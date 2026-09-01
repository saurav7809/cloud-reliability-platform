package io.aegiscloud.controlplane.web;

import io.aegiscloud.controlplane.onboarding.GitHubClient;
import io.aegiscloud.controlplane.onboarding.OnboardingService;
import io.aegiscloud.controlplane.onboarding.ServiceConfigService;
import io.aegiscloud.controlplane.persistence.ApplicationEntity;
import io.aegiscloud.controlplane.persistence.GitRepositoryEntity;
import io.aegiscloud.controlplane.persistence.ProjectEntity;
import io.aegiscloud.controlplane.persistence.ServiceEntity;
import io.aegiscloud.controlplane.persistence.ServiceEnvVarEntity;
import io.aegiscloud.controlplane.persistence.ServiceRepository;
import io.aegiscloud.controlplane.persistence.ServiceResourceEntity;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phase 03: onboarding an application and the microservices inside it.
 *
 * <p>Reads are open to any authenticated user; anything that changes the inventory
 * is OPERATOR+.
 */
@RestController
@RequestMapping("/api/v1")
public class OnboardingController {

    private final OnboardingService onboarding;
    private final ServiceConfigService config;
    private final ServiceRepository services;

    public OnboardingController(OnboardingService onboarding, ServiceConfigService config,
                                ServiceRepository services) {
        this.onboarding = onboarding;
        this.config = config;
        this.services = services;
    }

    /* -------------------------------- projects ------------------------------ */

    public record ProjectRequest(@NotBlank String name, String description) {
    }

    public record ProjectView(String id, String name, String description, Instant createdAt) {
        static ProjectView of(ProjectEntity e) {
            return new ProjectView(e.getId().toString(), e.getName(), e.getDescription(), e.getCreatedAt());
        }
    }

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ProjectView createProject(@RequestBody ProjectRequest request) {
        return ProjectView.of(guard(() -> onboarding.createProject(request.name(), request.description())));
    }

    @GetMapping("/projects")
    public List<ProjectView> listProjects() {
        return onboarding.listProjects().stream().map(ProjectView::of).toList();
    }

    /* ------------------------------ applications ---------------------------- */

    public record ApplicationRequest(@NotBlank String name, String description) {
    }

    public record ApplicationView(String id, String projectId, String name, String description,
                                  Instant createdAt) {
        static ApplicationView of(ApplicationEntity e) {
            return new ApplicationView(e.getId().toString(), e.getProjectId().toString(),
                    e.getName(), e.getDescription(), e.getCreatedAt());
        }
    }

    @PostMapping("/projects/{projectId}/applications")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ApplicationView createApplication(@PathVariable String projectId,
                                             @RequestBody ApplicationRequest request) {
        UUID id = uuid(projectId, "project");
        return ApplicationView.of(guard(() ->
                onboarding.createApplication(id, request.name(), request.description())));
    }

    @GetMapping("/projects/{projectId}/applications")
    public List<ApplicationView> listApplications(@PathVariable String projectId) {
        return onboarding.listApplications(uuid(projectId, "project")).stream()
                .map(ApplicationView::of).toList();
    }

    /* ------------------------------- repository ----------------------------- */

    public record ConnectRepositoryRequest(@NotBlank String owner, @NotBlank String repo,
                                           String credentialsRef) {
    }

    public record RepositoryView(String id, String provider, String owner, String repo, String url,
                                 String defaultBranch, Instant lastSyncedAt, String lastSyncStatus,
                                 String lastSyncDetail) {
        static RepositoryView of(GitRepositoryEntity e) {
            return new RepositoryView(e.getId().toString(), e.getProvider(), e.getOwner(), e.getRepo(),
                    e.getUrl(), e.getDefaultBranch(), e.getLastSyncedAt(), e.getLastSyncStatus(),
                    e.getLastSyncDetail());
        }
    }

    @PostMapping("/applications/{applicationId}/repository")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public RepositoryView connectRepository(@PathVariable String applicationId,
                                            @RequestBody ConnectRepositoryRequest request) {
        UUID id = uuid(applicationId, "application");
        return RepositoryView.of(guard(() -> onboarding.connectRepository(
                id, request.owner(), request.repo(), request.credentialsRef())));
    }

    @GetMapping("/applications/{applicationId}/repository")
    public RepositoryView repository(@PathVariable String applicationId) {
        return onboarding.repositoryFor(uuid(applicationId, "application"))
                .map(RepositoryView::of)
                .orElseThrow(() -> ApiException.notFound("no repository connected to this application"));
    }

