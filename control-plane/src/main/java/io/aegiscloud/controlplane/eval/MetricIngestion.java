package io.aegiscloud.controlplane.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aegiscloud.controlplane.audit.AuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Metric ingestion from outside the platform's own probes (FR-15).
 *
 * <p>Two routes, because the two halves of a fleet report differently. A workload
 * that already exposes Prometheus metrics is <em>pulled</em> from, by querying a
 * Prometheus that scrapes it. Anything else — a batch job, a service behind a network
 * the platform cannot reach, an OpenTelemetry collector — <em>pushes</em> to the
 * platform instead.
 *
 * <p>Both land in the same {@code metric_sample} table the probes write to, tagged by
 * source, so every consumer downstream (SLO evaluation, scoring, the AI service,
 * RCA) works on pushed and probed data identically. Adding a parallel table for
 * "external" metrics would have meant every one of those consumers growing a second
 * code path, and the second path is always the one that rots.
 */
@Service
public class MetricIngestion {

    private static final Logger log = LoggerFactory.getLogger(MetricIngestion.class);

    /** The metric types the schema permits; anything else is rejected at the door. */
    private static final List<String> METRIC_TYPES =
            List.of("AVAILABILITY", "LATENCY_MS", "ERROR_RATE", "THROUGHPUT");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AuditLog audit;
    private final HttpClient http;
    private final String prometheusUrl;

    public MetricIngestion(JdbcTemplate jdbc, ObjectMapper mapper, AuditLog audit,
                           @Value("${aegiscloud.prometheus.url:}") String prometheusUrl) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.audit = audit;
        this.prometheusUrl = prometheusUrl == null ? "" : prometheusUrl.replaceAll("/$", "");
        this.http = HttpClient.newBuilder()
                // Same reason as the AI client: uvicorn and several Prometheus
                // deployments sit behind proxies that do not implement h2c upgrade.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    // ------------------------------------------------------------------- push

    /** One measurement arriving from outside. */
    /** @param value absent when the caller sent no value; never coerced to zero. */
    public record PushedSample(String metricType, Double value, Boolean success) {
    }

    public record IngestResult(int accepted, int rejected, List<String> detail) {
    }

    /**
     * Accepts pushed measurements for a target.
     *
     * <p>Rejections are per-sample rather than per-request. A collector sending a
     * thousand samples with three malformed ones should have nine hundred and
     * ninety-seven stored and be told about the three, not have the batch refused —
     * the alternative is a monitoring pipeline that silently stops when one metric
     * name changes.
     */
    public IngestResult push(UUID targetId, String source, List<PushedSample> samples) {
        List<String> detail = new ArrayList<>();
        int accepted = 0;

        for (PushedSample sample : samples) {
            String type = sample.metricType() == null
                    ? "" : sample.metricType().toUpperCase(java.util.Locale.ROOT);

            if (!METRIC_TYPES.contains(type)) {
                detail.add("rejected unknown metric type: " + sample.metricType());
                continue;
            }
            if (sample.value() == null) {
                detail.add("rejected " + type + " with no value");
                continue;
            }
            if (!Double.isFinite(sample.value())) {
                // NaN and infinity survive JSON in some clients and poison every
                // percentile and average computed downstream.
                detail.add("rejected non-finite value for " + type);
                continue;
            }

            jdbc.update("""
                    INSERT INTO metric_sample (target_id, source, metric_type, value, success)
                    VALUES (?, ?, ?, ?, ?)
                    """, targetId, source, type, sample.value(), sample.success());
            accepted++;
        }

        audit.recordUserAction("INGEST_METRICS", "deployment_target", targetId.toString(),
                Map.of("source", source, "accepted", accepted,
                        "rejected", samples.size() - accepted));

        return new IngestResult(accepted, samples.size() - accepted, detail);
    }

    // ------------------------------------------------------------------- pull

    public record PrometheusResult(boolean reachable, int samplesStored, String detail) {
    }

    public boolean prometheusConfigured() {
        return !prometheusUrl.isBlank();
    }

    /**
     * Pulls one instant query from Prometheus and stores the result for a target.
     *
     * <p>An instant query rather than a range: the platform samples on its own
     * schedule and stores what it read, which keeps pulled data the same shape as
     * probed data. Back-filling a range would produce samples with timestamps the
     * evaluation windows never expected and quietly change what every SLO means.
     *
     * @param query a PromQL expression that must evaluate to a single scalar or
     *              one-element vector; anything else is reported rather than guessed at
     */
    public PrometheusResult pull(UUID targetId, String query, String metricType) {
        if (!prometheusConfigured()) {
            return new PrometheusResult(false, 0,
                    "no Prometheus configured; set aegiscloud.prometheus.url");
        }

        String type = metricType.toUpperCase(java.util.Locale.ROOT);
        if (!METRIC_TYPES.contains(type)) {
            return new PrometheusResult(true, 0, "unknown metric type: " + metricType);
        }

        try {
            String url = prometheusUrl + "/api/v1/query?query="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8);

            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder().uri(URI.create(url))
                            .timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return new PrometheusResult(false, 0,
                        "Prometheus returned HTTP " + response.statusCode());
            }

            Optional<Double> value = firstValue(mapper.readTree(response.body()));
            if (value.isEmpty()) {
                return new PrometheusResult(true, 0,
                        "query returned no samples: " + query);
            }

            jdbc.update("""
                    INSERT INTO metric_sample (target_id, source, metric_type, value, success)
                    VALUES (?, 'PROMETHEUS', ?, ?, true)
                    """, targetId, type, value.get());

            return new PrometheusResult(true, 1, String.format(
                    "stored %s = %.4f from Prometheus", type, value.get()));

        } catch (Exception e) {
            log.debug("Prometheus pull failed: {}", e.getMessage());
            return new PrometheusResult(false, 0, "Prometheus unreachable: " + e.getMessage());
        }
    }

    /**
     * Extracts the single value from a Prometheus query response.
     *
     * <p>Handles both the scalar and vector result types and deliberately refuses a
     * multi-element vector: a query matching three series has no single answer, and
     * storing the first one would attribute one pod's number to a whole target.
     */
    static Optional<Double> firstValue(JsonNode body) {
        JsonNode data = body.path("data");
        String resultType = data.path("resultType").asText("");

        if ("scalar".equals(resultType)) {
            return parse(data.path("result").path(1).asText(null));
        }

        if ("vector".equals(resultType)) {
            JsonNode result = data.path("result");
            if (result.size() != 1) {
                return Optional.empty();
            }
            return parse(result.path(0).path("value").path(1).asText(null));
        }

        return Optional.empty();
    }

    private static Optional<Double> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            double value = Double.parseDouble(raw);
            return Double.isFinite(value) ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
