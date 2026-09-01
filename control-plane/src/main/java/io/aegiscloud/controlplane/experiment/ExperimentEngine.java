package io.aegiscloud.controlplane.experiment;

import io.aegiscloud.controlplane.engine.ControlPlaneEvents;
import io.aegiscloud.controlplane.engine.ControlPlaneStore;
import io.aegiscloud.controlplane.engine.PolicyLimits;
import io.aegiscloud.controlplane.eval.EvaluationEngine;
import io.aegiscloud.controlplane.eval.EvaluationStore;
import io.aegiscloud.controlplane.graph.DependencyDiscovery;
import io.aegiscloud.controlplane.k8s.WorkloadOperations;
import io.aegiscloud.controlplane.k8s.WorkloadOperations.PodObservation;
import io.aegiscloud.controlplane.k8s.WorkloadOperations.WorkloadObservation;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The Experiment Engine (FR-17, FR-18): break something on purpose, measure what
 * happened, then put it back.
 *
 * <p>The shape of every run is the same — measure, inject, observe, restore, measure
 * again — and the restore is in a finally block because it is the only part that is
 * not optional. An experiment that fails to undo itself has stopped being an
 * experiment and become an incident, so restoration happens even when the run is
 * aborted, even when observation throws, and it is recorded whether or not it worked.
 *
 * <p>This phase comes before root-cause analysis deliberately. A chaos run is the
 * only incident whose true cause is known in advance, because the engine caused it
 * and wrote down what it did. That record is the ground truth Phase 8 will be scored
 * against, which is what turns "the RCA looks plausible" into "the RCA is right 8
 * times out of 10".
 */
@Service
public class ExperimentEngine {

    private static final Logger log = LoggerFactory.getLogger(ExperimentEngine.class);

    /** How often the steady-state hypothesis is checked while a fault is injected. */
    private static final Duration OBSERVATION_INTERVAL = Duration.ofSeconds(5);

    private final ExperimentStore store;
    private final ControlPlaneStore controlPlane;
    private final WorkloadOperations workloads;
    private final EvaluationEngine evaluation;
    private final EvaluationStore evaluationStore;
    private final DependencyDiscovery discovery;
    private final ControlPlaneEvents events;