    /* ------------------------------- discovery ------------------------------ */

    @PostMapping("/applications/{applicationId}/discover")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public OnboardingService.DiscoveryReport discover(@PathVariable String applicationId) {
        return guard(() -> onboarding.discoverServices(uuid(applicationId, "application")));
    }

    public record ServiceView(String id, String name, String description, String language,
                              String buildType, String sourcePath, String dockerfilePath,
                              Integer containerPort, String discoverySource) {
        static ServiceView of(ServiceEntity e) {
            return new ServiceView(e.getId().toString(), e.getName(), e.getDescription(),
                    e.getLanguage(), e.getBuildType(), e.getSourcePath(), e.getDockerfilePath(),
                    e.getContainerPort(), e.getDiscoverySource());
        }
    }

    @GetMapping("/applications/{applicationId}/services")
    public List<ServiceView> applicationServices(@PathVariable String applicationId) {
        return services.findByApplicationId(uuid(applicationId, "application")).stream()
                .map(ServiceView::of).toList();
    }

    /* ---------------------------- service config ---------------------------- */

    public record ResourceRequest(@Min(1) int cpuRequestMillicores, @Min(1) int cpuLimitMillicores,
                                  @Min(1) int memoryRequestMib, @Min(1) int memoryLimitMib,
                                  @Min(0) int desiredReplicas) {
    }

    public record ResourceView(int cpuRequestMillicores, int cpuLimitMillicores,
                               int memoryRequestMib, int memoryLimitMib, int desiredReplicas) {
        static ResourceView of(ServiceResourceEntity e) {
            return new ResourceView(e.getCpuRequestMillicores(), e.getCpuLimitMillicores(),
                    e.getMemoryRequestMib(), e.getMemoryLimitMib(), e.getDesiredReplicas());
        }
    }

    @GetMapping("/services/{serviceId}/resources")
    public ResourceView resources(@PathVariable String serviceId) {
        return ResourceView.of(guard(() -> config.resourcesFor(uuid(serviceId, "service"))));
    }

    @PutMapping("/services/{serviceId}/resources")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResourceView updateResources(@PathVariable String serviceId,
                                        @RequestBody ResourceRequest request) {
        UUID id = uuid(serviceId, "service");
        return ResourceView.of(guard(() -> config.updateResources(id,
                request.cpuRequestMillicores(), request.cpuLimitMillicores(),
                request.memoryRequestMib(), request.memoryLimitMib(), request.desiredReplicas())));
    }

    public record EnvVarRequest(@NotBlank String key, String value, boolean secret) {
    }

    /** {@code value} is null for secrets — see {@code ServiceEnvVarEntity}. */
    public record EnvVarView(String key, String value, boolean secret) {
        static EnvVarView of(ServiceEnvVarEntity e) {
            return new EnvVarView(e.getEnvKey(), e.getEnvValue(), e.isSecret());
        }
    }

    @GetMapping("/services/{serviceId}/env")
    public List<EnvVarView> envVars(@PathVariable String serviceId) {
        UUID id = uuid(serviceId, "service");
        return guard(() -> config.envVarsFor(id)).stream().map(EnvVarView::of).toList();
    }

    @PutMapping("/services/{serviceId}/env")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public EnvVarView setEnvVar(@PathVariable String serviceId, @RequestBody EnvVarRequest request) {
        UUID id = uuid(serviceId, "service");
        return EnvVarView.of(guard(() ->
                config.setEnvVar(id, request.key(), request.value(), request.secret())));
    }

    @DeleteMapping("/services/{serviceId}/env/{key}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public void deleteEnvVar(@PathVariable String serviceId, @PathVariable String key) {
        if (!guard(() -> config.deleteEnvVar(uuid(serviceId, "service"), key))) {
            throw ApiException.notFound("no environment variable '" + key + "' on this service");
        }
    }

    /* -------------------------------- plumbing ------------------------------ */

    private static UUID uuid(String raw, String what) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound(what + " " + raw + " not found");
        }
    }

    /**
     * Maps the service layer's exceptions onto HTTP.
     *
     * <p>The distinction matters to a caller: a bad id is 404, a duplicate name is
     * 409, an invalid value is 400, and a GitHub problem is 502 with GitHub's own
     * explanation — which is usually actionable ("set a token", "rate limit").
     */
    private static <T> T guard(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (GitHubClient.GitHubException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_ERROR", e.getMessage());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", e.getMessage());
        }
    }
}
