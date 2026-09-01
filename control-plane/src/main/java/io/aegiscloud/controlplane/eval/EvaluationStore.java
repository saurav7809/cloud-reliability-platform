package io.aegiscloud.controlplane.eval;

import io.aegiscloud.controlplane.domain.Models;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything the Evaluation Engine reads and writes.
 *
 * <p>The write path here is what turns the dashboard from a set of fixtures into a
 * report of measurements: samples land in {@code metric_sample}, budgets in
 * {@code error_budget_snapshot}, scores in {@code reliability_score_snapshot}, and
 * the denormalised columns on {@code deployment_target} are refreshed from the same
 * numbers so the fleet table and the detail views cannot disagree.
 */
@Repository
public class EvaluationStore {

    private final JdbcTemplate jdbc;

    public EvaluationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --------------------------------------------------------------- endpoints

    /** A registered endpoint with everything needed to probe it. */
    public record ProbeEndpoint(
            UUID endpointId,
            UUID targetId,
            UUID serviceId,
            String serviceName,
            String clusterName,
            String kubeContext,
            String namespace,
            String protocol,
            String address,
            int timeoutMs,
            Integer expectedStatusCode) {

        public String label() {
            return serviceName + " @ " + clusterName + "/" + namespace;
        }
    }

    /**
     * Endpoints worth probing: active, on an active target, on a cluster the platform
     * believes it can reach.
     */
    public List<ProbeEndpoint> activeEndpoints() {
        return jdbc.query("""
                SELECT e.id AS endpoint_id, t.id AS target_id, s.id AS service_id,
                       s.name AS service_name, c.name AS cluster_name,
                       c.kubeconfig_ref AS kube_context, t.namespace,
                       e.protocol, e.address, e.timeout_ms, e.expected_status_code
                FROM endpoint e
                JOIN deployment_target t ON t.id = e.target_id
                JOIN service s ON s.id = t.service_id
                JOIN cluster c ON c.id = t.cluster_id
                WHERE e.is_active AND t.is_active AND c.is_active
                  AND c.status <> 'UNREACHABLE'
                ORDER BY s.name
                """, (rs, i) -> new ProbeEndpoint(
                UUID.fromString(rs.getString("endpoint_id")),
                UUID.fromString(rs.getString("target_id")),
                UUID.fromString(rs.getString("service_id")),
                rs.getString("service_name"),
                rs.getString("cluster_name"),
                rs.getString("kube_context"),
                rs.getString("namespace"),
                rs.getString("protocol"),
                rs.getString("address"),
                rs.getInt("timeout_ms"),
                rs.getObject("expected_status_code") == null
                        ? null : rs.getInt("expected_status_code")));
    }

    /**
     * Registers an endpoint, or updates the one already at that address.
     *
     * <p>Idempotent on (target, address). Registering a service twice used to add a
     * second endpoint pointing at the same URL, which doubled the probe rate against
     * the workload and made availability a function of how many times somebody
     * pressed Register.
     */
    public UUID addEndpoint(UUID targetId, String protocol, String address, int probeIntervalSeconds,
                            int timeoutMs, Integer expectedStatusCode) {
        return jdbc.queryForObject("""
                INSERT INTO endpoint (target_id, protocol, address, probe_interval_seconds,
                                      timeout_ms, expected_status_code)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (target_id, address) DO UPDATE SET
                    protocol = EXCLUDED.protocol,
                    probe_interval_seconds = EXCLUDED.probe_interval_seconds,
                    timeout_ms = EXCLUDED.timeout_ms,
                    expected_status_code = EXCLUDED.expected_status_code,
                    is_active = true
                RETURNING id
                """, UUID.class, targetId, protocol, address, probeIntervalSeconds,
                timeoutMs, expectedStatusCode);
    }

    public List<ProbeEndpoint> endpointsFor(UUID targetId) {
        return activeEndpoints().stream().filter(e -> e.targetId().equals(targetId)).toList();
    }

