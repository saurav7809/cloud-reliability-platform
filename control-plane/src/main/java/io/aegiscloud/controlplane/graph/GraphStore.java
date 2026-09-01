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

    /** Every service in one organisation — an unconnected service is still a node. */
    public Map<String, String> serviceNames(UUID orgId) {
        Map<String, String> names = new LinkedHashMap<>();
        jdbc.query("SELECT id::text, name FROM service WHERE org_id = ? ORDER BY name",
                rs -> {
                    names.put(rs.getString(1), rs.getString(2));
                }, orgId);
        return names;
    }

    /**
     * Edges within one organisation.
     *
     * <p>Both endpoints are checked, not just one. An edge is only meaningful when
     * the graph holds both services, and checking a single end would admit a
     * half-edge pointing at a service the caller cannot see.
     */
    public List<ServiceGraph.Edge> edges(UUID orgId) {
        return jdbc.query("""
                SELECT d.caller_service_id::text, d.callee_service_id::text, d.discovery_source,
                       d.call_rate_per_min, d.error_rate_pct, d.latency_p95_ms
                FROM service_dependency d
                JOIN service caller ON caller.id = d.caller_service_id AND caller.org_id = ?
                JOIN service callee ON callee.id = d.callee_service_id AND callee.org_id = ?
                """, (rs, i) -> new ServiceGraph.Edge(
                rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getDouble(4), rs.getDouble(5), rs.getDouble(6)), orgId, orgId);
    }

    /** Builds a snapshot of one organisation's graph. */
    public ServiceGraph load(UUID orgId) {
        return new ServiceGraph(serviceNames(orgId), edges(orgId));
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

    /** A service name, only when the service belongs to this organisation. */
    public Optional<String> serviceName(UUID orgId, UUID serviceId) {
        return jdbc.queryForList("SELECT name FROM service WHERE id = ? AND org_id = ?",
                        String.class, serviceId, orgId)
                .stream().findFirst();
    }

    /** Unscoped lookup, for the engines that run without a caller. */
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
    public List<EdgeRow> edgeRows(UUID orgId) {
        return jdbc.query("""
                SELECT d.caller_service_id::text, caller.name, d.callee_service_id::text, callee.name,
                       d.discovery_source, d.call_rate_per_min, d.error_rate_pct, d.latency_p95_ms,
                       d.last_seen_at
                FROM service_dependency d
                JOIN service caller ON caller.id = d.caller_service_id
                JOIN service callee ON callee.id = d.callee_service_id
                WHERE caller.org_id = ? AND callee.org_id = ?
                ORDER BY caller.name, callee.name
                """, (rs, i) -> new EdgeRow(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getDouble(6), rs.getDouble(7), rs.getDouble(8),
                rs.getTimestamp(9).toInstant()), orgId, orgId);
    }

    /**
     * Services whose most recent reliability reading is below a threshold.
     *
     * <p>The input to graph correlation: which services are unhappy right now, so the
     * graph can be asked which of them explains the others.
     */
    public List<String> degradedServiceIds(UUID orgId, double scoreBelow) {
        return jdbc.queryForList("""
                SELECT DISTINCT t.service_id::text
                FROM deployment_target t
                JOIN cluster c ON c.id = t.cluster_id
                WHERE t.is_active AND c.org_id = ?
                  AND t.reliability_score > 0 AND t.reliability_score < ?
                """, String.class, orgId, scoreBelow);
    }
}
