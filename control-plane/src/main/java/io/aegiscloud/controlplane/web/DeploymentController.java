package io.aegiscloud.controlplane.web;

import io.aegiscloud.controlplane.domain.Models;
import io.aegiscloud.controlplane.k8s.ClusterConnectivity;
import io.aegiscloud.controlplane.k8s.DeploymentEngine;
import io.aegiscloud.controlplane.persistence.ClusterEntity;
import io.aegiscloud.controlplane.persistence.ClusterRepository;
import io.aegiscloud.controlplane.persistence.OrganizationEntity;
import io.aegiscloud.controlplane.persistence.OrganizationRepository;
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

    public DeploymentController(DeploymentEngine engine, ClusterRepository clusters,
                                OrganizationRepository organizations) {
        this.engine = engine;
        this.clusters = clusters;
        this.organizations = organizations;
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

        OrganizationEntity owner = organizations.findAll().stream().findFirst()
                .orElseThrow(() -> ApiException.badRequest("no organization exists to own this cluster"));

        if (clusters.existsByOrgIdAndName(owner.getId(), request.name())) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT",
                    "a cluster named " + request.name() + " is already registered");
        }

        ClusterEntity saved = clusters.save(new ClusterEntity(
                owner.getId(), request.name(), provider, request.distribution(),
                request.region(), request.kubeContext(), request.local()));

        ClusterConnectivity probe = engine.probe(saved.getId());

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
            return engine.deploy(request.cluster(), request.namespace(), request.workload(),
                    request.image(), request.replicas(), request.containerPort(), request.adopt());
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound(e.getMessage());
        }
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
