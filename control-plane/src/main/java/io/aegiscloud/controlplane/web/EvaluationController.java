package io.aegiscloud.controlplane.web;

import io.aegiscloud.controlplane.domain.Models;
import io.aegiscloud.controlplane.eval.EvaluationEngine;
import io.aegiscloud.controlplane.eval.EvaluationStore;
import io.aegiscloud.controlplane.eval.Prober;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The Evaluation Engine's HTTP surface: register what to measure, run a measurement
 * now, and read the results.
 *
 * <p>Registering endpoints and SLOs is OPERATOR+ because it defines what the platform
 * considers healthy — an SLO quietly loosened is an outage that stops being reported.
 */
@RestController
@RequestMapping("/api/v1")
public class EvaluationController {

    private final EvaluationEngine engine;
    private final EvaluationStore store;

    public EvaluationController(EvaluationEngine engine, EvaluationStore store) {
        this.engine = engine;
        this.store = store;
    }

    /**
     * @param address either an ordinary URL, or {@code k8s://namespace/service:port/path}
     *                to probe a cluster-internal service through the Kubernetes API
     *                server — the only way to reach a ClusterIP service with no ingress
     */
    public record RegisterEndpointRequest(
            @NotBlank String protocol,
            @NotBlank String address,
            @Min(5) int probeIntervalSeconds,
            @Positive int timeoutMs,
            Integer expectedStatusCode) {
    }

    @PostMapping("/targets/{targetId}/endpoints")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public EvaluationStore.ProbeEndpoint registerEndpoint(
            @PathVariable String targetId, @Valid @RequestBody RegisterEndpointRequest request) {

        UUID id = uuid(targetId, "deployment target");
        String protocol = request.protocol().toUpperCase(Locale.ROOT);

        if (!List.of("HTTP", "HTTPS", "TCP", "GRPC").contains(protocol)) {
            throw ApiException.badRequest("unknown protocol: " + request.protocol()
                    + "; expected HTTP, HTTPS, TCP or GRPC");
        }

        // Validating the cluster address here rather than at probe time means a
        // typo is rejected by the call that made it, instead of surfacing an hour
        // later as a mysteriously failing probe.
        if (request.address().startsWith(Prober.CLUSTER_SCHEME)) {
            try {
                Prober.ClusterAddress.parse(request.address());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest(e.getMessage());
            }
        }

        UUID endpointId = store.addEndpoint(id, protocol, request.address(),
                request.probeIntervalSeconds(), request.timeoutMs(), request.expectedStatusCode());

        return store.endpointsFor(id).stream()
                .filter(e -> e.endpointId().equals(endpointId))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("endpoint " + endpointId + " not found"));
    }

    @GetMapping("/targets/{targetId}/endpoints")
    public List<EvaluationStore.ProbeEndpoint> endpoints(@PathVariable String targetId) {
        return store.endpointsFor(uuid(targetId, "deployment target"));
    }

    public record RegisterSloRequest(
            @NotBlank String sliType,
            double objectiveValue,
            @Min(1) int windowDays) {
    }

    @PostMapping("/targets/{targetId}/slos")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public EvaluationStore.TargetSlo registerSlo(@PathVariable String targetId,
                                                 @Valid @RequestBody RegisterSloRequest request) {
        UUID id = uuid(targetId, "deployment target");

        Models.SliType sliType;
        try {
            sliType = Models.SliType.valueOf(request.sliType().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("unknown SLI type: " + request.sliType());
        }

        UUID sloId = store.addSlo(id, sliType, request.objectiveValue(), request.windowDays());

        return store.activeSlos().stream()
                .filter(s -> s.sloId().equals(sloId))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("slo " + sloId + " not found"));
    }

    /**
     * Runs a full evaluation pass now and returns everything it measured.
     *
     * <p>The same method the scheduler calls, for the same reason the control loop
     * exposes its own cycle: an operator checking the platform should see what it
     * actually does, not a separate path that resembles it.
     */
    @PostMapping("/evaluations/run")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public EvaluationEngine.EvaluationReport run() {
        return engine.evaluate();
    }

    /** Reliability score over time for one target — the trend FR-21 asks for. */
    @GetMapping("/targets/{targetId}/scores")
    public List<EvaluationStore.ScorePoint> scores(@PathVariable String targetId,
                                                   @RequestParam(defaultValue = "50") int limit) {
        return store.scoreHistory(uuid(targetId, "deployment target"),
                Math.min(Math.max(limit, 1), 500));
    }

    private static UUID uuid(String raw, String what) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound(what + " " + raw + " not found");
        }
    }
}
