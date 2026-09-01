package io.aegiscloud.controlplane.eval;

import io.aegiscloud.controlplane.k8s.KubernetesClientFactory;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.http.HttpRequest;
import io.fabric8.kubernetes.client.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Synthetic probes against registered endpoints (FR-14).
 *
 * <p>Two addressing schemes, because a reliability platform has to reach services
 * that were never meant to be reachable from outside the cluster:
 *
 * <ul>
 *   <li>{@code https://host/path} — an ordinary HTTP request, for anything with a
 *       public address.
 *   <li>{@code k8s://namespace/service:port/path} — routed through the Kubernetes API
 *       server's service proxy. This is how a ClusterIP service with no ingress gets
 *       probed at all, and it needs no port-forward, no NodePort, and no change to
 *       the workload. It also keeps the cloud-agnostic boundary intact: the request
 *       goes through the same authenticated Kubernetes API as everything else, so it
 *       works identically against kind and EKS.
 * </ul>
 *
 * <p>The latency a proxied probe measures includes the API server hop, so it is a
 * few milliseconds pessimistic against what a caller inside the cluster would see.
 * That is stated in the sample's own detail rather than silently subtracted: an
 * invented correction would be worse than a known, consistent bias.
 */
@Component
public class Prober {

    private static final Logger log = LoggerFactory.getLogger(Prober.class);

    /** The scheme that means "reach this through the cluster's API server". */
    public static final String CLUSTER_SCHEME = "k8s://";

    private final KubernetesClientFactory clients;
    private final HttpClient http;

    /**
     * One Kubernetes client per cluster, held open for the life of the process.
     *
     * <p>Not an optimisation - a correctness fix. Building a client per probe meant
     * every measurement paid for a fresh TLS handshake to the API server, which
     * inflated the first readings from single-digit milliseconds to hundreds. A
     * latency SLO evaluated against that number is measuring the prober, not the
     * service. Reusing the connection is also what any real monitoring agent does.
     */
    private final Map<String, KubernetesClient> clusterClients = new ConcurrentHashMap<>();

    public Prober(KubernetesClientFactory clients) {
        this.clients = clients;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                // A probe follows redirects the way a real client would; a service
                // that answers 302 to its health check is not down.
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** What is being probed, assembled from the endpoint row and its cluster. */
    public record ProbeSpec(
            String address,
            String protocol,
            int timeoutMs,
            Integer expectedStatusCode,
            String kubeContext) {
    }

    /**
     * @param latencyMs wall-clock time to a usable response, which is the number an
     *                  SLO is written against
     */
    public record ProbeResult(boolean success, double latencyMs, Integer statusCode, String detail) {
    }

    /** Runs one probe. Never throws: a failed probe is a measurement, not an error. */
    public ProbeResult probe(ProbeSpec spec) {
        long startedAt = System.nanoTime();
        try {
            if (spec.address().startsWith(CLUSTER_SCHEME)) {
                return throughApiServer(spec, startedAt);
            }
            if ("TCP".equalsIgnoreCase(spec.protocol())) {
                return tcpConnect(spec, startedAt);
            }
            return directHttp(spec, startedAt);

        } catch (Exception e) {
            // Including the elapsed time on a failure matters: a connection refused
            // in 2ms and a timeout at 5000ms are different failures.
            return new ProbeResult(false, elapsedMs(startedAt), null, rootMessage(e));
        }
    }

    /**
     * Probes a cluster-internal service through the API server proxy at
     * {@code /api/v1/namespaces/{ns}/services/{name}:{port}/proxy{path}}.
     */
    private ProbeResult throughApiServer(ProbeSpec spec, long startedAt) throws Exception {
        ClusterAddress address = ClusterAddress.parse(spec.address());

        KubernetesClient client = clusterClients.computeIfAbsent(
                spec.kubeContext() == null ? "" : spec.kubeContext(), clients::clientFor);

        String url = client.getMasterUrl().toString().replaceAll("/$", "")
                + "/api/v1/namespaces/" + address.namespace()
                + "/services/" + address.service() + ":" + address.port()
                + "/proxy" + address.path();

        HttpRequest request = client.getHttpClient().newHttpRequestBuilder()
                .uri(url)
                .timeout(spec.timeoutMs(), TimeUnit.MILLISECONDS)
                .build();

        HttpResponse<String> response = client.getHttpClient()
                .sendAsync(request, String.class)
                .get(spec.timeoutMs(), TimeUnit.MILLISECONDS);

        return verdict(response.code(), spec, startedAt,
                "via the API server proxy, which adds a hop to the measured latency");
    }

    /** Closes the pooled cluster clients on shutdown, so nothing leaks a connection pool. */
    @PreDestroy
    void closeClients() {
        clusterClients.values().forEach(client -> {
            try {
                client.close();
            } catch (Exception e) {
                log.debug("closing probe client failed: {}", e.getMessage());
            }
        });
        clusterClients.clear();
    }

    private ProbeResult directHttp(ProbeSpec spec, long startedAt) throws Exception {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(spec.address()))
                .timeout(Duration.ofMillis(spec.timeoutMs()))
                .GET()
                .build();

        java.net.http.HttpResponse<Void> response =
                http.send(request, java.net.http.HttpResponse.BodyHandlers.discarding());

        return verdict(response.statusCode(), spec, startedAt, "direct");
    }

    /**
     * A TCP probe establishes a connection and nothing more.
     *
     * <p>That is all TCP can honestly tell you: the port is open and something
     * accepted. It says nothing about whether the process behind it is serving
     * correctly, which is why an HTTP endpoint should never be registered as TCP.
     */
    private ProbeResult tcpConnect(ProbeSpec spec, long startedAt) throws IOException {
        URI uri = URI.create(spec.address().contains("://") ? spec.address() : "tcp://" + spec.address());
        int port = uri.getPort() > 0 ? uri.getPort() : 80;

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), port), spec.timeoutMs());
            return new ProbeResult(true, elapsedMs(startedAt), null,
                    "tcp connect to " + uri.getHost() + ":" + port + " succeeded");
        }
    }

    /**
     * Decides whether a status code counts as a success.
     *
     * <p>When the endpoint declares an expected code, that code and nothing else
     * passes — an endpoint registered as expecting 204 and answering 200 has changed
     * behaviour, and a probe that shrugged at it would be worthless. With no
     * expectation declared, any 2xx passes.
     */
    private static ProbeResult verdict(int statusCode, ProbeSpec spec, long startedAt, String note) {
        boolean success = spec.expectedStatusCode() == null
                ? statusCode >= 200 && statusCode < 300
                : statusCode == spec.expectedStatusCode();

        String detail = success
                ? "HTTP " + statusCode + " (" + note + ")"
                : "HTTP " + statusCode + " but expected "
                        + (spec.expectedStatusCode() == null ? "2xx" : spec.expectedStatusCode());

        return new ProbeResult(success, elapsedMs(startedAt), statusCode, detail);
    }

    private static double elapsedMs(long startedAt) {
        return Math.round((System.nanoTime() - startedAt) / 100_000.0) / 10.0;
    }

    private static String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message;
    }

    /** A parsed {@code k8s://namespace/service:port/path} address. */
    public record ClusterAddress(String namespace, String service, int port, String path) {

        public static ClusterAddress parse(String address) {
            if (!address.startsWith(CLUSTER_SCHEME)) {
                throw new IllegalArgumentException("not a cluster address: " + address);
            }
            String rest = address.substring(CLUSTER_SCHEME.length());

            int firstSlash = rest.indexOf('/');
            if (firstSlash < 0) {
                throw new IllegalArgumentException(
                        "cluster address needs a namespace and service: " + address);
            }
            String namespace = rest.substring(0, firstSlash);
            String remainder = rest.substring(firstSlash + 1);

            String path = "/";
            int pathStart = remainder.indexOf('/');
            if (pathStart >= 0) {
                path = remainder.substring(pathStart);
                remainder = remainder.substring(0, pathStart);
            }

            int colon = remainder.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException(
                        "cluster address needs a service port: " + address);
            }

            String service = remainder.substring(0, colon);
            int port;
            try {
                port = Integer.parseInt(remainder.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("port is not a number in: " + address);
            }

            if (namespace.isBlank() || service.isBlank()) {
                throw new IllegalArgumentException("namespace and service must not be empty: " + address);
            }

            return new ClusterAddress(namespace, service, port, path);
        }
    }
}
