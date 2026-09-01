package io.aegiscloud.controlplane.rca;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Incidents, verdicts, and the signals RCA reasons from.
 *
 * <p>The read side matters as much as the write side here. Everything the engine
 * concludes is derived from these queries, so a signal this class cannot fetch is a
 * signal no verdict can cite — which, given FR-29, means it may as well not exist.
 */
@Repository
public class RcaStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public RcaStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // -------------------------------------------------------------- incidents

    public record IncidentRow(UUID id, String title, String status, String rootCauseService,
                              Double confidence, int blastRadiusCount,
                              Instant startedAt, Instant resolvedAt) {
    }

    /** Opens an incident. Returns its id. */
    public UUID open(String title, int blastRadiusCount) {
        return jdbc.queryForObject("""
                INSERT INTO incident (org_id, title, status, blast_radius_count)
                SELECT id, ?, 'DIAGNOSING', ? FROM organization ORDER BY created_at LIMIT 1
                RETURNING id
                """, UUID.class, title, blastRadiusCount);
    }

    public void recordRootCause(UUID incidentId, UUID rootCauseTargetId, double confidence) {
        jdbc.update("""
                UPDATE incident SET root_cause_target_id = ?, confidence = ?, status = 'OPEN'
                WHERE id = ?
                """, rootCauseTargetId, confidence, incidentId);
    }

    public void resolve(UUID incidentId) {
        jdbc.update("UPDATE incident SET status = 'RESOLVED', resolved_at = now() WHERE id = ?",
                incidentId);
    }

    public List<IncidentRow> incidents(int limit) {
        return jdbc.query("""
                SELECT i.id, i.title, i.status, s.name AS root_cause_service, i.confidence,
                       i.blast_radius_count, i.started_at, i.resolved_at
                FROM incident i
                LEFT JOIN deployment_target t ON t.id = i.root_cause_target_id
                LEFT JOIN service s ON s.id = t.service_id
                ORDER BY i.started_at DESC LIMIT ?
                """, (rs, n) -> new IncidentRow(
                UUID.fromString(rs.getString("id")), rs.getString("title"), rs.getString("status"),
                rs.getString("root_cause_service"),
                rs.getObject("confidence") == null ? null : rs.getDouble("confidence"),
                rs.getInt("blast_radius_count"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("resolved_at") == null
                        ? null : rs.getTimestamp("resolved_at").toInstant()), limit);
    }

    public Optional<IncidentRow> incident(UUID id) {
        return incidents(500).stream().filter(i -> i.id().equals(id)).findFirst();
    }

    // --------------------------------------------------------------- verdicts

    /** Replaces the verdicts for an incident with a fresh analysis. */
    public void saveVerdicts(UUID incidentId, List<RcaEngine.Verdict> verdicts) {
        jdbc.update("DELETE FROM rca_verdict WHERE incident_id = ?", incidentId);

        for (RcaEngine.Verdict verdict : verdicts) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("assessment", verdict.assessment().name());
            evidence.put("facts", verdict.evidence().stream()
                    .map(e -> Map.of("signal", e.signal().name(),
                            "weight", e.weight(), "detail", e.description()))
                    .toList());

            Map<String, Object> signalScores = new LinkedHashMap<>();
            verdict.signalScores().forEach((signal, score) -> signalScores.put(signal.name(), score));

            jdbc.update("""
                    INSERT INTO rca_verdict (incident_id, candidate_target_id, rank, confidence,
                                             reasoning, evidence, signal_scores)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                    """, incidentId, UUID.fromString(verdict.targetId()), verdict.rank(),
                    verdict.confidence(), verdict.reasoning(), toJson(evidence), toJson(signalScores));
        }
    }

    public record VerdictRow(int rank, String serviceName, String targetId, double confidence,
                             String reasoning, Map<String, Object> evidence,
                             Map<String, Object> signalScores, String humanVerdict) {
    }

    public List<VerdictRow> verdicts(UUID incidentId) {
        return jdbc.query("""
                SELECT v.rank, s.name AS service_name, v.candidate_target_id::text, v.confidence,
                       v.reasoning, v.evidence, v.signal_scores, v.human_verdict
                FROM rca_verdict v
                JOIN deployment_target t ON t.id = v.candidate_target_id
                JOIN service s ON s.id = t.service_id
                WHERE v.incident_id = ?
                ORDER BY v.rank
                """, (rs, n) -> new VerdictRow(
                rs.getInt("rank"), rs.getString("service_name"), rs.getString(3),
                rs.getDouble("confidence"), rs.getString("reasoning"),
                readMap(rs.getString("evidence")), readMap(rs.getString("signal_scores")),
                rs.getString("human_verdict")), incidentId);
    }

    /** A human marking a verdict right or wrong (FR-30) — the only ground truth outside chaos. */
    public int judge(UUID incidentId, int rank, String humanVerdict) {
        return jdbc.update("""
                UPDATE rca_verdict SET human_verdict = ? WHERE incident_id = ? AND rank = ?
                """, humanVerdict, incidentId, rank);
    }

    // ---------------------------------------------------------------- signals

    /** A degraded target with everything the engine needs to reason about it. */
    public record DegradedTarget(UUID targetId, UUID serviceId, String serviceName,
                                 String namespace, String clusterName, String kubeContext,
                                 String workload, double score) {
    }

    /**
     * Targets whose most recently <em>measured</em> score sits below the threshold.
     *
     * <p>The score is read from {@code reliability_score_snapshot}, not from the
     * denormalised column on the target, and that distinction matters more than it
     * looks. The column holds a number for every target including ones nothing has
     * ever probed — seeded demo fleets, clusters with no kubeconfig on this machine —
     * and those numbers are indistinguishable from measurements once they are in the
     * column. Diagnosing them produces incidents about services the platform has
     * never observed, with verdicts whose evidence is a score drop that never
     * happened.
     *
     * <p>A snapshot row, by contrast, exists only because the Evaluation Engine
     * measured something. No measurement, no candidacy.
     */
    public List<DegradedTarget> degradedTargets(double scoreBelow) {
        return jdbc.query("""
                SELECT t.id, t.service_id, s.name AS service_name, t.namespace,
                       c.name AS cluster_name, c.kubeconfig_ref, s.name AS workload,
                       latest.score
                FROM deployment_target t
                JOIN service s ON s.id = t.service_id
                JOIN cluster c ON c.id = t.cluster_id
                JOIN LATERAL (
                    SELECT score FROM reliability_score_snapshot snap
                    WHERE snap.target_id = t.id
                    ORDER BY snap.window_end DESC LIMIT 1
                ) latest ON true
                WHERE t.is_active AND latest.score < ?
                ORDER BY latest.score
                """, (rs, n) -> new DegradedTarget(
                UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getDouble(8)), scoreBelow);
    }

    /**
     * When this target's score first fell below the threshold within the window.
     *
     * <p>Read from the score history rather than from an alert, because the history
     * is sampled at a known cadence and an alert is not — temporal ordering is only
     * as trustworthy as the timestamps behind it.
     */
    public Optional<Instant> degradedSince(UUID targetId, double scoreBelow, Instant since) {
        return jdbc.queryForList("""
                SELECT min(window_end) FROM reliability_score_snapshot
                WHERE target_id = ? AND score < ? AND window_end >= ?
                """, Timestamp.class, targetId, scoreBelow, Timestamp.from(since))
                .stream().filter(java.util.Objects::nonNull).findFirst().map(Timestamp::toInstant);
    }

    /**
     * Changes near the incident: scaling decisions, healing actions and chaos runs.
     *
     * <p>Chaos is included deliberately. An experiment is the most consequential
     * change a service can undergo, and an RCA that failed to mention one was running
     * would be hiding the answer.
     */
    public List<String> changeEvents(UUID targetId, Instant since) {
        List<String> events = new ArrayList<>();

        events.addAll(jdbc.queryForList("""
                SELECT 'scaled ' || previous_replicas || ' -> ' || new_replicas
                       || ' on ' || trigger_metric
                FROM scaling_event WHERE target_id = ? AND decided_at >= ?
                ORDER BY decided_at DESC LIMIT 5
                """, String.class, targetId, Timestamp.from(since)));

        events.addAll(jdbc.queryForList("""
                SELECT action_taken || ' ' || pod_name || ' (' || reason || ')'
                FROM healing_event WHERE target_id = ? AND detected_at >= ?
                ORDER BY detected_at DESC LIMIT 5
                """, String.class, targetId, Timestamp.from(since)));

        events.addAll(jdbc.queryForList("""
                SELECT 'chaos experiment: ' || COALESCE(fault_spec->>'type', 'unknown')
                       || ' (' || status || ')'
                FROM evaluation_run
                WHERE target_id = ? AND run_type = 'CHAOS' AND started_at >= ?
                ORDER BY started_at DESC LIMIT 5
                """, String.class, targetId, Timestamp.from(since)));

        return events;
    }

    /** The score a target held before the incident, for measuring how far it fell. */
    public Optional<Double> scoreBefore(UUID targetId, Instant before) {
        return jdbc.queryForList("""
                SELECT score FROM reliability_score_snapshot
                WHERE target_id = ? AND window_end < ?
                ORDER BY window_end DESC LIMIT 1
                """, Double.class, targetId, Timestamp.from(before)).stream().findFirst();
    }

    // ----------------------------------------------------- chaos ground truth

    /**
     * A completed chaos run and the target it actually broke.
     *
     * <p>This is the only source of incidents whose true cause is known rather than
     * believed, which makes it the only honest way to measure whether RCA works.
     */
    public record ChaosGroundTruth(UUID runId, UUID faultTargetId, UUID faultServiceId,
                                   String faultServiceName, String faultType,
                                   Instant startedAt, Instant endedAt) {
    }

    public List<ChaosGroundTruth> chaosGroundTruth(int limit) {
        return jdbc.query("""
                SELECT r.id, t.id AS fault_target_id, t.service_id, s.name,
                       r.fault_spec->>'type' AS fault_type, r.started_at, r.ended_at
                FROM evaluation_run r
                JOIN deployment_target t
                     ON t.id = COALESCE((r.fault_spec->>'faultTargetId')::uuid, r.target_id)
                JOIN service s ON s.id = t.service_id
                WHERE r.run_type = 'CHAOS' AND r.status IN ('COMPLETED','ABORTED')
                  AND r.ended_at IS NOT NULL
                  -- Only runs this platform actually executed. The ExperimentEngine
                  -- writes faultTargetId into every spec it creates; seeded demo rows
                  -- do not have it, and scoring RCA against a fault that was never
                  -- injected measures the fixture rather than the engine.
                  -- jsonb_exists rather than the ? operator: JDBC parses ? as a
                  -- bind placeholder and the statement fails at prepare time.
                  AND jsonb_exists(r.fault_spec, 'faultTargetId')
                ORDER BY r.started_at DESC LIMIT ?
                """, (rs, n) -> new ChaosGroundTruth(
                UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                UUID.fromString(rs.getString(3)), rs.getString(4), rs.getString(5),
                rs.getTimestamp(6).toInstant(), rs.getTimestamp(7).toInstant()), limit);
    }

    /** Targets whose score dipped during a given window — the candidates for a past incident. */
    public List<DegradedTarget> targetsDegradedDuring(Instant from, Instant to, double scoreBelow) {
        return jdbc.query("""
                SELECT DISTINCT t.id, t.service_id, s.name AS service_name, t.namespace,
                       c.name AS cluster_name, c.kubeconfig_ref, s.name AS workload,
                       min(snap.score) AS worst
                FROM reliability_score_snapshot snap
                JOIN deployment_target t ON t.id = snap.target_id
                JOIN service s ON s.id = t.service_id
                JOIN cluster c ON c.id = t.cluster_id
                WHERE snap.window_end BETWEEN ? AND ? AND snap.score < ?
                GROUP BY t.id, t.service_id, s.name, t.namespace, c.name, c.kubeconfig_ref
                """, (rs, n) -> new DegradedTarget(
                UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getDouble(8)),
                Timestamp.from(from), Timestamp.from(to), scoreBelow);
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise RCA payload", e);
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