    // ----------------------------------------------------------------- samples

    /**
     * Records one probe as two samples: whether it worked, and how long it took.
     *
     * <p>Two rows rather than one because they answer different questions and feed
     * different SLOs. A failed probe still has a latency - the time until it failed -
     * but that number must not enter a latency percentile, so only successful probes
     * contribute a LATENCY_MS row.
     */
    public long recordProbe(UUID targetId, UUID endpointId, boolean success, double latencyMs) {
        long availabilityId = jdbc.queryForObject("""
                INSERT INTO metric_sample (target_id, endpoint_id, source, metric_type, value, success)
                VALUES (?, ?, 'PROBE', 'AVAILABILITY', ?, ?) RETURNING id
                """, Long.class, targetId, endpointId, success ? 100.0 : 0.0, success);

        if (success) {
            jdbc.update("""
                    INSERT INTO metric_sample (target_id, endpoint_id, source, metric_type, value, success)
                    VALUES (?, ?, 'PROBE', 'LATENCY_MS', ?, true)
                    """, targetId, endpointId, latencyMs);
        }

        return availabilityId;
    }

    /**
     * Samples inside a window measured in minutes.
     *
     * <p>Separate from the day-based query because the two windows answer different
     * questions and must not share one. An error budget is deliberately long-memoried
     * — a budget that forgets last week is not a budget. A reliability score used for
     * incident detection has to reflect now: over a day of history, a service that
     * went down five minutes ago still scores in the nineties, because most of the
     * day's probes succeeded. Detection would take hours.
     */
    public List<SloEvaluator.Sample> samplesWithin(UUID targetId, String metricType, int minutes) {
        return jdbc.query("""
                SELECT value, COALESCE(success, true) AS success
                FROM metric_sample
                WHERE target_id = ? AND metric_type = ?
                  AND sampled_at > now() - make_interval(mins => ?)
                ORDER BY sampled_at
                """, (rs, i) -> new SloEvaluator.Sample(rs.getDouble("value"), rs.getBoolean("success")),
                targetId, metricType, minutes);
    }

    /** Samples of one metric type for a target inside a window, oldest first. */
    public List<SloEvaluator.Sample> samples(UUID targetId, String metricType, int windowDays) {
        return jdbc.query("""
                SELECT value, COALESCE(success, true) AS success
                FROM metric_sample
                WHERE target_id = ? AND metric_type = ?
                  AND sampled_at > now() - make_interval(days => ?)
                ORDER BY sampled_at
                """, (rs, i) -> new SloEvaluator.Sample(rs.getDouble("value"), rs.getBoolean("success")),
                targetId, metricType, windowDays);
    }

    // -------------------------------------------------------------------- SLOs

    public record TargetSlo(UUID sloId, UUID targetId, Models.SliType sliType,
                            double objectiveValue, int windowDays) {
    }

