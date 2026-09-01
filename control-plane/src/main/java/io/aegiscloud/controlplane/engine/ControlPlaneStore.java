package io.aegiscloud.controlplane.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aegiscloud.controlplane.domain.Models;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * Every read and write the Phase 4 engines make against PostgreSQL.
 *
 * <p>Kept apart from {@code PlatformStore}, which serves the dashboard's read model.
 * This one is the write side of the autonomous loop: the events it produces, the
 * guardrails it is bound by, and the ledger it is judged on.
 */
@Repository
public class ControlPlaneStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ControlPlaneStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ----------------------------------------------------------------- targets

    private static final String TARGET_SELECT = """
            SELECT t.id                                   AS target_id,
                   s.name                                 AS service_name,
                   c.id                                   AS cluster_id,
                   c.name                                 AS cluster_name,
                   c.kubeconfig_ref                       AS kube_context,
                   t.namespace                            AS namespace,
                   -- The Deployment is named after the service. deployment_target.label
                   -- is a human-readable placement label ("prod-aws-use1"), not a
                   -- Kubernetes object name, so it must not be used here.
                   s.name                                 AS workload,
                   t.scaling_strategy                     AS strategy,
                   t.replicas                             AS replicas,
                   (SELECT o.objective_value FROM slo o
                     WHERE o.target_id = t.id AND o.is_active
                       AND o.sli_type IN ('LATENCY_P95','LATENCY_P99')
                     ORDER BY o.sli_type LIMIT 1)         AS latency_objective
            FROM deployment_target t
            JOIN service s ON s.id = t.service_id
            JOIN cluster c ON c.id = t.cluster_id
            WHERE t.is_active AND c.is_active
            """;

    /**
     * Targets on clusters the platform believes it can reach.
     *
     * <p>Unreachable clusters are skipped rather than acted on: with no live reading
     * there is nothing to base a decision on, and the last known state is exactly the
     * stale data that produces a wrong autonomous action.
     */
    public List<ManagedTarget> reachableTargets() {
        return jdbc.query(TARGET_SELECT + " AND c.status <> 'UNREACHABLE' ORDER BY s.name",
                (rs, i) -> mapTarget(rs));
    }

    /** One target by id, regardless of cluster reachability. */
    public Optional<ManagedTarget> target(UUID targetId) {
        return jdbc.query(TARGET_SELECT + " AND t.id = ?", (rs, i) -> mapTarget(rs), targetId)
                .stream().findFirst();
    }

    private static ManagedTarget mapTarget(java.sql.ResultSet rs) throws java.sql.SQLException {
        double objective = rs.getDouble("latency_objective");
        return new ManagedTarget(
                UUID.fromString(rs.getString("target_id")),
                rs.getString("service_name"),
                UUID.fromString(rs.getString("cluster_id")),
                rs.getString("cluster_name"),
                rs.getString("kube_context"),
                rs.getString("namespace"),
                rs.getString("workload"),
                Models.ScalingStrategy.valueOf(rs.getString("strategy")),
                rs.getInt("replicas"),
                rs.wasNull() ? OptionalDouble.empty() : OptionalDouble.of(objective));
    }

    /** Keeps the denormalised replica count in step with what the cluster now runs. */
    public void updateReplicas(UUID targetId, int replicas) {
        jdbc.update("UPDATE deployment_target SET replicas = ?, desired_replicas = ? WHERE id = ?",
                replicas, replicas, targetId);
    }

    /**
     * Latency samples for a target, oldest first — the series the TREND strategy
     * fits a line through.
     */
    public List<Double> recentLatency(UUID targetId, int limit) {
        return jdbc.queryForList("""
                SELECT value FROM (
                    SELECT value, sampled_at FROM metric_sample
                    WHERE target_id = ? AND metric_type = 'LATENCY_MS'
                    ORDER BY sampled_at DESC LIMIT ?
                ) recent ORDER BY sampled_at ASC
                """, Double.class, targetId, limit);
    }

    // ---------------------------------------------------------------- policies

    /**
     * The guardrails in force for a cluster.
     *
     * <p>Falls back to the org-wide row ({@code cluster_id IS NULL}) and then to the
     * schema defaults, so a cluster with no policy of its own is still governed —
     * absence of configuration must never mean absence of limits.
     */
    public PolicyLimits limitsFor(UUID clusterId) {
        List<PolicyLimits> exact = jdbc.query(
                "SELECT max_replicas, max_concurrent_experiments, protected_namespaces "
                        + "FROM policy WHERE cluster_id = ?", this::mapLimits, clusterId);
        if (!exact.isEmpty()) {
            return exact.get(0);
        }
        List<PolicyLimits> global = jdbc.query(
                "SELECT max_replicas, max_concurrent_experiments, protected_namespaces "
                        + "FROM policy WHERE cluster_id IS NULL", this::mapLimits);
        return global.isEmpty() ? PolicyLimits.defaults() : global.get(0);
    }

    private PolicyLimits mapLimits(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new PolicyLimits(rs.getInt("max_replicas"), rs.getInt("max_concurrent_experiments"),
                readStringList(rs.getString("protected_namespaces")));
    }

    /** Creates or replaces a cluster's policy row. */
    public void savePolicy(UUID clusterId, PolicyLimits limits) {
        String namespaces = toJson(limits.protectedNamespaces());
        int updated = jdbc.update("""
                UPDATE policy SET max_replicas = ?, max_concurrent_experiments = ?,
                                  protected_namespaces = ?::jsonb, updated_at = now()
                WHERE cluster_id = ?
                """, limits.maxReplicas(), limits.maxConcurrentExperiments(), namespaces, clusterId);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO policy (cluster_id, max_replicas, max_concurrent_experiments,
                                        protected_namespaces)
                    VALUES (?, ?, ?, ?::jsonb)
                    """, clusterId, limits.maxReplicas(), limits.maxConcurrentExperiments(), namespaces);
        }
    }

    // ---------------------------------------------------------------- autonomy

    /**
     * The autonomy level for an action type on a cluster.
     *
     * <p>Defaults to SUGGEST when nothing is configured (FR-36). The default is
     * decided here rather than in the database so that a cluster with no row at all
     * behaves identically to one whose row says SUGGEST.
     */
    public AutonomyLevel levelFor(UUID clusterId, ActionType actionType) {
        List<String> rows = jdbc.queryForList(
                "SELECT level FROM autonomy_setting WHERE cluster_id = ? AND action_type = ?",
                String.class, clusterId, actionType.name());
        return rows.isEmpty() ? AutonomyLevel.SUGGEST : AutonomyLevel.valueOf(rows.get(0));
    }

    public void setLevel(UUID clusterId, ActionType actionType, AutonomyLevel level, UUID updatedBy) {
        jdbc.update("""
                INSERT INTO autonomy_setting (cluster_id, action_type, level, updated_by)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (cluster_id, action_type)
                DO UPDATE SET level = EXCLUDED.level, updated_by = EXCLUDED.updated_by,
                              updated_at = now()
                """, clusterId, actionType.name(), level.name(), updatedBy);
    }

    public record AutonomySetting(String clusterId, String clusterName, String actionType,
                                  String level, Instant updatedAt) {
    }

    /**
     * Every cluster crossed with every action type, showing the level actually in
     * force — configured or defaulted. Listing only stored rows would hide the
     * clusters whose behaviour nobody has decided yet, which are the ones worth
     * looking at.
     */
    public List<AutonomySetting> autonomySettings() {
        return jdbc.query("""
                SELECT c.id AS cluster_id, c.name AS cluster_name, t.action_type,
                       COALESCE(a.level, 'SUGGEST') AS level, a.updated_at
                FROM cluster c
                CROSS JOIN (SELECT unnest(?::text[]) AS action_type) t
                LEFT JOIN autonomy_setting a
                       ON a.cluster_id = c.id AND a.action_type = t.action_type
                WHERE c.is_active
                ORDER BY c.name, t.action_type
                """, (rs, i) -> new AutonomySetting(
                        rs.getString("cluster_id"), rs.getString("cluster_name"),
                        rs.getString("action_type"), rs.getString("level"),
                        rs.getTimestamp("updated_at") == null
                                ? null : rs.getTimestamp("updated_at").toInstant()),
                (Object) ActionType.namesAsPgArray());
    }

    // ------------------------------------------------------------------ events

    public void recordScalingEvent(UUID targetId, int previous, int next, String triggerMetric,
                                   double triggerValue, Models.ScalingStrategy strategy) {
        jdbc.update("""
                INSERT INTO scaling_event (target_id, previous_replicas, new_replicas,
                                           trigger_metric, trigger_value, strategy)
                VALUES (?, ?, ?, ?, ?, ?)
                """, targetId, previous, next, triggerMetric, triggerValue, strategy.name());
    }

    /** When this target was last scaled — the input to the flapping guard. */
    public Optional<Instant> lastScaledAt(UUID targetId) {
        return jdbc.queryForList(
                        "SELECT max(decided_at) FROM scaling_event WHERE target_id = ?",
                        Timestamp.class, targetId)
                .stream().filter(java.util.Objects::nonNull).findFirst().map(Timestamp::toInstant);
    }

    public long recordHealingEvent(UUID targetId, String podName, String reason, String actionTaken) {
        return jdbc.queryForObject("""
                INSERT INTO healing_event (target_id, pod_name, reason, action_taken)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, targetId, podName, reason, actionTaken);
    }

    /** Pod names this target already has an unresolved healing event for. */
    public List<String> openHealingPods(UUID targetId) {
        return jdbc.queryForList(
                "SELECT pod_name FROM healing_event WHERE target_id = ? AND resolved_at IS NULL",
                String.class, targetId);
    }

    /**
     * Closes healing events for pods that are no longer failing.
     *
     * <p>Recovery is confirmed on a later cycle rather than immediately after the
     * action, because a deleted pod is not a recovered workload until its replacement
     * is actually ready.
     */
    public int resolveHealingEvents(UUID targetId, List<String> recoveredPods) {
        if (recoveredPods.isEmpty()) {
            return 0;
        }
        return jdbc.update("""
                UPDATE healing_event SET resolved_at = now()
                WHERE target_id = ? AND resolved_at IS NULL AND pod_name = ANY (?::text[])
                """, targetId, toPgArray(recoveredPods));
    }

    // ------------------------------------------------------------------ ledger

    /**
     * Records what the loop observed, concluded and did.
     *
     * <p>Written for rejected and merely-suggested actions too. A platform that only
     * logs what it did cannot answer the question operators actually ask, which is
     * why it did not do something.
     */
    public UUID recordAction(UUID targetId, ActionType actionType, Map<String, Object> observed,
                             String concluded, Map<String, Object> executed,
                             boolean policyPassed, Double scoreBefore) {
        UUID id = jdbc.queryForObject("""
                INSERT INTO autonomous_action (target_id, action_type, observed, concluded,
                                               executed, policy_check, score_before)
                VALUES (?, ?, ?::jsonb, ?, ?::jsonb, ?, ?) RETURNING id
                """, UUID.class, targetId, actionType.name(), toJson(observed), concluded,
                toJson(executed), policyPassed ? "PASSED" : "REJECTED", scoreBefore);

        jdbc.update("""
                INSERT INTO audit_log_entry (org_id, actor_kind, action, entity_type, entity_id,
                                             after_state)
                SELECT c.org_id, 'ENGINE', ?, 'deployment_target', t.id::text, ?::jsonb
                FROM deployment_target t JOIN cluster c ON c.id = t.cluster_id
                WHERE t.id = ?
                """, actionType.name(), toJson(executed), targetId);

        return id;
    }

    public record PendingAction(UUID id, UUID targetId, String actionType, Double scoreBefore,
                                Map<String, Object> executed, Instant executedAt) {
    }

    /** Applied actions whose effect has not yet been checked. */
    public List<PendingAction> pendingVerification() {
        return jdbc.query("""
                SELECT id, target_id, action_type, score_before, executed, executed_at
                FROM autonomous_action
                WHERE outcome = 'PENDING' AND policy_check = 'PASSED'
                  AND executed::text <> '{}'
                ORDER BY executed_at
                """, (rs, i) -> new PendingAction(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("target_id")),
                rs.getString("action_type"),
                rs.getObject("score_before") == null ? null : rs.getDouble("score_before"),
                readMap(rs.getString("executed")),
                rs.getTimestamp("executed_at").toInstant()));
    }

    public void recordOutcome(UUID actionId, String outcome, double scoreAfter) {
        jdbc.update("""
                UPDATE autonomous_action
                SET outcome = ?, score_after = ?, verified_at = now()
                WHERE id = ?
                """, outcome, scoreAfter, actionId);
    }

    public record ActionRow(String id, String targetLabel, String actionType, String concluded,
                            String policyCheck, String outcome, Double scoreBefore, Double scoreAfter,
                            Map<String, Object> observed, Map<String, Object> executed,
                            Instant executedAt, Instant verifiedAt) {
    }

    public List<ActionRow> recentActions(int limit) {
        return jdbc.query("""
                SELECT a.id, s.name || ' @ ' || c.name || '/' || t.namespace AS target_label,
                       a.action_type, a.concluded, a.policy_check, a.outcome,
                       a.score_before, a.score_after, a.observed, a.executed,
                       a.executed_at, a.verified_at
                FROM autonomous_action a
                JOIN deployment_target t ON t.id = a.target_id
                JOIN service s ON s.id = t.service_id
                JOIN cluster c ON c.id = t.cluster_id
                ORDER BY a.executed_at DESC
                LIMIT ?
                """, (rs, i) -> new ActionRow(
                rs.getString("id"), rs.getString("target_label"), rs.getString("action_type"),
                rs.getString("concluded"), rs.getString("policy_check"), rs.getString("outcome"),
                rs.getObject("score_before") == null ? null : rs.getDouble("score_before"),
                rs.getObject("score_after") == null ? null : rs.getDouble("score_after"),
                readMap(rs.getString("observed")), readMap(rs.getString("executed")),
                rs.getTimestamp("executed_at").toInstant(),
                rs.getTimestamp("verified_at") == null
                        ? null : rs.getTimestamp("verified_at").toInstant()), limit);
    }

    // ------------------------------------------------------------------- json

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise control-plane payload", e);
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

    @SuppressWarnings("unchecked")
    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, List.class);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /** Renders a list as a PostgreSQL text[] literal for {@code = ANY (...)} comparisons. */
    static String toPgArray(List<String> values) {
        return "{" + String.join(",", values.stream().map(v -> "\"" + v.replace("\"", "\\\"") + "\"").toList()) + "}";
    }
}
