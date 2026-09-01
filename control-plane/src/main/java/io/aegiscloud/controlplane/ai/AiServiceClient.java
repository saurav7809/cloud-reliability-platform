package io.aegiscloud.controlplane.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The control plane's link to the Python AI service.
 *
 * <p>Optional by design, exactly like the Redis cache. A reliability platform that
 * cannot report on reliability because its analytics sidecar is down has failed at
 * the one job it has, so every call here returns an empty result and a reason rather
 * than propagating a failure. The endpoints that use it say plainly when the service
 * was unreachable instead of quietly returning nothing.
 *
 * <p>Nothing here sends the AI service anything but numbers: a list of metric values
 * and, for re-ranking, candidate names and signal counts the platform already
 * computed. It receives no credentials, no kubeconfig and no cluster access, which is
 * what keeps "the Intelligence Layer never writes to a cluster" true of the whole
 * layer rather than only of the Java half.
 */
@Component
public class AiServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final Duration timeout;

    public AiServiceClient(ObjectMapper mapper,
                           @Value("${aegiscloud.ai.base-url:http://localhost:8000}") String baseUrl,
                           @Value("${aegiscloud.ai.timeout-ms:3000}") long timeoutMs) {
        this.mapper = mapper;
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.timeout = Duration.ofMillis(timeoutMs);
        // HTTP/1.1 explicitly. Java's client defaults to HTTP/2 and opens with an
        // h2c upgrade request, which uvicorn does not implement - it logs
        // "Unsupported upgrade request" and the mangled request comes back 422,
        // which reads exactly like a validation failure in a perfectly valid body.
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    /** Whether the service answered, and what it says it can do. */
    public Optional<JsonNode> health() {
        return get("/health");
    }

    public boolean available() {
        return health().isPresent();
    }

    /** Anomalies in a metric series. Empty when the service is unreachable. */
    public Optional<JsonNode> anomalies(List<Double> values, double threshold) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("values", values);
        body.put("threshold", threshold);
        return post("/anomaly", body);
    }

    /** A forecast, and when the series crosses the threshold. */
    public Optional<JsonNode> forecast(List<Double> values, int horizon,
                                       Double threshold, String direction) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("values", values);
        body.put("horizon", horizon);
        body.put("threshold", threshold);
        body.put("direction", direction);
        return post("/forecast", body);
    }

    /** Re-ranks RCA candidates using how unusual each service's telemetry is. */
    public Optional<JsonNode> rerank(List<Map<String, Object>> candidates) {
        return post("/rca/rerank", Map.of("candidates", candidates));
    }

    private Optional<JsonNode> get(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(timeout)
                    .GET()
                    .build();
            return send(request);
        } catch (Exception e) {
            return unavailable(path, e);
        }
    }

    private Optional<JsonNode> post(String path, Object body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            return send(request);
        } catch (Exception e) {
            return unavailable(path, e);
        }
    }

    private Optional<JsonNode> send(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.debug("ai service returned {} for {}", response.statusCode(), request.uri());
            return Optional.empty();
        }
        return Optional.of(mapper.readTree(response.body()));
    }

    private Optional<JsonNode> unavailable(String path, Exception e) {
        // Debug rather than warn: with the service deliberately optional, a missing
        // one is a supported configuration, and logging it as a warning every minute
        // would train operators to ignore warnings.
        log.debug("ai service unavailable for {}: {}", path, e.getMessage());
        return Optional.empty();
    }
}