    public List<TargetSlo> activeSlos() {
        return jdbc.query("""
                SELECT s.id, s.target_id, s.sli_type, s.objective_value, s.window_days
                FROM slo s
                JOIN deployment_target t ON t.id = s.target_id
                WHERE s.is_active AND t.is_active
                """, (rs, i) -> new TargetSlo(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("target_id")),
                Models.SliType.valueOf(rs.getString("sli_type")),
                rs.getDouble("objective_value"),
                rs.getInt("window_days")));
    }

    /**
     * Sets an objective, replacing any existing one of the same type.
     *
     * <p>One objective per SLI type per target: two availability objectives on one
     * service is not a stricter promise, it is two answers to the same question, and
     * the alerting sweep would evaluate both.
     */
    public UUID addSlo(UUID targetId, Models.SliType sliType, double objectiveValue, int windowDays) {
        return jdbc.queryForObject("""
                INSERT INTO slo (target_id, sli_type, objective_value, window_days)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (target_id, sli_type) DO UPDATE SET
                    objective_value = EXCLUDED.objective_value,
                    window_days = EXCLUDED.window_days,
                    is_active = true
                RETURNING id
                """, UUID.class, targetId, sliType.name(), objectiveValue, windowDays);
    }

    /**
     * Records an evaluated budget.
     *
     * <p>An infinite burn rate - what a zero-tolerance objective produces the moment
     * anything fails - is stored as a large finite number, because the column is a
     * double and infinity is not something a dashboard can render or a query can
     * order by.
     */
    public void recordBudget(UUID sloId, double currentValue, double budgetRemainingPct,
                             double burnRate) {
        double storable = Double.isFinite(burnRate) ? burnRate : 9999.0;
        jdbc.update("""
                INSERT INTO error_budget_snapshot (slo_id, current_value, budget_remaining_pct, burn_rate)
                VALUES (?, ?, ?, ?)
                """, sloId, currentValue, budgetRemainingPct, storable);
    }

    // ------------------------------------------------------------------ scores

    public void recordScore(UUID targetId, Instant windowStart, Instant windowEnd, double score) {
        jdbc.update("""
                INSERT INTO reliability_score_snapshot (target_id, window_start, window_end, score)
                VALUES (?, ?, ?, ?)
                """, targetId, java.sql.Timestamp.from(windowStart),
                java.sql.Timestamp.from(windowEnd), score);
    }

    /**
     * Refreshes the denormalised readings the fleet table renders.
     *
     * <p>These columns are a cache of what the samples say. Writing them here, in the
     * same transaction that computed them, is what stops the summary view and the
     * detail view from telling an operator two different stories.
     */
    public void refreshTargetReadings(UUID targetId, double score, double availabilityPct,
                                      double latencyP95Ms, double errorRatePct) {
        jdbc.update("""
                UPDATE deployment_target
                SET reliability_score = ?, availability_pct = ?, latency_p95_ms = ?, error_rate_pct = ?
                WHERE id = ?
                """, score, availabilityPct, latencyP95Ms, errorRatePct, targetId);
    }

    public record ScorePoint(Instant at, double score) {
    }

    /** Score history for a target, oldest first — the trend FR-21 asks for. */
    public List<ScorePoint> scoreHistory(UUID targetId, int limit) {
        return jdbc.query("""
                SELECT window_end, score FROM (
                    SELECT window_end, score FROM reliability_score_snapshot
                    WHERE target_id = ? ORDER BY window_end DESC LIMIT ?
                ) recent ORDER BY window_end
                """, (rs, i) -> new ScorePoint(rs.getTimestamp("window_end").toInstant(),
                rs.getDouble("score")), targetId, limit);
    }

    // ---------------------------------------------------------- evaluation run

    /** Opens an evaluation run and returns its id. */
    public UUID startRun(UUID serviceId, UUID targetId, String runType) {
        return jdbc.queryForObject("""
                INSERT INTO evaluation_run (service_id, target_id, run_type)
                VALUES (?, ?, ?) RETURNING id
                """, UUID.class, serviceId, targetId, runType);
    }

    public void linkSample(UUID runId, long metricSampleId, String phase) {
        jdbc.update("""
                INSERT INTO evaluation_run_metric (evaluation_run_id, metric_sample_id, phase)
                VALUES (?, ?, ?)
                """, runId, metricSampleId, phase);
    }

    public void finishRun(UUID runId, String status, Double scoreAfter) {
        jdbc.update("""
                UPDATE evaluation_run SET status = ?, score_after = ?, ended_at = now()
                WHERE id = ?
                """, status, scoreAfter, runId);
    }

    /** The target a probe belongs to, for the run record. */
    public Optional<UUID> serviceIdForTarget(UUID targetId) {
        return jdbc.queryForList("SELECT service_id FROM deployment_target WHERE id = ?",
                        UUID.class, targetId).stream().findFirst();
    }
}
