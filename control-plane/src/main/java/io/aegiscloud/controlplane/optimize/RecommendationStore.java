package io.aegiscloud.controlplane.optimize;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aegiscloud.controlplane.domain.Models;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

/** Recommendations, and the facts they are derived from. */
@Repository
public class RecommendationStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public RecommendationStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * Everything the advisor needs about every active target, in one query.
     *
     * <p>The reliability score and sample count come from measurements rather than
     * the denormalised columns, for the same reason RCA does it: those columns carry
     * numbers for targets nothing has ever probed, and advising on a fixture is worse
     * than saying nothing.
     */
    public List<TargetRow> targetFacts() {
        return jdbc.query("""
                SELECT t.id::text            AS target_id,
                       s.name                AS service_name,
                       c.name                AS cluster_name,
                       c.kubeconfig_ref      AS kube_context,
                       t.namespace           AS namespace,
                       s.name                AS workload,
                       t.replicas            AS replicas,
                       t.monthly_cost_usd    AS monthly_cost,
                       t.scaling_strategy    AS scaling_strategy,
                       EXISTS (SELECT 1 FROM slo o WHERE o.target_id = t.id AND o.is_active
                                 AND o.sli_type IN ('LATENCY_P95','LATENCY_P99')) AS has_latency_slo,
                       (SELECT count(*) FROM metric_sample m
                         WHERE m.target_id = t.id
                           AND m.sampled_at > now() - INTERVAL '1 hour')          AS sample_count,
                       (SELECT snap.score FROM reliability_score_snapshot snap
                         WHERE snap.target_id = t.id
                         ORDER BY snap.window_end DESC LIMIT 1)                   AS score,
                       (SELECT min(b.budget_remaining_pct)
                          FROM error_budget_snapshot b
                          JOIN slo o2 ON o2.id = b.slo_id
                         WHERE o2.target_id = t.id
                           AND b.computed_at > now() - INTERVAL '1 hour')         AS budget_remaining
                FROM deployment_target t
                JOIN service s ON s.id = t.service_id
                JOIN cluster c ON c.id = t.cluster_id
                WHERE t.is_active AND c.is_active
                ORDER BY s.name
                """, (rs, i) -> new TargetRow(
                rs.getString("target_id"), rs.getString("service_name"),
                rs.getString("cluster_name"), rs.getString("kube_context"),
                rs.getString("namespace"), rs.getString("workload"),
                rs.getInt("replicas"), rs.getDouble("monthly_cost"),
                Models.ScalingStrategy.valueOf(rs.getString("scaling_strategy")),
                rs.getBoolean("has_latency_slo"), rs.getInt("sample_count"),
                optional(rs, "score"), optional(rs, "budget_remaining")));
    }

    private static OptionalDouble optional(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? OptionalDouble.empty() : OptionalDouble.of(value);
    }

    public record TargetRow(String targetId, String serviceName, String clusterName,
                            String kubeContext, String namespace, String workload,
                            int replicas, double monthlyCostUsd,
                            Models.ScalingStrategy scalingStrategy, boolean hasLatencySlo,
                            int sampleCount, OptionalDouble score, OptionalDouble budgetRemaining) {
    }

    /**
     * Stores a recommendation, replacing an open one of the same kind for the same
     * target.
     *
     * <p>Advice is a standing opinion, not an event log: re-running the advisor
     * should refresh what it thinks, not stack up ten copies of the same suggestion
     * with slightly different numbers. Applied and dismissed rows are left untouched,
     * because those are decisions and decisions are history.
     */
    public UUID upsert(OptimizationAdvisor.Recommendation recommendation) {
        jdbc.update("""
                DELETE FROM recommendation
                WHERE target_id = ? AND kind = ? AND status = 'OPEN'
                """, UUID.fromString(recommendation.targetId()), recommendation.kind());

        return jdbc.queryForObject("""
                INSERT INTO recommendation (target_id, kind, title, rationale, evidence,
                                            estimated_monthly_saving_usd, reliability_impact, status)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, 'OPEN') RETURNING id
                """, UUID.class, UUID.fromString(recommendation.targetId()),
                recommendation.kind(), recommendation.title(), recommendation.rationale(),
                toJson(withSafety(recommendation)), recommendation.estimatedMonthlySavingUsd(),
                recommendation.reliabilityImpact().name());
    }

    /**
     * The safety verdict travels inside the evidence.
     *
     * <p>Kept with the facts rather than in a column of its own so that whatever
     * reads a recommendation — the API, the dashboard, a future export — cannot
     * receive the saving without also receiving whether taking it is safe.
     */
    private Map<String, Object> withSafety(OptimizationAdvisor.Recommendation recommendation) {
        Map<String, Object> evidence = new java.util.LinkedHashMap<>(recommendation.evidence());
        evidence.put("safeToApply", recommendation.safeToApply());
        if (recommendation.proposedReplicas() != null) {
            evidence.put("proposedReplicas", recommendation.proposedReplicas());
        }
        return evidence;
    }

    public record RecommendationRow(UUID id, String targetId, String serviceName, String clusterName,
                                    String kind, String title, String rationale,
                                    Map<String, Object> evidence, double estimatedMonthlySavingUsd,
                                    String reliabilityImpact, String status, String outcome,
                                    Instant createdAt, Instant appliedAt) {

        /** Whether the advisor considered this safe to act on. */
        public boolean safeToApply() {
            return Boolean.TRUE.equals(evidence().get("safeToApply"));
        }

        public Optional<Integer> proposedReplicas() {
            Object value = evidence().get("proposedReplicas");
            return value instanceof Number number
                    ? Optional.of(number.intValue()) : Optional.empty();
        }
    }

    /** The select every recommendation read shares, so the two cannot drift apart. */
    private static final String RECOMMENDATION_SELECT = """
            SELECT r.id, r.target_id::text, s.name AS service_name, c.name AS cluster_name,
                   r.kind, r.title, r.rationale, r.evidence, r.estimated_monthly_saving_usd,
                   r.reliability_impact, r.status, r.outcome, r.created_at, r.applied_at
            FROM recommendation r
            JOIN deployment_target t ON t.id = r.target_id
            JOIN service s ON s.id = t.service_id
            JOIN cluster c ON c.id = t.cluster_id
            """;

    public List<RecommendationRow> recommendations(String status, int limit) {
        // Assembled with explicit newlines rather than by concatenating text blocks:
        // a block ends where its content ends, so "WHERE true" and "ORDER BY" ran
        // together into one unparseable token.
        String sql = RECOMMENDATION_SELECT
                + (status == null ? "WHERE true " : "WHERE r.status = ? ")
                + "ORDER BY r.estimated_monthly_saving_usd DESC, r.created_at DESC "
                + "LIMIT ?";

        Object[] args = status == null ? new Object[]{limit} : new Object[]{status, limit};

        return jdbc.query(sql, (rs, i) -> new RecommendationRow(
                UUID.fromString(rs.getString("id")), rs.getString(2), rs.getString("service_name"),
                rs.getString("cluster_name"), rs.getString("kind"), rs.getString("title"),
                rs.getString("rationale"), readMap(rs.getString("evidence")),
                rs.getDouble("estimated_monthly_saving_usd"), rs.getString("reliability_impact"),
                rs.getString("status"), rs.getString("outcome"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("applied_at") == null
                        ? null : rs.getTimestamp("applied_at").toInstant()), args);
    }

    public Optional<RecommendationRow> recommendation(UUID id) {
        return jdbc.query(RECOMMENDATION_SELECT + "WHERE r.id = ?",
                (rs, i) -> new RecommendationRow(
                UUID.fromString(rs.getString("id")), rs.getString(2), rs.getString("service_name"),
                rs.getString("cluster_name"), rs.getString("kind"), rs.getString("title"),
                rs.getString("rationale"), readMap(rs.getString("evidence")),
                rs.getDouble("estimated_monthly_saving_usd"), rs.getString("reliability_impact"),
                rs.getString("status"), rs.getString("outcome"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("applied_at") == null
                        ? null : rs.getTimestamp("applied_at").toInstant()), id)
                .stream().findFirst();
    }

    /** Records the decision and what came of it (FR-34), so bad advice stays visible. */
    public void close(UUID id, String status, UUID actor, String outcome) {
        jdbc.update("""
                UPDATE recommendation
                SET status = ?, applied_by = ?, applied_at = now(), outcome = ?
                WHERE id = ?
                """, status, actor, outcome, id);
    }

    /** The target behind a recommendation, for applying it. */
    public Optional<TargetRow> targetOf(UUID recommendationId) {
        return recommendation(recommendationId).flatMap(row -> targetFacts().stream()
                .filter(t -> t.targetId().equals(row.targetId()))
                .findFirst());
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise recommendation evidence", e);
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
