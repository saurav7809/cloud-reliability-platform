package io.aegiscloud.controlplane.graph;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Reads and writes {@code service_dependency}, the self-referential table that makes the graph a graph. */
@Repository
public class GraphStore {

    private final JdbcTemplate jdbc;

    public GraphStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every service, whether or not it has edges — an unconnected service is still a node. */
    public Map<String, String> serviceNames() {
        Map<String, String> names = new LinkedHashMap<>();
        jdbc.query("SELECT id::text, name FROM service ORDER BY name",
                rs -> {
                    names.put(rs.getString(1), rs.getString(2));
                });
        return names;
    }

    public List<ServiceGraph.Edge> edges() {
        return jdbc.query("""
                SELECT caller_service_id::text, callee_service_id::text, discovery_source,
                       call_rate_per_min, error_rate_pct, latency_p95_ms
                FROM service_dependency
                """, (rs, i) -> new ServiceGraph.Edge(
                rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getDouble(4), rs.getDouble(5), rs.getDouble(6)));
    }

    /** Builds a snapshot of the whole graph. */
    public ServiceGraph load() {
        return new ServiceGraph(serviceNames(), edges());
    }

    /**
     * Records a dependency, or refreshes one already known.
     *
     * <p>An edge rediscovered keeps its original discovery source unless the new one
     * is stronger. An experiment proves a dependency in a way a trace cannot — a
     * trace shows A called B, an experiment shows A breaks when B does — so
     * EXPERIMENT is allowed to overwrite TRACE, and nothing overwrites EXPERIMENT.
     */
    public void upsertEdge(UUID caller, UUID callee, String discoverySource,
                           double callRatePerMin, double errorRatePct, double latencyP95Ms) {
        if (caller.equals(callee)) {
            throw new IllegalArgumentException("a service cannot depend on itself");
        }

        jdbc.update("""
                INSERT INTO service_dependency (caller_service_id, callee_service_id,
                                                discovery_source, call_rate_per_min,
                                                error_rate_pct, latency_p95_ms, last_seen_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (caller_service_id, callee_service_id) DO UPDATE SET
                    discovery_source = CASE
                        WHEN service_dependency.discovery_source = 'EXPERIMENT' THEN 'EXPERIMENT'
                        WHEN EXCLUDED.discovery_source = 'EXPERIMENT' THEN 'EXPERIMENT'
                        ELSE EXCLUDED.discovery_source END,
                    call_rate_per_min = EXCLUDED.call_rate_per_min,
                    error_rate_pct = EXCLUDED.error_rate_pct,
                    latency_p95_ms = EXCLUDED.latency_p95_ms,
                    last_seen_at = now()
                """, caller, callee, discoverySource, callRatePerMin, errorRatePct, latencyP95Ms);
    }

    public int deleteEdge(UUID caller, UUID callee) {
        return jdbc.update(
                "DELETE FROM service_dependency WHERE caller_service_id = ? AND callee_service_id = ?",
                caller, callee);
    }

    public Optional<String> serviceName(UUID serviceId) {
        return jdbc.queryForList("SELECT name FROM service WHERE id = ?", String.class, serviceId)
                .stream().findFirst();
    }

    /** The service a deployment target belongs to. */
    public Optional<UUID> serviceOfTarget(UUID targetId) {
        return jdbc.queryForList("SELECT service_id FROM deployment_target WHERE id = ?",
                UUID.class, targetId).stream().findFirst();
    }

    public record EdgeRow(String callerServiceId, String callerName, String calleeServiceId,
                          String calleeName, String discoverySource, double callRatePerMin,
                          double errorRatePct, double latencyP95Ms, Instant lastSeenAt) {
    }

    /** Edges with both service names resolved, for the API and the dashboard. */
    public List<EdgeRow> edgeRows() {
        return jdbc.query("""
                SELECT d.caller_service_id::text, caller.name, d.callee_service_id::text, callee.name,
                       d.discovery_source, d.call_rate_per_min, d.error_rate_pct, d.latency_p95_ms,
                       d.last_seen_at
                FROM service_dependency d
                JOIN service caller ON caller.id = d.caller_service_id
                JOIN service callee ON callee.id = d.callee_service_id
                ORDER BY caller.name, callee.name
                """, (rs, i) -> new EdgeRow(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getDouble(6), rs.getDouble(7), rs.getDouble(8),
                rs.getTimestamp(9).toInstant()));
    }

    /**
     * Services whose most recent reliability reading is below a threshold.
     *
     * <p>The input to graph correlation: which services are unhappy right now, so the
     * graph can be asked which of them explains the others.
     */
    public List<String> degradedServiceIds(double scoreBelow) {
        return jdbc.queryForList("""
                SELECT DISTINCT t.service_id::text
                FROM deployment_target t
                WHERE t.is_active AND t.reliability_score > 0 AND t.reliability_score < ?
                """, String.class, scoreBelow);
    }
}
