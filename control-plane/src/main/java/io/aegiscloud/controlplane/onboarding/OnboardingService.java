package io.aegiscloud.controlplane.onboarding;

import io.aegiscloud.controlplane.persistence.ApplicationEntity;
import io.aegiscloud.controlplane.persistence.ApplicationRepository;
import io.aegiscloud.controlplane.persistence.GitRepositoryEntity;
import io.aegiscloud.controlplane.persistence.GitRepositoryRepository;
import io.aegiscloud.controlplane.persistence.OrganizationEntity;
import io.aegiscloud.controlplane.persistence.OrganizationRepository;
import io.aegiscloud.controlplane.persistence.ProjectEntity;
import io.aegiscloud.controlplane.persistence.ProjectRepository;
import io.aegiscloud.controlplane.persistence.ServiceEntity;
import io.aegiscloud.controlplane.persistence.ServiceRepository;
import io.aegiscloud.controlplane.persistence.ServiceResourceEntity;
import io.aegiscloud.controlplane.persistence.ServiceResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Onboarding: projects, applications, the repository behind an application, and the
 * services discovered inside it.
 */
@Service
public class OnboardingService {

    private static final Logger log = LoggerFactory.getLogger(OnboardingService.class);

    /**
     * Ceiling on Dockerfile reads per scan. Each one is a separate GitHub request,
     * and an unbounded monorepo scan would burn an hour's rate limit discovering
     * port numbers the operator can also just type in.
     */
    private static final int MAX_DOCKERFILE_READS = 25;

    private final ProjectRepository projects;
    private final ApplicationRepository applications;
    private final GitRepositoryRepository repositories;
    private final ServiceRepository services;
    private final ServiceResourceRepository resources;
    private final OrganizationRepository organizations;
    private final GitHubClient github;
    private final ServiceDiscovery discovery;

    public OnboardingService(ProjectRepository projects, ApplicationRepository applications,
                             GitRepositoryRepository repositories, ServiceRepository services,
                             ServiceResourceRepository resources, OrganizationRepository organizations,
                             GitHubClient github, ServiceDiscovery discovery) {
        this.projects = projects;
        this.applications = applications;
        this.repositories = repositories;
        this.services = services;
        this.resources = resources;
        this.organizations = organizations;
        this.github = github;
        this.discovery = discovery;
    }

    /* -------------------------------- projects ------------------------------ */

    @Transactional
    public ProjectEntity createProject(String name, String description) {
        UUID orgId = currentOrgId();
        projects.findByOrgIdAndName(orgId, name).ifPresent(existing -> {
            throw new IllegalStateException("a project named '" + name + "' already exists");
        });
        return projects.save(new ProjectEntity(orgId, name, description));
    }

    public List<ProjectEntity> listProjects() {
        return projects.findByOrgId(currentOrgId());
    }

    /* ------------------------------ applications ---------------------------- */

    @Transactional
    public ApplicationEntity createApplication(UUID projectId, String name, String description) {
        projects.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("no such project: " + projectId));

