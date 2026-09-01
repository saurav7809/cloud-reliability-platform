package io.aegiscloud.controlplane.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.aegiscloud.controlplane.ai.AiServiceClient;
import io.aegiscloud.controlplane.auth.Tenant;
import io.aegiscloud.controlplane.eval.EvaluationStore;
import io.aegiscloud.controlplane.eval.SloEvaluator;
import io.aegiscloud.controlplane.rca.RcaStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Analytics over a target's real telemetry, computed by the Python AI service.
 *
 * <p>The control plane supplies the data and the context; the AI service supplies the
 * statistics. Keeping the split that way means the series being analysed is always one
 * the platform actually measured — the AI service cannot invent history it was never
 * given, because it has no database to invent it from.
 *
 * <p>Every endpoint here answers with an explicit "the AI service is not reachable"
 * rather than an empty result when the sidecar is down. An empty anomaly list and an
 * absent analyser look identical to a caller, and the difference is the whole
 * question of whether the service is healthy.
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

    /** How much recent telemetry is analysed. Long enough to hold a baseline. */
    private static final int WINDOW_MINUTES = 120;

    private final AiServiceClient ai;
    private final EvaluationStore evaluation;
    private final RcaStore rca;

    public AiController(AiServiceClient ai, EvaluationStore evaluation, RcaStore rca) {
        this.ai = ai;
        this.evaluation = evaluation;
        this.rca = rca;
    }

    /** Whether the AI service is reachable, and which methods it reports using. */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return ai.health()
                .map(node -> Map.of("reachable", true, "service", (Object) node))
                .orElse(Map.of("reachable", false,
                        "detail", "the AI service is not reachable; the platform runs without it"));
    }

    /**
     * Anomalies in a target's recent latency.
     *
     * <p>Latency rather than availability, because availability is already a binary
     * the SLO evaluator handles well. Latency is where a distribution exists and
     * where a threshold comparison misses things — a service that has quietly moved
     * from 20ms to 90ms is nowhere near its 250ms objective and has changed
     * character completely.
     */
    @GetMapping("/targets/{targetId}/anomalies")
    public Map<String, Object> anomalies(@PathVariable String targetId,
                                         @RequestParam(defaultValue = "3.5") double threshold) {
        UUID id = ownedTarget(targetId);
        List<Double> series = latencySeries(id);

        if (series.isEmpty()) {
            return Map.of("analysed", false,
                    "detail", "no latency samples for this target in the last "
                            + WINDOW_MINUTES + " minutes");
        }

        return ai.anomalies(series, threshold)
                .map(node -> withSeries(node, series))
                .orElse(Map.of("analysed", false,
                        "detail", "the AI service is not reachable",
                        "samples", series.size()));
    }

    /**
     * Where a target's latency is heading, and when it crosses its objective.
     *
     * <p>The threshold is the target's own latency SLO rather than a number supplied
     * by the caller: a breach forecast against an invented line is a guess dressed as
     * a warning. Without an SLO the forecast is still returned, with no breach
     * estimate and a note saying why.
     */
    @GetMapping("/targets/{targetId}/forecast")
    public Map<String, Object> forecast(@PathVariable String targetId,
                                        @RequestParam(defaultValue = "12") int horizon) {
        UUID id = ownedTarget(targetId);
        List<Double> series = latencySeries(id);

        if (series.isEmpty()) {
            return Map.of("analysed", false,
                    "detail", "no latency samples for this target in the last "
                            + WINDOW_MINUTES + " minutes");
        }

        Double objective = evaluation.activeSlos().stream()
                .filter(slo -> slo.targetId().equals(id))
                .filter(slo -> slo.sliType().name().startsWith("LATENCY"))
                .map(EvaluationStore.TargetSlo::objectiveValue)
                .findFirst()
                .orElse(null);

        return ai.forecast(series, horizon, objective, "above")
                .map(node -> {
                    Map<String, Object> payload = withSeries(node, series);
                    payload.put("objectiveMs", objective);
                    if (objective == null) {
                        payload.put("note", "this target has no latency SLO, so no breach "
                                + "estimate is possible");
                    }
                    return payload;
                })
                .orElse(Map.of("analysed", false,
                        "detail", "the AI service is not reachable",
                        "samples", series.size()));
    }

    /**
     * The AI service's view of an incident's candidates, alongside the platform's.
     *
     * <p>Returned as a second opinion rather than as a replacement: the platform's
     * own ranking stays in the response so the two can be compared. Where they
     * disagree, that disagreement is the interesting part, and hiding it behind a
     * single merged number would throw away the only signal that says the analysis
     * is uncertain.
     */
    @GetMapping("/incidents/{incidentId}/rerank")
    public Map<String, Object> rerank(@PathVariable String incidentId) {
        UUID orgId = Tenant.currentOrgId();
        UUID id = uuid(incidentId);

        rca.incident(orgId, id).orElseThrow(() ->
                ApiException.notFound("incident " + incidentId + " not found"));

        List<RcaStore.VerdictRow> verdicts = rca.verdicts(id);
        if (verdicts.isEmpty()) {
            return Map.of("analysed", false, "detail", "this incident has no verdicts to re-rank");
        }

        List<Map<String, Object>> candidates = verdicts.stream().map(verdict -> {
            List<Double> series = latencySeries(UUID.fromString(verdict.targetId()));

            Double anomalyScore = ai.anomalies(series, 3.5)
                    .filter(node -> node.path("found").asBoolean(false))
                    .map(node -> node.path("anomalies").path(0).path("score").asDouble(0))
                    .filter(score -> score > 0)
                    .orElse(null);

            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("service_id", verdict.targetId());
            candidate.put("service_name", verdict.serviceName());
            candidate.put("confidence", verdict.confidence());
            candidate.put("downstream_of",
                    verdict.reasoning().contains("downstream of") ? 1 : 0);
            candidate.put("upstream_of",
                    verdict.reasoning().contains("is upstream of") ? 1 : 0);
            candidate.put("anomaly_score", anomalyScore);
            return candidate;
        }).toList();

        return ai.rerank(candidates)
                .map(node -> Map.of(
                        "analysed", true,
                        "platformRanking", verdicts.stream()
                                .map(v -> Map.of("rank", v.rank(), "service", v.serviceName(),
                                        "confidence", v.confidence()))
                                .toList(),
                        "aiRanking", (Object) node.path("ranked")))
                .orElse(Map.of("analysed", false, "detail", "the AI service is not reachable"));
    }

    private Map<String, Object> withSeries(JsonNode node, List<Double> series) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("analysed", true);
        payload.put("windowMinutes", WINDOW_MINUTES);
        payload.put("samples", series.size());
        node.fields().forEachRemaining(entry -> payload.put(entry.getKey(), entry.getValue()));
        return payload;
    }

    private List<Double> latencySeries(UUID targetId) {
        return evaluation.samplesWithin(targetId, "LATENCY_MS", WINDOW_MINUTES).stream()
                .map(SloEvaluator.Sample::value)
                .toList();
    }

    /**
     * Resolves a target id the caller is allowed to see.
     *
     * <p>Analytics endpoints are as tenant-scoped as everything else: a series of
     * latency numbers is still another organisation's operational data.
     */
    private UUID ownedTarget(String targetId) {
        UUID id = uuid(targetId);
        boolean owned = evaluation.activeEndpoints().stream()
                .anyMatch(endpoint -> endpoint.targetId().equals(id));

        if (!owned && rca.degradedTargets(Tenant.currentOrgId(), 101).stream()
                .noneMatch(target -> target.targetId().equals(id))) {
            throw ApiException.notFound("deployment target " + targetId + " not found");
        }
        return id;
    }

    private static UUID uuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("not a valid id: " + raw);
        }
    }
}
