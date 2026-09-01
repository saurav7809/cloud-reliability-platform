package io.aegiscloud.controlplane.alerting;

import io.aegiscloud.controlplane.domain.Models;
import io.aegiscloud.controlplane.engine.ControlPlaneEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Raises alerts from burn rate (FR-39), and groups them under a diagnosed root cause
 * (FR-41).
 *
 * <p>Grouping is the half that matters operationally. When one service fails, every
 * service downstream of it breaches its own SLO and each breach raises its own alert —
 * so the moment the platform is most useful is exactly the moment it produces the most
 * noise. Attaching the symptom alerts to the incident that explains them turns five
 * pages into one.
 */
@Service
public class AlertingService {

    private static final Logger log = LoggerFactory.getLogger(AlertingService.class);

    private final JdbcTemplate jdbc;
    private final ControlPlaneEvents events;

    public AlertingService(JdbcTemplate jdbc, ControlPlaneEvents events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    public record AlertingReport(int evaluated, int raised, int resolved, int grouped,
                                 List<String> detail) {
    }

    @Scheduled(
            initialDelayString = "${aegiscloud.alerting.initial-delay-ms:30000}",
            fixedDelayString = "${aegiscloud.alerting.interval-ms:60000}")
    public void scheduledSweep() {
        try {
            AlertingReport report = sweep();
            if (report.raised() > 0 || report.resolved() > 0 || report.grouped() > 0) {
                log.info("alerting: {} raised, {} resolved, {} grouped under a root cause",
                        report.raised(), report.resolved(), report.grouped());
            }
        } catch (Exception e) {
            log.warn("alerting sweep failed: {}", e.getMessage(), e);
        }
    }

    /** One pass: raise what deserves raising, resolve what recovered, group the rest. */
    public AlertingReport sweep() {
        List<String> detail = new java.util.ArrayList<>();
        int raised = 0;
        int resolved = 0;

        List<BudgetState> states = currentBudgets();

        for (BudgetState state : states) {
            BurnRateAlerting.Verdict verdict = BurnRateAlerting.evaluate(
                    state.targetLabel(), state.sliType(), state.burnRate(),
                    state.budgetRemainingPct(), state.sampleCount());

            if (verdict.shouldAlert()) {
                if (raiseIfAbsent(state, verdict)) {
                    raised++;
                    detail.add("raised " + verdict.severity() + ": " + verdict.message());
                }
            } else {
                int closed = resolveOpenAlerts(state.sloId(), state.targetLabel());
                if (closed > 0) {
                    resolved += closed;
                    detail.add("resolved " + closed + " alert(s) for " + state.targetLabel()
                            + ": burn rate back within limits");
                }
            }
        }

        int grouped = groupUnderRootCause(detail);

        return new AlertingReport(states.size(), raised, resolved, grouped, detail);
    }

    private record BudgetState(UUID sloId, UUID targetId, String targetLabel,
                               Models.SliType sliType, double burnRate,
                               double budgetRemainingPct, int sampleCount) {
    }

    /**
     * The latest budget snapshot per SLO, with how many samples it rests on.
     *
     * <p>Only snapshots from the last hour count. A burn rate computed this morning
     * says nothing about now, and alerting on it would either page for a resolved
     * problem or stay silent through a new one.
     */
    private List<BudgetState> currentBudgets() {
        return jdbc.query("""
                SELECT s.id AS slo_id, t.id AS target_id,
                       sv.name || ' @ ' || c.name AS target_label,
                       s.sli_type, latest.burn_rate, latest.budget_remaining_pct,
                       (SELECT count(*) FROM metric_sample m
                         WHERE m.target_id = t.id
                           AND m.sampled_at > now() - make_interval(days => s.window_days)) AS samples
                FROM slo s
                JOIN deployment_target t ON t.id = s.target_id
                JOIN service sv ON sv.id = t.service_id
                JOIN cluster c ON c.id = t.cluster_id
                JOIN LATERAL (
                    SELECT burn_rate, budget_remaining_pct
                    FROM error_budget_snapshot b
                    WHERE b.slo_id = s.id AND b.computed_at > now() - INTERVAL '1 hour'
                    ORDER BY b.computed_at DESC LIMIT 1
                ) latest ON true
                WHERE s.is_active AND t.is_active
                """, (rs, i) -> new BudgetState(
                UUID.fromString(rs.getString("slo_id")),
                UUID.fromString(rs.getString("target_id")),
                rs.getString("target_label"),
                Models.SliType.valueOf(rs.getString("sli_type")),
                rs.getDouble("burn_rate"),
                rs.getDouble("budget_remaining_pct"),
                rs.getInt("samples")));
    }

    /**
     * Raises an alert unless one is already open for this SLO.
     *
     * <p>The guard is the whole difference between alerting and spamming: a burn rate
     * stays high for as long as the incident lasts, and a sweep every minute would
     * otherwise raise sixty alerts an hour for one problem.
     */
    private boolean raiseIfAbsent(BudgetState state, BurnRateAlerting.Verdict verdict) {
        Integer open = jdbc.queryForObject(
                "SELECT count(*) FROM alert WHERE slo_id = ? AND status <> 'RESOLVED'",
                Integer.class, state.sloId());

        if (open != null && open > 0) {
            return false;
        }

        jdbc.update("""
                INSERT INTO alert (target_id, slo_id, severity, status, message)
                VALUES (?, ?, ?, 'OPEN', ?)
                """, state.targetId(), state.sloId(), verdict.severity().name(), verdict.message());

        events.broadcast("alert", Map.of(
                "target", state.targetLabel(),
                "severity", verdict.severity().name(),
                "message", verdict.message()));

        return true;
    }

    /**
     * Closes alerts whose SLO has recovered.
     *
     * <p>Auto-resolving matters as much as raising. An alert that stays open after
     * the problem is gone teaches people that open alerts mean nothing, and then a
     * real one goes unread.
     */
    private int resolveOpenAlerts(UUID sloId, String targetLabel) {
        int closed = jdbc.update("""
                UPDATE alert SET status = 'RESOLVED', resolved_at = now()
                WHERE slo_id = ? AND status <> 'RESOLVED'
                """, sloId);

        if (closed > 0) {
            events.broadcast("alert", Map.of(
                    "target", targetLabel, "severity", "RESOLVED",
                    "message", "burn rate back within limits"));
        }
        return closed;
    }

    /**
     * Attaches open alerts to the incident that explains them (FR-41).
     *
     * <p>An alert is grouped when its target is one the incident's verdicts named —
     * either as the cause or as a symptom of it. That is a deliberately narrow rule:
     * grouping by time alone would sweep unrelated failures into one incident and
     * hide a second, real problem behind the first.
     */
    private int groupUnderRootCause(List<String> detail) {
        int grouped = jdbc.update("""
                UPDATE alert a
                SET incident_id = i.id
                FROM incident i
                WHERE a.incident_id IS NULL
                  AND a.status <> 'RESOLVED'
                  AND i.status <> 'RESOLVED'
                  AND a.opened_at >= i.started_at - INTERVAL '10 minutes'
                  AND EXISTS (
                      SELECT 1 FROM rca_verdict v
                      WHERE v.incident_id = i.id AND v.candidate_target_id = a.target_id
                  )
                """);

        if (grouped > 0) {
            detail.add(grouped + " alert(s) grouped under a diagnosed incident");
        }
        return grouped;
    }

    /** Open alerts belonging to one incident, for the incident view. */
    public List<Map<String, Object>> alertsForIncident(UUID incidentId) {
        return jdbc.query("""
                SELECT a.id::text, sv.name || ' @ ' || c.name AS target_label,
                       a.severity, a.status, a.message, a.opened_at
                FROM alert a
                JOIN deployment_target t ON t.id = a.target_id
                JOIN service sv ON sv.id = t.service_id
                JOIN cluster c ON c.id = t.cluster_id
                WHERE a.incident_id = ?
                ORDER BY a.opened_at
                """, (rs, i) -> Map.<String, Object>of(
                "id", rs.getString(1),
                "target", rs.getString(2),
                "severity", rs.getString(3),
                "status", rs.getString(4),
                "message", rs.getString(5),
                "openedAt", rs.getTimestamp(6).toInstant().toString()), incidentId);
    }
}
