package io.aegiscloud.controlplane.experiment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Experiment records, stored as {@code evaluation_run} rows with
 * {@code run_type = 'CHAOS'}.
 *
 * <p>Chaos runs share a table with probe evaluations on purpose: both are "the
 * platform measured something over a window", and keeping them together is what lets
 * a score be read back against whatever was happening at the time. The
 * {@code fault_spec} column carries the injected fault, which is the ground truth
 * Phase 8's root-cause analysis will be scored against.
 */
@Repository
public class ExperimentStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ExperimentStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** The target an experiment acts on, with everything needed to inject and undo a fault. */
    public record ExperimentTarget(
            UUID targetId,
            UUID serviceId,
            String serviceName,
            UUID clusterId,
            String clusterName,
            String kubeContext,
            String namespace,
            String workload,
            int replicas) {

        public String label() {
            return serviceName + " @ " + clusterName + "/" + namespace;
        }
    }

    public Optional<ExperimentTarget> target(UUID targetId) {
        return jdbc.query("""
                SELECT t.id AS target_id, s.id AS service_id, s.name AS service_name,
                       c.id AS cluster_id, c.name AS cluster_name, c.kubeconfig_ref AS kube_context,
                       t.namespace, s.name AS workload, t.replicas
                FROM deployment_target t
                JOIN service s ON s.id = t.service_id
                JOIN cluster c ON c.id = t.cluster_id
                WHERE t.id = ? AND t.is_active
                """, (rs, i) -> new ExperimentTarget(
                UUID.fromString(rs.getString("target_id")),
                UUID.fromString(rs.getString("service_id")),
                rs.getString("service_name"),
                UUID.fromString(rs.getString("cluster_id")),
                rs.getString("cluster_name"),
                rs.getString("kube_context"),
                rs.getString("namespace"),
                rs.getString("workload"),
                rs.getInt("replicas")), targetId).stream().findFirst();
    }

    /** Experiments already running on a cluster — the input to the concurrency limit. */
    public int runningExperiments(UUID clusterId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM evaluation_run r
                JOIN deployment_target t ON t.id = r.target_id
                WHERE r.run_type = 'CHAOS' AND r.status = 'RUNNING' AND t.cluster_id = ?
                """, Integer.class, clusterId);
        return count == null ? 0 : count;
    }

    /**
     * Opens an experiment record before anything is injected.
     *
     * <p>Written first, deliberately. If the process dies mid-experiment, a RUNNING
     * row with its fault spec is the only thing that tells an operator what was done
     * to the cluster and therefore what to undo.
     */
    public UUID open(UUID serviceId, UUID targetId, Map<String, Object> faultSpec, Double scoreBefore) {
        return jdbc.queryForObject("""
                INSERT INTO evaluation_run (service_id, target_id, run_type, fault_spec,
                                            status, score_before)
                VALUES (?, ?, 'CHAOS', ?::jsonb, 'RUNNING', ?) RETURNING id
                """, UUID.class, serviceId, targetId, toJson(faultSpec), scoreBefore);
    }

    /** Records a request that never ran because the safety rules refused it. */
    public UUID recordRejected(UUID serviceId, UUID targetId, Map<String, Object> faultSpec,
                               String reason) {
        Map<String, Object> spec = new java.util.LinkedHashMap<>(faultSpec);
        spec.put("rejectedBecause", reason);

        return jdbc.queryForObject("""
                INSERT INTO evaluation_run (service_id, target_id, run_type, fault_spec,
                                            status, ended_at)
                VALUES (?, ?, 'CHAOS', ?::jsonb, 'REJECTED_BY_POLICY', now()) RETURNING id
                """, UUID.class, serviceId, targetId, toJson(spec));
    }

    public void recordDuring(UUID runId, double scoreDuring) {
        jdbc.update("UPDATE evaluation_run SET score_during = ? WHERE id = ?", scoreDuring, runId);
    }

    public void close(UUID runId, String status, Double scoreAfter, Map<String, Object> faultSpec) {
        jdbc.update("""
                UPDATE evaluation_run
                SET status = ?, score_after = ?, fault_spec = ?::jsonb, ended_at = now()
                WHERE id = ?
                """, status, scoreAfter, toJson(faultSpec), runId);
    }

    public record ExperimentRow(UUID id, String status, String targetLabel, Map<String, Object> faultSpec,
                                Double scoreBefore, Double scoreDuring, Double scoreAfter,
                                Instant startedAt, Instant endedAt) {
    }

    public Optional<ExperimentRow> experiment(UUID runId) {
        return jdbc.query("""
                SELECT r.id, r.status, r.fault_spec, r.score_before, r.score_during, r.score_after,
                       r.started_at, r.ended_at,
                       s.name || ' @ ' || COALESCE(c.name,'-') || '/' || COALESCE(t.namespace,'-')
                           AS target_label
                FROM evaluation_run r
                JOIN service s ON s.id = r.service_id
                LEFT JOIN deployment_target t ON t.id = r.target_id
                LEFT JOIN cluster c ON c.id = t.cluster_id
                WHERE r.id = ? AND r.run_type = 'CHAOS'
                """, (rs, i) -> new ExperimentRow(
                UUID.fromString(rs.getString("id")),
                rs.getString("status"),
                rs.getString("target_label"),
                readMap(rs.getString("fault_spec")),
                nullable(rs, "score_before"), nullable(rs, "score_during"), nullable(rs, "score_after"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("ended_at") == null ? null : rs.getTimestamp("ended_at").toInstant()),
                runId).stream().findFirst();
    }

    private static Double nullable(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private String toJson(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise fault spec", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            return Map.of("unparseable", json);
        }
    }
}
