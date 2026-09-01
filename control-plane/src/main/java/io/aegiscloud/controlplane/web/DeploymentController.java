package io.aegiscloud.controlplane.web;

import io.aegiscloud.controlplane.audit.AuditLog;
import io.aegiscloud.controlplane.auth.Tenant;
import io.aegiscloud.controlplane.domain.Models;
import io.aegiscloud.controlplane.k8s.ClusterConnectivity;
import io.aegiscloud.controlplane.k8s.DeploymentEngine;
import io.aegiscloud.controlplane.persistence.ClusterEntity;
import io.aegiscloud.controlplane.persistence.ClusterRepository;
import io.aegiscloud.controlplane.persistence.OrganizationEntity;
import io.aegiscloud.controlplane.persistence.OrganizationRepository;
import io.aegiscloud.controlplane.persistence.TargetRegistry;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The Deployment Engine's HTTP surface: register a cluster, probe it, and roll
 * workloads onto it.
 *
 * <p>All of these change cluster state, so they are OPERATOR+ throughout.
 */
@RestController
@RequestMapping("/api/v1")
public class DeploymentController {

    private final DeploymentEngine engine;
    private final ClusterRepository clusters;
    private final OrganizationRepository organizations;
    private final TargetRegistry targets;
    private final AuditLog audit;

    public DeploymentController(DeploymentEngine engine, ClusterRepository clusters,
                                OrganizationRepository organizations, TargetRegistry targets,
                                AuditLog audit) {
        this.engine = engine;
        this.clusters = clusters;
        this.organizations = organizations;
        this.targets = targets;
        this.audit = audit;
    }

    public record RegisterClusterRequest(
            @NotBlank String name,
            @NotBlank String provider,
            String distribution,
            String region,
            @NotBlank String kubeContext,
            boolean local) {
    }

    public record ClusterRegistration(String id, String name, String provider, String status,
                                      int nodeCount, String k8sVersion, String detail) {
    }

    /**
     * Registers a cluster and immediately probes it.
     *
     * <p>Registration and verification are one operation on purpose: a cluster
     * recorded but never contacted is an assertion, and the fleet view would show it
     * as real infrastructure before anyone knows whether it answers.
     */
    @PostMapping("/clusters")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ClusterRegistration register(@RequestBody RegisterClusterRequest request) {
        Models.ProviderType provider;
        try {
            provider = Models.ProviderType.valueOf(request.provider().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("unknown provider: " + request.provider());
        }

        // The cluster belongs to the caller's organisation, not to whichever
        // organisation happens to be first in the table.
        UUID ownerId = Tenant.currentOrgId();

        if (clusters.existsByOrgIdAndName(ownerId, request.name())) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT",
                    "a cluster named " + request.name() + " is already registered");
        }

        ClusterEntity saved = clusters.save(new ClusterEntity(
                ownerId, request.name(), provider, request.distribution(),
                request.region(), request.kubeContext(), request.local()));

        ClusterConnectivity probe = engine.probe(saved.getId());

        audit.recordUserAction("REGISTER_CLUSTER", "cluster", saved.getId().toString(),
                java.util.Map.of("name", saved.getName(), "provider", provider.name(),
                        "kubeContext", String.valueOf(request.kubeContext()),
                        "reachable", probe.reachable()));

