package io.aegiscloud.controlplane.web;

import io.aegiscloud.controlplane.alerting.AlertingService;
import io.aegiscloud.controlplane.audit.AuditLog;
import io.aegiscloud.controlplane.auth.Tenant;
import io.aegiscloud.controlplane.eval.MetricIngestion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
import java.util.Map;
import java.util.UUID;

/**
 * Metric ingestion, alerting and the audit trail — the last three requirements the
 * platform stated and had not met.
 */
@RestController
@RequestMapping("/api/v1")
public class IngestionController {

    private final MetricIngestion ingestion;
    private final AlertingService alerting;
    private final AuditLog audit;

    public IngestionController(MetricIngestion ingestion, AlertingService alerting, AuditLog audit) {
        this.ingestion = ingestion;
        this.alerting = alerting;
        this.audit = audit;
    }

    /**
     * @param value boxed rather than primitive on purpose. A primitive double turns
     *              a JSON null into 0.0, so a collector sending a null latency would
     *              have a 0ms reading stored - a number that looks like a
     *              measurement, drags every percentile down, and is indistinguishable
     *              afterwards from a genuinely fast response.
     */
    public record SampleRequest(@NotBlank String metricType, Double value, Boolean success) {
    }

    public record PushRequest(
            @NotBlank String source,
            @NotEmpty List<SampleRequest> samples) {
    }

    /**
     * Accepts measurements from outside the platform (FR-15, push half).
     *
     * <p>OPERATOR+ because ingested metrics drive SLO evaluation, scoring, alerting
     * and autonomous scaling. Anyone who can write a metric can make the platform act.
     */
    @PostMapping("/targets/{targetId}/metrics")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public MetricIngestion.IngestResult push(@PathVariable String targetId,
                                             @Valid @RequestBody PushRequest request) {
        String source = request.source().toUpperCase(Locale.ROOT);
        if (!List.of("OTEL", "PUSHED", "PROMETHEUS").contains(source)) {
            throw ApiException.badRequest("source must be OTEL, PUSHED or PROMETHEUS");
        }

        return ingestion.push(uuid(targetId), source,
                request.samples().stream()
                        .map(s -> new MetricIngestion.PushedSample(
                                s.metricType(), s.value(), s.success()))
                        .toList());
    }

    public record PullRequest(@NotBlank String query, @NotBlank String metricType) {
    }

    /** Pulls a PromQL query into the platform's own series (FR-15, pull half). */
    @PostMapping("/targets/{targetId}/metrics/pull")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public MetricIngestion.PrometheusResult pull(@PathVariable String targetId,
                                                 @Valid @RequestBody PullRequest request) {
        return ingestion.pull(uuid(targetId), request.query(), request.metricType());
    }

    @GetMapping("/ingestion/status")
    public Map<String, Object> status() {
        return Map.of(
                "prometheusConfigured", ingestion.prometheusConfigured(),
                "pushAccepted", List.of("OTEL", "PUSHED", "PROMETHEUS"),
                "metricTypes", List.of("AVAILABILITY", "LATENCY_MS", "ERROR_RATE", "THROUGHPUT"));
    }

    /** Runs an alerting sweep now: raise, resolve and group (FR-39, FR-41). */
    @PostMapping("/alerts/sweep")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public AlertingService.AlertingReport sweep() {
        return alerting.sweep();
    }

    /** The alerts grouped under one incident — the noise an incident absorbs (FR-41). */
    @GetMapping("/incidents/{incidentId}/alerts")
    public List<Map<String, Object>> incidentAlerts(@PathVariable String incidentId) {
        return alerting.alertsForIncident(uuid(incidentId));
    }

    /**
     * The audit trail (FR-42, FR-43).
     *
     * <p>ADMIN only. The trail describes every action anyone in the organisation has
     * taken, which makes it the most revealing read the API offers.
     */
    @GetMapping("/audit")
    @PreAuthorize("hasRole('ADMIN')")
    public List<AuditLog.AuditEntry> auditTrail(@RequestParam(defaultValue = "100") int limit) {
        return audit.entries(Tenant.currentOrgId(), Math.min(Math.max(limit, 1), 1000));
    }

    private static UUID uuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("not a valid id: " + raw);
        }
    }
}
