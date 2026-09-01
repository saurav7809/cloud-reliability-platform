package io.aegiscloud.controlplane.eval;

import io.aegiscloud.controlplane.domain.Models;
import io.aegiscloud.controlplane.engine.ControlPlaneEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * The Evaluation Engine (FR-11 to FR-16, FR-19 to FR-21).
 *
 * <p>One pass: probe every registered endpoint, record what came back, evaluate each
 * SLO against its window, compute a Reliability Score per target, and refresh the
 * readings the dashboard renders.
 *
 * <p>This is the phase that makes the platform's numbers mean something. Before it,
 * every score on the dashboard was a seeded fixture; after it, each one is derived
 * from probes that actually happened, and a target with no probes gets no score
 * rather than a flattering default.
 */
@Service
public class EvaluationEngine {

    private static final Logger log = LoggerFactory.getLogger(EvaluationEngine.class);

    private final EvaluationStore store;
    private final Prober prober;
    private final ControlPlaneEvents events;
    private final int scoreWindowMinutes;

    public EvaluationEngine(EvaluationStore store, Prober prober, ControlPlaneEvents events,
                            @Value("${aegiscloud.evaluation.score-window-minutes:60}")
                            int scoreWindowMinutes) {
        this.store = store;
        this.prober = prober;
        this.events = events;
        this.scoreWindowMinutes = scoreWindowMinutes;
    }

    /** What one evaluation pass measured. */
    public record EvaluationReport(
            Instant ranAt,
            int endpointsProbed,
            int probesSucceeded,
            int slosEvaluated,
            int scoresRecorded,
            List<String> observations) {
    }

    @Scheduled(
            initialDelayString = "${aegiscloud.evaluation.initial-delay-ms:15000}",
            fixedDelayString = "${aegiscloud.evaluation.interval-ms:60000}")
    public void scheduledEvaluation() {
        try {
            EvaluationReport report = evaluate();
            if (report.endpointsProbed() > 0) {
                log.info("evaluation: {} probes ({} ok), {} SLOs, {} scores",
                        report.endpointsProbed(), report.probesSucceeded(),
                        report.slosEvaluated(), report.scoresRecorded());
            }
        } catch (Exception e) {
            // Measurement failing must never take the platform down with it.
            log.warn("evaluation cycle failed: {}", e.getMessage(), e);
        }
    }

    /** Runs one full evaluation pass. */
    public EvaluationReport evaluate() {
        Instant startedAt = Instant.now();
        List<String> observations = new ArrayList<>();

        List<EvaluationStore.ProbeEndpoint> endpoints = store.activeEndpoints();
        int succeeded = probeAll(endpoints, observations);

        int slosEvaluated = evaluateSlos(observations);
        int scoresRecorded = scoreTargets(endpoints, startedAt, observations);

        EvaluationReport report = new EvaluationReport(startedAt, endpoints.size(), succeeded,
                slosEvaluated, scoresRecorded, observations);

        events.broadcast("evaluation", Map.of(
                "at", startedAt.toString(),
                "probed", endpoints.size(),
                "succeeded", succeeded,
                "slos", slosEvaluated,
                "scores", scoresRecorded));

        return report;
    }

    /**
     * Probes one target now and returns the score those measurements produce.
     *
     * <p>Exists for the Experiment Engine, which has to know what a target's
     * reliability was immediately before a fault, during it, and after recovery.
     * Reading the last scheduled score would blur those three moments together.
     */
    public OptionalDouble measureTarget(UUID targetId) {
        List<EvaluationStore.ProbeEndpoint> endpoints = store.activeEndpoints().stream()
                .filter(e -> e.targetId().equals(targetId))
                .toList();

        if (endpoints.isEmpty()) {
            return OptionalDouble.empty();
        }

        probeAll(endpoints, new ArrayList<>());
        return scoreFromRecentSamples(targetId);
    }

    /**
     * The score implied by the samples already collected for a target, without
     * probing again.
     */
    private OptionalDouble scoreFromRecentSamples(UUID targetId) {
        List<SloEvaluator.Sample> availability =
                store.samplesWithin(targetId, "AVAILABILITY", scoreWindowMinutes);
        if (availability.isEmpty()) {
            return OptionalDouble.empty();
        }

        List<SloEvaluator.Sample> latency =
                store.samplesWithin(targetId, "LATENCY_MS", scoreWindowMinutes);
        double availabilityPct = availability.stream().filter(SloEvaluator.Sample::success).count()
                * 100.0 / availability.size();

        ReliabilityScore.Result result = ReliabilityScore.compute(new ReliabilityScore.Inputs(
                OptionalDouble.of(availabilityPct),
                latency.isEmpty() ? OptionalDouble.empty()
                        : OptionalDouble.of(SloEvaluator.percentile(latency, 95)),
                latencyObjective(targetId),
                OptionalDouble.of(100 - availabilityPct)));

        return result.score();
    }

    // ------------------------------------------------------------------ probes