        return new ClusterRegistration(saved.getId().toString(), saved.getName(),
                provider.name(), probe.reachable() ? "HEALTHY" : "UNREACHABLE",
                probe.nodeCount(), probe.k8sVersion(), probe.detail());
    }

    /** Re-probes a registered cluster and records what was observed. */
    @PostMapping("/clusters/{clusterId}/probe")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ClusterConnectivity probe(@PathVariable String clusterId) {
        UUID id;
        try {
            id = UUID.fromString(clusterId);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("cluster " + clusterId + " not found");
        }
        try {
            return engine.probe(id);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("cluster " + clusterId + " not found");
        }
    }

    /** Probes every active cluster, returning what each one actually reported. */
    @PostMapping("/clusters/probe-all")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public List<ClusterProbeResult> probeAll() {
        return clusters.findByActiveTrue().stream()
                .map(c -> new ClusterProbeResult(c.getName(), engine.probe(c.getId())))
                .toList();
    }

    public record ClusterProbeResult(String cluster, ClusterConnectivity connectivity) {
    }

    /**
     * @param adopt take ownership of a deployment that already exists and was not
     *              created by AegisCloud. Defaults to false, so the engine refuses
     *              rather than silently rewriting a workload someone else manages.
     */
    public record DeployRequest(
            @NotBlank String cluster,
            @NotBlank String namespace,
            @NotBlank String workload,
            @NotBlank String image,
            @Min(0) int replicas,
            @Min(1) int containerPort,
            boolean adopt) {
    }

    @PostMapping("/deployments")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public DeploymentEngine.DeploymentOutcome deploy(@RequestBody DeployRequest request) {
        try {
            DeploymentEngine.DeploymentOutcome outcome = engine.deploy(
                    request.cluster(), request.namespace(), request.workload(),
                    request.image(), request.replicas(), request.containerPort(), request.adopt());

            audit.recordUserAction("DEPLOY_WORKLOAD", "workload",
                    request.namespace() + "/" + request.workload(),
                    java.util.Map.of("cluster", request.cluster(), "image", request.image(),
                            "replicas", request.replicas(), "adopted", request.adopt(),
                            "succeeded", outcome.succeeded(), "detail", outcome.detail()));

            return outcome;
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound(e.getMessage());
        }
    }

    /**
     * Registers a running workload as a managed deployment target.
     *
     * <p>Deploying a workload and managing it are separate acts, and until now the
     * platform could do the first without the second: a service could be rolled out
     * and then be invisible to scaling, healing, evaluation and experiments, because
     * all four work from {@code deployment_target} rows. This is what puts a workload
     * under management.
     */
    public record RegisterTargetRequest(
            @NotBlank String serviceId,
            @NotBlank String clusterName,
            @NotBlank String namespace,
            String label,
            String scalingStrategy) {
    }

    public record TargetRegistration(String targetId, String serviceName, String clusterName,
                                     String namespace, String scalingStrategy, int replicas,
                                     String detail) {
    }

    @PostMapping("/targets")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public TargetRegistration registerTarget(@RequestBody RegisterTargetRequest request) {
        UUID serviceId;
        try {
            serviceId = UUID.fromString(request.serviceId());
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("service " + request.serviceId() + " not found");
        }

        ClusterEntity cluster = clusters.findByOrgIdAndName(Tenant.currentOrgId(), request.clusterName())
                .orElseThrow(() -> ApiException.notFound(
                        "cluster " + request.clusterName() + " not found"));

        Models.ScalingStrategy strategy;
        try {
            strategy = request.scalingStrategy() == null
                    ? Models.ScalingStrategy.NONE
                    : Models.ScalingStrategy.valueOf(
                            request.scalingStrategy().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("unknown scaling strategy: " + request.scalingStrategy());
        }

        // The workload has to exist before it can be managed. Registering a target
        // for something that is not running would put a row in the fleet view that
        // every engine then skips, which reads as a bug rather than as an absence.
        String serviceName = targets.serviceName(serviceId)
                .orElseThrow(() -> ApiException.notFound("service " + serviceId + " not found"));

        DeploymentEngine.DeploymentOutcome status =
                engine.status(cluster.getName(), request.namespace(), serviceName);

        if (!status.succeeded()) {
            throw ApiException.badRequest("workload " + request.namespace() + "/" + serviceName
                    + " is not running on " + cluster.getName() + ": " + status.detail());
        }

        UUID targetId = targets.register(serviceId, cluster.getId(), request.namespace(),
                request.label(), strategy, status.desiredReplicas());

        audit.recordUserAction("REGISTER_TARGET", "deployment_target", targetId.toString(),
                java.util.Map.of("service", serviceName, "cluster", cluster.getName(),
                        "namespace", request.namespace(), "scalingStrategy", strategy.name()));

        return new TargetRegistration(targetId.toString(), serviceName, cluster.getName(),
                request.namespace(), strategy.name(), status.desiredReplicas(),
                "registered and now managed by the control plane");
    }

    @GetMapping("/deployments/{cluster}/{namespace}/{workload}")
    public DeploymentEngine.DeploymentOutcome status(@PathVariable String cluster,
                                                     @PathVariable String namespace,
                                                     @PathVariable String workload) {
        try {
            return engine.status(cluster, namespace, workload);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound(e.getMessage());
        }
    }
}
