package io.aegiscloud.controlplane.web;

import io.aegiscloud.controlplane.audit.AuditLog;
import io.aegiscloud.controlplane.auth.CurrentUser;
import io.aegiscloud.controlplane.auth.Tenant;
import io.aegiscloud.controlplane.build.BuildStore;
import io.aegiscloud.controlplane.build.ImageBuildService;
import io.aegiscloud.controlplane.k8s.DeploymentEngine;
import io.aegiscloud.controlplane.persistence.ClusterRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Building images and the record of what was deployed (section 04).
 *
 * <p>Building is OPERATOR+: a build produces an artefact the platform will later run
 * in a cluster, and an image is as consequential as the deployment that uses it.
 */
@RestController
@RequestMapping("/api/v1")
public class BuildController {

    private final ImageBuildService builds;
    private final BuildStore store;
    private final DeploymentEngine engine;
    private final AuditLog audit;
    private final ClusterRepository clusters;

    public BuildController(ImageBuildService builds, BuildStore store,
                           DeploymentEngine engine, AuditLog audit,
                           ClusterRepository clusters) {
        this.builds = builds;
        this.store = store;
        this.engine = engine;
        this.audit = audit;
        this.clusters = clusters;
    }

    public record BuildRequestBody(
            String serviceId,
            @NotBlank String clusterName,
            @NotBlank String gitUrl,
            String gitRef,
            String contextPath,
            String dockerfile,
            @NotBlank String imageName,
            String tag) {
    }

    /**
     * Builds an image from a Git repository and pushes it to the registry.
     *
     * <p>Returns as soon as the build is running. Builds take minutes and an HTTP
     * call that waited would time out before the image existed, leaving the caller
     * unable to distinguish a slow build from a failed one.
     */
    @PostMapping("/builds")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ImageBuildService.BuildStarted build(@Valid @RequestBody BuildRequestBody request) {
        try {
            return builds.start(new ImageBuildService.BuildRequest(
                    request.serviceId() == null || request.serviceId().isBlank()
                            ? null : UUID.fromString(request.serviceId()),
                    request.clusterName(),
                    request.gitUrl(),
                    request.gitRef() == null || request.gitRef().isBlank()
                            ? "main" : request.gitRef(),
                    request.contextPath() == null || request.contextPath().isBlank()
                            ? "." : request.contextPath(),
                    request.dockerfile() == null || request.dockerfile().isBlank()
                            ? "Dockerfile" : request.dockerfile(),
                    request.imageName(),
                    request.tag()));

        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT,
                    "CONFLICT", e.getMessage());
        }
    }

    @GetMapping("/builds")
    public List<BuildStore.BuildRow> builds(@RequestParam(defaultValue = "50") int limit) {
        return store.builds(Tenant.currentOrgId(), Math.min(Math.max(limit, 1), 500));
    }

    @GetMapping("/builds/{buildId}")
    public BuildStore.BuildRow build(@PathVariable String buildId) {
        return store.build(uuid(buildId))
                .orElseThrow(() -> ApiException.notFound("build " + buildId + " not found"));
    }

    /** The build log, which is where the reason a build failed actually lives. */
    @GetMapping("/builds/{buildId}/logs")
    public Map<String, String> logs(@PathVariable String buildId) {
        return Map.of("logs", builds.logs(uuid(buildId))
                .orElseThrow(() -> ApiException.notFound("no logs for build " + buildId)));
    }

    @GetMapping("/registry")
    public Map<String, Object> registry() {
        return Map.of(
                "configured", builds.registryConfigured(),
                "url", builds.registry(),
                "note", builds.registryConfigured()
                        ? "images are built by a Kaniko Job in the cluster and pushed here"
                        : "set aegiscloud.registry.url to enable builds");
    }

    /** What was deployed, when, by whom, and what it replaced. */
    @GetMapping("/deployments/history")
    public List<BuildStore.DeploymentRecord> history(
            @RequestParam(required = false) String workload,
            @RequestParam(defaultValue = "50") int limit) {
        return store.history(Tenant.currentOrgId(), workload, Math.min(Math.max(limit, 1), 500));
    }

    public record RollbackRequest(@NotBlank String cluster, @NotBlank String namespace,
                                  @NotBlank String workload) {
    }

    /**
     * Redeploys the previous successful image for a workload.
     *
     * <p>The target comes from deployment history rather than from the caller, which
     * is the point: rolling back should not require a human to remember which tag was
     * running before, at the moment they are least able to. A deployment whose own
     * rollout failed is skipped — rolling back to it would replace one broken state
     * with another.
     */
    @PostMapping("/deployments/rollback")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public Map<String, Object> rollback(@Valid @RequestBody RollbackRequest request) {
        UUID orgId = Tenant.currentOrgId();

        BuildStore.DeploymentRecord previous = store.previousSuccessful(orgId, request.workload())
                .orElseThrow(() -> ApiException.badRequest(
                        "no earlier successful deployment of " + request.workload()
                                + " to roll back to"));

        Map<String, String> env = previous.env().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        entry -> String.valueOf(entry.getValue())));

        DeploymentEngine.DeploymentOutcome outcome = engine.deploy(
                request.cluster(), request.namespace(), request.workload(),
                previous.image(), previous.replicas(), 8080, true, env);

        // A rollback is a deployment and belongs in the history like any other.
        // Without this the record says one image is running while the cluster runs
        // another, and the next rollback reads that stale record as its target.
        clusters.findByOrgIdAndName(orgId, request.cluster()).ifPresent(cluster ->
                store.recordDeployment(cluster.getId(), request.namespace(), request.workload(),
                        previous.image(), outcome.previousImage(), previous.replicas(), env,
                        UUID.fromString(CurrentUser.get().id()), outcome.succeeded(),
                        "rollback: " + outcome.detail()));

        audit.recordUserAction("ROLLBACK_DEPLOYMENT", "workload",
                request.namespace() + "/" + request.workload(),
                Map.of("rolledBackTo", previous.image(), "succeeded", outcome.succeeded()));

        return Map.of(
                "workload", request.workload(),
                "rolledBackTo", previous.image(),
                "deployedAt", previous.deployedAt().toString(),
                "succeeded", outcome.succeeded(),
                "detail", outcome.detail());
    }

    private static UUID uuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("not a valid id: " + raw);
        }
    }
}