        applications.findByProjectIdAndName(projectId, name).ifPresent(existing -> {
            throw new IllegalStateException("an application named '" + name + "' already exists in this project");
        });
        return applications.save(new ApplicationEntity(projectId, name, description));
    }

    public List<ApplicationEntity> listApplications(UUID projectId) {
        return applications.findByProjectId(projectId);
    }

    /* ------------------------------- repository ----------------------------- */

    /**
     * Connects a GitHub repository to an application, verifying it is reachable
     * before storing it.
     *
     * <p>Verification is not optional: recording a repository nobody has managed to
     * read produces an application that looks connected and discovers nothing, and
     * the failure then surfaces later, further from its cause.
     */
    @Transactional
    public GitRepositoryEntity connectRepository(UUID applicationId, String owner, String repo,
                                                 String credentialsRef) {
        applications.findById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("no such application: " + applicationId));

        GitHubClient.RepositoryInfo info = github.repository(owner, repo);

        GitRepositoryEntity entity = repositories.findByApplicationId(applicationId)
                .orElseGet(() -> new GitRepositoryEntity(applicationId, "GITHUB", owner, repo,
                        info.htmlUrl(), credentialsRef));

        entity.recordSuccessfulSync(info.defaultBranch(),
                "connected to " + info.fullName() + (info.isPrivate() ? " (private)" : " (public)"));

        return repositories.save(entity);
    }

    public Optional<GitRepositoryEntity> repositoryFor(UUID applicationId) {
        return repositories.findByApplicationId(applicationId);
    }

    /* ------------------------------- discovery ------------------------------ */

    /** What a scan found, and what it did about it. */
    public record DiscoveryReport(
            String repository,
            String branch,
            boolean truncated,
            int filesScanned,
            List<ServiceDiscovery.DiscoveredService> found,
            List<String> created,
            List<String> updated) {
    }

    /**
     * Scans the connected repository and records the services it contains.
     *
     * <p>Re-scanning is safe. A service already present is updated in place rather
     * than duplicated, and one that was registered by hand is left alone — discovery
     * infers, and inference should not overwrite something a person stated.
     */
    @Transactional
    public DiscoveryReport discoverServices(UUID applicationId) {
        ApplicationEntity application = applications.findById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("no such application: " + applicationId));

        GitRepositoryEntity repository = repositories.findByApplicationId(applicationId)
                .orElseThrow(() -> new IllegalStateException(
                        "application '" + application.getName() + "' has no repository connected"));

        String branch = repository.getDefaultBranch() == null ? "HEAD" : repository.getDefaultBranch();

        final GitHubClient.TreeListing tree;
        try {
            tree = github.tree(repository.getOwner(), repository.getRepo(), branch);
        } catch (RuntimeException e) {
            repository.recordFailedSync(e.getMessage());
            repositories.save(repository);
            throw e;
        }

        List<ServiceDiscovery.DiscoveredService> found =
                discovery.discover(tree.entries(), repository.getRepo());

        found = enrichWithPorts(repository, branch, found);

        UUID orgId = currentOrgId();
        List<String> created = new ArrayList<>();
        List<String> updated = new ArrayList<>();

        for (ServiceDiscovery.DiscoveredService candidate : found) {
            Optional<ServiceEntity> existing = services.findByOrgIdAndName(orgId, candidate.name());

            if (existing.isPresent()) {
                ServiceEntity service = existing.get();
                if ("MANUAL".equals(service.getDiscoverySource()) && service.getApplicationId() == null) {
                    // Registered by a person before any repository was connected.
                    // Leave their record intact.
                    continue;
                }
                service.applyDiscovery(applicationId, candidate.path(), candidate.language(),
                        candidate.buildType(), candidate.containerPort(), candidate.dockerfilePath());
                services.save(service);
                updated.add(service.getName());
            } else {
                ServiceEntity service = new ServiceEntity(orgId, candidate.name());
                service.setDescription("Discovered in " + repository.getOwner() + "/"
                        + repository.getRepo()
                        + (candidate.path().isEmpty() ? "" : " at " + candidate.path()));
                service.applyDiscovery(applicationId, candidate.path(), candidate.language(),
                        candidate.buildType(), candidate.containerPort(), candidate.dockerfilePath());
                ServiceEntity saved = services.save(service);

                // Every service gets a resource row so manifest generation always has
                // requests and limits to work from, rather than deploying unbounded.
                resources.save(new ServiceResourceEntity(saved.getId()));
                created.add(saved.getName());
            }
        }

        repository.recordSuccessfulSync(branch,
                "scanned " + tree.entries().size() + " paths, found " + found.size() + " service(s)");
        repositories.save(repository);

        log.info("discovery on {}/{}: {} found, {} created, {} updated",
                repository.getOwner(), repository.getRepo(), found.size(), created.size(), updated.size());

        return new DiscoveryReport(repository.getOwner() + "/" + repository.getRepo(), branch,
                tree.truncated(), tree.entries().size(), found, created, updated);
    }

    /** Reads each service's Dockerfile to recover the port it declares. */
    private List<ServiceDiscovery.DiscoveredService> enrichWithPorts(
            GitRepositoryEntity repository, String branch,
            List<ServiceDiscovery.DiscoveredService> found) {

        List<ServiceDiscovery.DiscoveredService> enriched = new ArrayList<>(found.size());
        int reads = 0;

        for (ServiceDiscovery.DiscoveredService service : found) {
            Integer port = null;

            if (service.dockerfilePath() != null && reads < MAX_DOCKERFILE_READS) {
                reads++;
                port = github.fileContent(repository.getOwner(), repository.getRepo(),
                                branch, service.dockerfilePath())
                        .flatMap(discovery::containerPortFrom)
                        .orElse(null);
            }

            enriched.add(new ServiceDiscovery.DiscoveredService(
                    service.name(), service.path(), service.language(), service.buildType(),
                    service.dockerfilePath(), port, service.evidence()));
        }
        return enriched;
    }

    /* -------------------------------- helpers ------------------------------- */

    /**
     * The owning organization. Single-tenant today; Phase 15 replaces this with the
     * caller's own org, which is why every write already carries an org id.
     */
    private UUID currentOrgId() {
        return organizations.findAll().stream()
                .findFirst()
                .map(OrganizationEntity::getId)
                .orElseThrow(() -> new IllegalStateException("no organization exists"));
    }
}