    private int probeAll(List<EvaluationStore.ProbeEndpoint> endpoints, List<String> observations) {
        int succeeded = 0;

        for (EvaluationStore.ProbeEndpoint endpoint : endpoints) {
            UUID serviceId = endpoint.serviceId();
            UUID runId = store.startRun(serviceId, endpoint.targetId(), "SCHEDULED_PROBE");

            Prober.ProbeResult result = prober.probe(new Prober.ProbeSpec(
                    endpoint.address(), endpoint.protocol(), endpoint.timeoutMs(),
                    endpoint.expectedStatusCode(), endpoint.kubeContext()));

            long sampleId = store.recordProbe(endpoint.targetId(), endpoint.endpointId(),
                    result.success(), result.latencyMs());

            // Linking the sample to the run is what lets an evaluation be audited
            // later: the verdict and the exact measurements behind it stay tied
            // together instead of both floating free in time.
            store.linkSample(runId, sampleId, "AFTER");
            store.finishRun(runId, result.success() ? "COMPLETED" : "FAILED", null);

            if (result.success()) {
                succeeded++;
            }

            observations.add(String.format("%s %s -> %s in %.1fms (%s)",
                    endpoint.label(), endpoint.address(),
                    result.success() ? "ok" : "FAILED", result.latencyMs(), result.detail()));

            events.broadcast("probe", Map.of(
                    "target", endpoint.label(),
                    "address", endpoint.address(),
                    "success", result.success(),
                    "latencyMs", result.latencyMs(),
                    "detail", result.detail()));
        }

        return succeeded;
    }

    // -------------------------------------------------------------------- SLOs

    private int evaluateSlos(List<String> observations) {
        int evaluated = 0;

        for (EvaluationStore.TargetSlo slo : store.activeSlos()) {
            List<SloEvaluator.Sample> samples = samplesFor(slo);

            SloEvaluator.Evaluation evaluation =
                    SloEvaluator.evaluate(slo.sliType(), slo.objectiveValue(), samples);

            if (evaluation.sampleCount() == 0) {
                // Recording a zero-sample verdict would put "0% availability, no
                // budget left" on the dashboard for a service nobody has measured
                // yet, which reads as an outage.
                observations.add(slo.sliType() + " SLO skipped: " + evaluation.detail());
                continue;
            }

            store.recordBudget(slo.sloId(), evaluation.currentValue(),
                    evaluation.budgetRemainingPct(), evaluation.burnRate());
            evaluated++;

            observations.add(String.format("%s SLO: %s, %.1f%% of budget left, burn %.2fx",
                    slo.sliType(), evaluation.detail(), evaluation.budgetRemainingPct(),
                    evaluation.burnRate()));
        }

        return evaluated;
    }

    /**
     * The samples an SLO is judged on.
     *
     * <p>Availability and error-rate SLOs read the availability series, because a
     * probe that did not succeed is both an availability failure and an error.
     * Latency SLOs read only successful probes, since the time a request took to fail
     * is not a latency measurement.
     */
    private List<SloEvaluator.Sample> samplesFor(EvaluationStore.TargetSlo slo) {
        String metricType = switch (slo.sliType()) {
            case LATENCY_P95, LATENCY_P99 -> "LATENCY_MS";
            case THROUGHPUT -> "THROUGHPUT";
            case AVAILABILITY, ERROR_RATE -> "AVAILABILITY";
        };
        return store.samples(slo.targetId(), metricType, slo.windowDays());
    }

    // ------------------------------------------------------------------ scores

    private int scoreTargets(List<EvaluationStore.ProbeEndpoint> endpoints, Instant now,
                             List<String> observations) {
        Map<UUID, String> targets = new LinkedHashMap<>();
        for (EvaluationStore.ProbeEndpoint endpoint : endpoints) {
            targets.putIfAbsent(endpoint.targetId(), endpoint.label());
        }

        Instant windowStart = now.minus(Duration.ofMinutes(scoreWindowMinutes));
        int recorded = 0;

        for (Map.Entry<UUID, String> entry : targets.entrySet()) {
            UUID targetId = entry.getKey();

            List<SloEvaluator.Sample> availability =
                    store.samplesWithin(targetId, "AVAILABILITY", scoreWindowMinutes);
            List<SloEvaluator.Sample> latency =
                    store.samplesWithin(targetId, "LATENCY_MS", scoreWindowMinutes);

            if (availability.isEmpty()) {
                continue;
            }

            double availabilityPct = availability.stream().filter(SloEvaluator.Sample::success).count()
                    * 100.0 / availability.size();
            double errorRatePct = 100 - availabilityPct;

            OptionalDouble p95 = latency.isEmpty()
                    ? OptionalDouble.empty()
                    : OptionalDouble.of(SloEvaluator.percentile(latency, 95));

            OptionalDouble objective = latencyObjective(targetId);

            ReliabilityScore.Result result = ReliabilityScore.compute(new ReliabilityScore.Inputs(
                    OptionalDouble.of(availabilityPct), p95, objective,
                    OptionalDouble.of(errorRatePct)));

            if (result.score().isEmpty()) {
                continue;
            }

            double score = result.score().getAsDouble();
            store.recordScore(targetId, windowStart, now, score);
            store.refreshTargetReadings(targetId, score, availabilityPct,
                    p95.orElse(0), errorRatePct);
            recorded++;

            observations.add(String.format("%s scored %.1f %s", entry.getValue(), score,
                    result.components()));
        }

        return recorded;
    }

    private OptionalDouble latencyObjective(UUID targetId) {
        return store.activeSlos().stream()
                .filter(s -> s.targetId().equals(targetId))
                .filter(s -> s.sliType() == Models.SliType.LATENCY_P95
                        || s.sliType() == Models.SliType.LATENCY_P99)
                .mapToDouble(EvaluationStore.TargetSlo::objectiveValue)
                .findFirst();
    }
}
