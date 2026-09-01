package io.aegiscloud.controlplane.web;

import io.aegiscloud.controlplane.audit.AuditLog;
import io.aegiscloud.controlplane.auth.Tenant;
import io.aegiscloud.controlplane.build.BuildStore;
import io.aegiscloud.controlplane.domain.Models;
import io.aegiscloud.controlplane.eval.EvaluationStore;
import io.aegiscloud.controlplane.k8s.DeploymentEngine;
import io.aegiscloud.controlplane.k8s.WorkloadOperations;
import io.aegiscloud.controlplane.persistence.ClusterRepository;
import io.aegiscloud.controlplane.persistence.TargetRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Registering a microservice: one call that declares it, deploys it, puts it under
 * management and starts measuring it.
 *
 * <p>Every step here is already an endpoint of its own, and they remain so — this
 * exists because doing it in six calls made the client responsible for the order and
 * for cleaning up when step four failed. Onboarding a service is one intention, and a
 * half-registered service is the worst outcome available: deployed and running, but
 * invisible to scaling, healing, evaluation and RCA, which is precisely the state
 * nobody notices until an incident.
 *
 * <p>What it deliberately does not do is hide the pieces. The response reports each
 * step, so a registration that got as far as deploying but failed to probe says so
 * rather than reporting a generic failure.
 */
@RestController
@RequestMapping("/api/v1")
public class MicroserviceController {

    private static final Logger log = LoggerFactory.getLogger(MicroserviceController.class);

    private final DeploymentEngine engine;
    private final TargetRegistry targets;
    private final EvaluationStore evaluation;
    private final ClusterRepository clusters;
    private final WorkloadOperations workloads;
    private final BuildStore builds;
    private final AuditLog audit;

    public MicroserviceController(DeploymentEngine engine, TargetRegistry targets,
                                  EvaluationStore evaluation, ClusterRepository clusters,
                                  WorkloadOperations workloads, BuildStore builds,
                                  AuditLog audit) {
        this.engine = engine;
        this.targets = targets;
        this.evaluation = evaluation;
        this.clusters = clusters;
        this.workloads = workloads;
        this.builds = builds;
        this.audit = audit;
    }

    /**
     * @param probePath   the endpoint the platform will measure. Defaults to
     *                    {@code /api/work} rather than a health check: liveness is
     *                    self-only by design, while the working endpoint fails when a
     *                    dependency fails, and that is what availability should mean.
     * @param dependencies service name to URL, passed to the container as DEPENDENCIES
     */
    public record RegisterRequest(
            @NotBlank String name,
            @NotBlank String image,
            @NotBlank String cluster,
            String namespace,
            @Min(1) int replicas,
            @Min(1) int containerPort,
            String probePath,
            String ownerTeam,
            String scalingStrategy,
            Double latencyObjectiveMs,
            Double availabilityObjectivePct,
            Map<String, String> dependencies) {
    }

    /** Each step, named, so a partial registration is legible. */
    public record RegisterResult(
            String serviceId,
            String targetId,
            String name,
            String namespace,
            boolean deployed,
            boolean managed,
            boolean measured,
            List<String> steps,
            String detail) {
    }