    /**
     * Experiments run off the request thread: a run lasts minutes, and an HTTP call
     * that blocks for its duration would time out long before the fault is undone,
     * leaving the caller with no idea whether it was.
     */
    private final ExecutorService runner = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "aegis-experiment");
        // Not a daemon: a JVM shutdown must not abandon a thread that still has a
        // fault to undo. Shutdown waits for it below.
        thread.setDaemon(false);
        return thread;
    });

    public ExperimentEngine(ExperimentStore store, ControlPlaneStore controlPlane,
                            WorkloadOperations workloads, EvaluationEngine evaluation,
                            EvaluationStore evaluationStore, DependencyDiscovery discovery,
                            ControlPlaneEvents events) {
        this.store = store;
        this.controlPlane = controlPlane;
        this.workloads = workloads;
        this.evaluation = evaluation;
        this.evaluationStore = evaluationStore;
        this.discovery = discovery;
        this.events = events;
    }

    /**
     * @param dependencyTargetId required for DEPENDENCY_OUTAGE: the target to take
     *                           down, which must not be the one under observation
     * @param abortIfScoreBelow  the steady-state hypothesis. When the observed score
     *                           falls below this, the fault is undone immediately
     *                           rather than at the end of the duration
     */
    public record ExperimentRequest(
            UUID targetId,
            FaultType faultType,
            int magnitude,
            int durationSeconds,
            UUID dependencyTargetId,
            Double abortIfScoreBelow) {
    }

    /** What the caller gets back straight away, before the run finishes. */
    public record ExperimentAccepted(String runId, String status, String detail) {
    }

    /**
     * Validates and starts an experiment, returning as soon as it is recorded.
     *
     * <p>The safety check happens on the calling thread so a refusal is immediate and
     * carries its reason; only an approved experiment is handed to the executor.
     */
    public ExperimentAccepted start(ExperimentRequest request) {
        ExperimentStore.ExperimentTarget target = store.target(request.targetId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "no such deployment target: " + request.targetId()));

        ExperimentStore.ExperimentTarget faultTarget = resolveFaultTarget(request, target);

        WorkloadObservation observed = workloads.observe(
                faultTarget.kubeContext(), faultTarget.namespace(), faultTarget.workload());

        if (!observed.found()) {
            throw new IllegalArgumentException("workload " + faultTarget.namespace() + "/"
                    + faultTarget.workload() + " is not on the cluster: " + observed.detail());
        }

        Map<String, Object> faultSpec = describeFault(request, faultTarget, observed);
        PolicyLimits limits = controlPlane.limitsFor(target.clusterId());

        ExperimentSafety.Verdict verdict = ExperimentSafety.check(new ExperimentSafety.Request(
                request.faultType(), faultTarget.namespace(), observed.desiredReplicas(),
                request.magnitude(), request.durationSeconds(),
                store.runningExperiments(target.clusterId())), limits);

        if (!verdict.allowed()) {
            UUID rejectedId = store.recordRejected(target.serviceId(), target.targetId(),
                    faultSpec, verdict.reason());
            log.info("experiment refused for {}: {}", target.label(), verdict.reason());
            events.broadcast("experiment", Map.of(
                    "target", target.label(), "status", "REJECTED_BY_POLICY",
                    "reason", verdict.reason()));
            return new ExperimentAccepted(rejectedId.toString(), "REJECTED_BY_POLICY", verdict.reason());
        }

        double scoreBefore = evaluation.measureTarget(target.targetId()).orElse(observed.readyPct());
        UUID runId = store.open(target.serviceId(), target.targetId(), faultSpec, scoreBefore);

        log.info("experiment {} starting on {}: {} for {}s",
                runId, target.label(), request.faultType(), request.durationSeconds());
        events.broadcast("experiment", Map.of(
                "runId", runId.toString(), "target", target.label(),
                "fault", request.faultType().name(), "status", "RUNNING",
                "scoreBefore", scoreBefore));

        runner.submit(() -> execute(runId, request, target, faultTarget, faultSpec, observed));

        return new ExperimentAccepted(runId.toString(), "RUNNING", verdict.reason());
    }

    /** Injects, observes, and — whatever happens — restores. */
    private void execute(UUID runId, ExperimentRequest request,
                         ExperimentStore.ExperimentTarget target,
                         ExperimentStore.ExperimentTarget faultTarget,
                         Map<String, Object> faultSpec, WorkloadObservation before) {

        Map<String, Object> outcome = new LinkedHashMap<>(faultSpec);
        String status = "COMPLETED";
        Double scoreDuring = null;

        // For a dependency outage, what every other service scored before the fault
        // is the baseline the discovered edges rest on. It has to be taken before
        // anything is broken.
        Map<UUID, Double> baseline = request.faultType() == FaultType.DEPENDENCY_OUTAGE
                ? measureEveryOtherTarget(faultTarget.targetId())
                : Map.of();

        try {
            Injection injection = inject(request, faultTarget, before);
            outcome.put("injected", injection.description());

            Observation observation = observe(runId, target, request);

            if (request.faultType() == FaultType.DEPENDENCY_OUTAGE) {
                // Measured while the dependency is still down: this is the only
                // moment the platform can tell which services actually need it,
                // rather than which ones merely talk to it.
                outcome.put("dependenciesDiscovered",
                        discoverDependencies(faultTarget, baseline));
            }
            scoreDuring = observation.worstScore();
            outcome.put("observedMinimumScore", observation.worstScore());
            outcome.put("hypothesisHeld", !observation.aborted());

            if (observation.aborted()) {
                status = "ABORTED";
                outcome.put("abortedBecause", observation.reason());
                log.warn("experiment {} aborted: {}", runId, observation.reason());
            }

            String restored = restore(injection, faultTarget);
            outcome.put("restored", restored);

        } catch (Exception e) {
            status = "FAILED";
            outcome.put("error", e.getMessage());
            log.warn("experiment {} failed: {}", runId, e.getMessage(), e);

            // A failure during injection or observation still leaves whatever was
            // already done to the cluster in place, so restoration is attempted
            // regardless of how we got here.
            try {
                outcome.put("restored", restore(
                        new Injection(request.faultType(), faultTarget, before.desiredReplicas(),
                                "recovery after failure"), faultTarget));
            } catch (Exception restoreFailure) {
                outcome.put("restored", "FAILED: " + restoreFailure.getMessage());
                log.error("experiment {} could not restore {}: {}", runId,
                        faultTarget.label(), restoreFailure.getMessage());
            }
        }

        Double scoreAfter = evaluation.measureTarget(target.targetId()).stream().boxed()
                .findFirst().orElse(null);
        outcome.put("recoveryVerifiedAt", Instant.now().toString());

        store.close(runId, status, scoreAfter, outcome);

        log.info("experiment {} {}: score {} -> {} -> {}", runId, status,
                faultSpec.get("scoreBefore"), scoreDuring, scoreAfter);

        events.broadcast("experiment", Map.of(
                "runId", runId.toString(), "target", target.label(), "status", status,
                "scoreDuring", scoreDuring == null ? -1 : scoreDuring,
                "scoreAfter", scoreAfter == null ? -1 : scoreAfter,
                "restored", String.valueOf(outcome.get("restored"))));
    }

    /**
     * Scores every other target that has endpoints, so a dependency outage can be
     * compared against a real baseline rather than against an assumption.
     */
    private Map<UUID, Double> measureEveryOtherTarget(UUID excludedTargetId) {
        Map<UUID, Double> scores = new LinkedHashMap<>();

        evaluationStore.activeEndpoints().stream()
                .map(EvaluationStore.ProbeEndpoint::targetId)
                .distinct()
                .filter(id -> !id.equals(excludedTargetId))
                .forEach(id -> evaluation.measureTarget(id)
                        .ifPresent(score -> scores.put(id, score)));

        return scores;
    }

    /**
     * Records the edges this outage demonstrated.
     *
     * <p>A service that degraded while the dependency was down depends on it. A
     * service that carried on did not — and recording nothing for that case is the
     * point: an absent edge is honest, while an invented one produces a blast radius
     * that looks authoritative and is wrong.
     */
    private List<String> discoverDependencies(ExperimentStore.ExperimentTarget faultTarget,
                                              Map<UUID, Double> baseline) {
        if (baseline.isEmpty()) {
            return List.of("no other target has endpoints registered, so nothing could be measured");
        }

        List<DependencyDiscovery.ObservedImpact> impacts = new java.util.ArrayList<>();

        for (Map.Entry<UUID, Double> entry : baseline.entrySet()) {
            UUID targetId = entry.getKey();
            OptionalDouble during = evaluation.measureTarget(targetId);
            if (during.isEmpty()) {
                continue;
            }

            UUID serviceId = discovery.serviceOf(targetId).orElse(null);
            if (serviceId == null) {
                continue;
            }

            store.target(targetId).ifPresent(t -> impacts.add(new DependencyDiscovery.ObservedImpact(
                    serviceId, t.serviceName(), entry.getValue(), during.getAsDouble())));
        }

        return discovery.recordFromOutage(faultTarget.serviceId(), impacts).findings();
    }

    /** What was done, and what it takes to undo it. */
    private record Injection(FaultType faultType, ExperimentStore.ExperimentTarget target,
                             int originalReplicas, String description) {
    }

    private Injection inject(ExperimentRequest request, ExperimentStore.ExperimentTarget faultTarget,
                             WorkloadObservation before) {
        return switch (request.faultType()) {
            case POD_KILL -> killPods(request, faultTarget, before);
            case REPLICA_LOSS -> {
                int reduced = before.desiredReplicas() - request.magnitude();
                fail(workloads.scale(faultTarget.kubeContext(), faultTarget.namespace(),
                        faultTarget.workload(), reduced), "scale down");
                yield new Injection(request.faultType(), faultTarget, before.desiredReplicas(),
                        "replicas " + before.desiredReplicas() + " -> " + reduced);
            }
            case DEPENDENCY_OUTAGE -> {
                fail(workloads.scale(faultTarget.kubeContext(), faultTarget.namespace(),
                        faultTarget.workload(), 0), "scale dependency to zero");
                yield new Injection(request.faultType(), faultTarget, before.desiredReplicas(),
                        faultTarget.label() + " scaled to 0 (was " + before.desiredReplicas() + ")");
            }
        };
    }

    private Injection killPods(ExperimentRequest request, ExperimentStore.ExperimentTarget faultTarget,
                               WorkloadObservation before) {
        List<String> killed = before.pods().stream()
                .filter(PodObservation::ready)
                .limit(request.magnitude())
                .map(PodObservation::name)
                .toList();

        for (String pod : killed) {
            fail(workloads.deletePod(faultTarget.kubeContext(), faultTarget.namespace(), pod),
                    "delete pod " + pod);
        }

        // Nothing to undo: the ReplicaSet replaces a deleted pod by itself, and
        // that replacement is exactly what the experiment is testing.
        return new Injection(request.faultType(), faultTarget, before.desiredReplicas(),
                "deleted " + killed.size() + " pod(s): " + String.join(", ", killed));
    }

    private String restore(Injection injection, ExperimentStore.ExperimentTarget faultTarget) {
        if (injection.faultType() == FaultType.POD_KILL) {
            return "nothing to restore: the ReplicaSet recreates deleted pods";
        }

        Optional<String> failure = workloads.scale(faultTarget.kubeContext(), faultTarget.namespace(),
                faultTarget.workload(), injection.originalReplicas());

        if (failure.isPresent()) {
            return "FAILED to restore " + injection.originalReplicas() + " replicas: " + failure.get();
        }
        return "restored to " + injection.originalReplicas() + " replicas";
    }

    /** The steady-state check while the fault is in place. */
    private record Observation(double worstScore, boolean aborted, String reason) {
    }

    private Observation observe(UUID runId, ExperimentStore.ExperimentTarget target,
                                ExperimentRequest request) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(request.durationSeconds());
        double worst = 100;

        while (Instant.now().isBefore(deadline)) {
            OptionalDouble score = evaluation.measureTarget(target.targetId());
            if (score.isPresent()) {
                worst = Math.min(worst, score.getAsDouble());
                store.recordDuring(runId, worst);

                if (request.abortIfScoreBelow() != null
                        && score.getAsDouble() < request.abortIfScoreBelow()) {
                    return new Observation(worst, true, String.format(
                            "steady-state hypothesis broken: score %.1f fell below the %.1f abort "
                                    + "threshold", score.getAsDouble(), request.abortIfScoreBelow()));
                }
            }

            Thread.sleep(OBSERVATION_INTERVAL.toMillis());
        }

        return new Observation(worst, false, "ran to completion");
    }

    /**
     * The fault, written down before it happens.
     *
     * <p>This map is the ground truth for Phase 8: it names the service that was
     * broken and how, so an RCA verdict can be compared against the answer rather
     * than merely inspected for plausibility.
     */
    private Map<String, Object> describeFault(ExperimentRequest request,
                                              ExperimentStore.ExperimentTarget faultTarget,
                                              WorkloadObservation observed) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("type", request.faultType().name());
        spec.put("magnitude", request.magnitude());
        spec.put("durationSeconds", request.durationSeconds());
        spec.put("faultTarget", faultTarget.label());
        spec.put("faultTargetId", faultTarget.targetId().toString());
        spec.put("workload", faultTarget.namespace() + "/" + faultTarget.workload());
        spec.put("replicasBefore", observed.desiredReplicas());
        if (request.abortIfScoreBelow() != null) {
            spec.put("abortIfScoreBelow", request.abortIfScoreBelow());
        }
        return spec;
    }

    private ExperimentStore.ExperimentTarget resolveFaultTarget(
            ExperimentRequest request, ExperimentStore.ExperimentTarget target) {

        if (request.faultType() != FaultType.DEPENDENCY_OUTAGE) {
            return target;
        }

        if (request.dependencyTargetId() == null) {
            throw new IllegalArgumentException(
                    "DEPENDENCY_OUTAGE needs dependencyTargetId: the service to take down");
        }
        if (request.dependencyTargetId().equals(request.targetId())) {
            // Taking down the observed service itself would measure nothing about
            // propagation, which is the only reason this fault type exists.
            throw new IllegalArgumentException(
                    "the dependency must be a different target from the one under observation");
        }

        return store.target(request.dependencyTargetId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "no such dependency target: " + request.dependencyTargetId()));
    }

    private static void fail(Optional<String> failure, String what) {
        if (failure.isPresent()) {
            throw new IllegalStateException(what + " failed: " + failure.get());
        }
    }

    /**
     * Waits for in-flight experiments on shutdown.
     *
     * <p>Killing the process mid-experiment leaves a service scaled down with nothing
     * left running that intends to scale it back up. The wait is bounded by the
     * safety cap on experiment duration.
     */
    @PreDestroy
    void shutdown() {
        runner.shutdown();
        try {
            if (!runner.awaitTermination(60, TimeUnit.SECONDS)) {
                log.error("an experiment was still running at shutdown; "
                        + "check evaluation_run for RUNNING chaos rows and restore by hand");
                runner.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