    @PostMapping("/microservices")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public RegisterResult register(@Valid @RequestBody RegisterRequest request) {
        UUID orgId = Tenant.currentOrgId();
        List<String> steps = new ArrayList<>();

        String namespace = blank(request.namespace()) ? "aegiscloud-live" : request.namespace();
        String probePath = blank(request.probePath()) ? "/api/work" : request.probePath();

        var cluster = clusters.findByOrgIdAndName(orgId, request.cluster())
                .orElseThrow(() -> ApiException.notFound(
                        "cluster " + request.cluster() + " not found"));

        // 1. Declare it.
        UUID serviceId = targets.createService(orgId, request.name(),
                "registered through the platform from " + request.image(), request.ownerTeam());
        steps.add("declared service " + request.name());

        // 2. Deploy it. Dependencies reach the container as environment, which is how
        //    a service is told where the things it calls actually live.
        Map<String, String> env = request.dependencies() == null || request.dependencies().isEmpty()
                ? Map.of()
                : Map.of("DEPENDENCIES", request.dependencies().entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .reduce((a, b) -> a + "," + b).orElse(""));

        DeploymentEngine.DeploymentOutcome outcome = engine.deploy(
                cluster.getName(), namespace, request.name(), request.image(),
                request.replicas(), request.containerPort(), true, env);

        builds.recordDeployment(cluster.getId(), namespace, request.name(), request.image(),
                outcome.previousImage(), request.replicas(), env,
                UUID.fromString(io.aegiscloud.controlplane.auth.CurrentUser.get().id()),
                outcome.succeeded(), outcome.detail());

        if (!outcome.succeeded()) {
            steps.add("deployment failed: " + outcome.detail());
            // Stopping here rather than registering a target for a workload that is
            // not running: every engine would then skip it and report it as missing,
            // which reads as a platform bug rather than a failed deployment.
            return new RegisterResult(serviceId.toString(), null, request.name(), namespace,
                    false, false, false, steps,
                    "the workload could not be deployed, so it was not registered");
        }
        steps.add("deployed " + request.image() + " (" + request.replicas() + " replicas)");

        // 3. Put it under management.
        Models.ScalingStrategy strategy = parseStrategy(request.scalingStrategy());
        UUID targetId = targets.register(serviceId, cluster.getId(), namespace, "live",
                strategy, request.replicas());
        steps.add("registered as a managed target, scaling strategy " + strategy);

        // 4. Start measuring it. Without an endpoint the target has a score of nothing
        //    and every engine downstream correctly declines to reason about it.
        evaluation.addEndpoint(targetId, "HTTP",
                "k8s://" + namespace + "/" + request.name() + ":80" + probePath,
                30, 3000, 200);
        steps.add("probing k8s://" + namespace + "/" + request.name() + ":80" + probePath);

        double availability = request.availabilityObjectivePct() == null
                ? 99.0 : request.availabilityObjectivePct();
        double latency = request.latencyObjectiveMs() == null
                ? 250.0 : request.latencyObjectiveMs();

        evaluation.addSlo(targetId, Models.SliType.AVAILABILITY, availability, 1);
        evaluation.addSlo(targetId, Models.SliType.LATENCY_P95, latency, 1);
        steps.add(String.format("SLOs: %.1f%% availability, p95 under %.0fms",
                availability, latency));

        audit.recordUserAction("REGISTER_MICROSERVICE", "service", serviceId.toString(),
                Map.of("name", request.name(), "image", request.image(),
                        "namespace", namespace, "replicas", request.replicas()));

        log.info("registered microservice {} in {}", request.name(), namespace);

        return new RegisterResult(serviceId.toString(), targetId.toString(), request.name(),
                namespace, true, true, true, steps,
                "registered and being measured; the first score appears within a probe cycle");
    }

    /** A registered microservice with its live cluster state beside its recorded one. */
    public record MicroserviceView(
            String targetId,
            String name,
            String namespace,
            String cluster,
            String image,
            int desiredReplicas,
            int readyReplicas,
            String health,
            List<String> dependencies) {
    }

    /**
     * What is registered, with what the cluster says about it right now.
     *
     * <p>Recorded state and live state side by side on purpose: the interesting cases
     * are exactly where they disagree — a target registered for a workload that has
     * been deleted, or one running an image nobody recorded deploying.
     */
    @GetMapping("/microservices")
    public List<MicroserviceView> list() {
        UUID orgId = Tenant.currentOrgId();
        List<MicroserviceView> views = new ArrayList<>();

        // Grouped by target, not by endpoint. A service may legitimately have more
        // than one probe, and listing it once per probe would show it twice on a
        // screen whose whole job is to say what is registered.
        java.util.Map<UUID, EvaluationStore.ProbeEndpoint> byTarget = new java.util.LinkedHashMap<>();
        for (EvaluationStore.ProbeEndpoint endpoint : evaluation.activeEndpoints()) {
            byTarget.putIfAbsent(endpoint.targetId(), endpoint);
        }

        for (EvaluationStore.ProbeEndpoint endpoint : byTarget.values()) {
            WorkloadObservationSummary summary = observe(endpoint);

            String image = builds.history(orgId, endpoint.serviceName(), 1).stream()
                    .findFirst().map(BuildStore.DeploymentRecord::image).orElse("unrecorded");

            List<String> dependencies = builds.history(orgId, endpoint.serviceName(), 1).stream()
                    .findFirst()
                    .map(record -> String.valueOf(record.env().getOrDefault("DEPENDENCIES", "")))
                    .filter(value -> !value.isBlank() && !"null".equals(value))
                    .map(value -> List.of(value.split(",")))
                    .orElse(List.of());

            views.add(new MicroserviceView(
                    endpoint.targetId().toString(), endpoint.serviceName(), endpoint.namespace(),
                    endpoint.clusterName(), image, summary.desired(), summary.ready(),
                    summary.health(), dependencies));
        }

        return views;
    }

    private record WorkloadObservationSummary(int desired, int ready, String health) {
    }

    private WorkloadObservationSummary observe(EvaluationStore.ProbeEndpoint endpoint) {
        var observation = workloads.observe(endpoint.kubeContext(), endpoint.namespace(),
                endpoint.serviceName());

        if (!observation.found()) {
            return new WorkloadObservationSummary(0, 0, "MISSING");
        }
        String health = observation.readyReplicas() == 0 ? "DOWN"
                : observation.readyReplicas() < observation.desiredReplicas() ? "DEGRADED"
                : "HEALTHY";

        return new WorkloadObservationSummary(observation.desiredReplicas(),
                observation.readyReplicas(), health);
    }

    private static Models.ScalingStrategy parseStrategy(String raw) {
        if (blank(raw)) {
            return Models.ScalingStrategy.CPU;
        }
        try {
            return Models.ScalingStrategy.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("unknown scaling strategy: " + raw);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
