package io.aegiscloud.controlplane.engine;

import io.aegiscloud.controlplane.domain.Models;

import java.time.Duration;
import java.util.List;
import java.util.OptionalDouble;

/**
 * The auto-scaling decision, as a pure function of what was observed.
 *
 * <p>Nothing in this class reads a database or touches a cluster. That is what makes
 * the decision testable: every rule below, including the flapping guard, is exercised
 * by unit tests with no Kubernetes anywhere. {@link ReconciliationLoop} supplies the
 * readings and carries out whatever comes back.
 *
 * <p>The rules are deliberately asymmetric — quick to add capacity, slow and
 * single-stepped to remove it. Under-provisioning is a user-visible outage while
 * over-provisioning is a bill, so the two errors are not worth the same.
 */
public final class ScalingEngine {

    /** Utilisation the CPU strategy sizes towards, as a percentage of the pod's request. */
    static final double CPU_TARGET_PCT = 60.0;

    /** Above this, capacity is added. The gap from the target is the flapping deadband. */
    static final double CPU_SCALE_UP_PCT = 75.0;

    /** Below this, one replica is removed. */
    static final double CPU_SCALE_DOWN_PCT = 30.0;

    /** Latency below this fraction of the objective means the workload is oversized. */
    static final double LATENCY_SLACK_FRACTION = 0.5;

    /** Samples the TREND strategy projects ahead before deciding. */
    static final int TREND_PROJECTION_STEPS = 3;

    private ScalingEngine() {
    }

    /**
     * What the engine has to work with.
     *
     * @param cpuUtilizationPct  live utilisation, absent when metrics-server or a CPU
     *                           request is missing
     * @param latencyP95Ms       the most recent p95 reading, absent when none exists
     * @param latencyObjectiveMs the target's SLO, absent when it has no latency SLO
     * @param latencyTrend       recent p95 samples, oldest first
     */
    public record Signals(
            OptionalDouble cpuUtilizationPct,
            OptionalDouble latencyP95Ms,
            OptionalDouble latencyObjectiveMs,
            List<Double> latencyTrend) {
    }

    /**
     * @param act           whether a replica change should be made at all
     * @param triggerMetric the metric named in the resulting scaling_event row
     * @param reason        the sentence shown in the action ledger, including for
     *                      the no-action cases — "why did nothing happen" is the
     *                      question an operator asks most
     */
    public record Decision(
            boolean act,
            int fromReplicas,
            int toReplicas,
            String triggerMetric,
            double triggerValue,
            String reason) {

        static Decision hold(int current, String reason) {
            return new Decision(false, current, current, "none", 0, reason);
        }

        public ActionType actionType() {
            return toReplicas > fromReplicas ? ActionType.SCALE_UP : ActionType.SCALE_DOWN;
        }
    }

    /**
     * Decides the replica count for one target.
     *
     * @param sinceLastChange time since this target was last scaled, or null when it
     *                        never has been
     */
    public static Decision decide(Models.ScalingStrategy strategy, Signals signals, int currentReplicas,
                                  int maxReplicas, Duration sinceLastChange, Duration cooldown) {

        Decision proposal = propose(strategy, signals, currentReplicas, maxReplicas);

        if (!proposal.act()) {
            return proposal;
        }

        // The flapping guard is applied after the proposal rather than before it, so
        // the ledger records what the engine wanted to do and why it was held back,
        // instead of a silent gap in the history.
        if (sinceLastChange != null && sinceLastChange.compareTo(cooldown) < 0) {
            long remaining = cooldown.minus(sinceLastChange).toSeconds();
            return Decision.hold(currentReplicas, proposal.reason()
                    + " — held: last scaled " + sinceLastChange.toSeconds() + "s ago, "
                    + remaining + "s of the " + cooldown.toSeconds() + "s cooldown remain");
        }

        return proposal;
    }

    private static Decision propose(Models.ScalingStrategy strategy, Signals signals,
                                    int current, int maxReplicas) {
        return switch (strategy) {
            case NONE -> Decision.hold(current, "scaling strategy is NONE; this target is not autoscaled");
            case CPU -> byCpu(signals, current, maxReplicas);
            case LATENCY -> byLatency(signals, current, maxReplicas);
            case TREND -> byTrend(signals, current, maxReplicas);
        };
    }

    private static Decision byCpu(Signals signals, int current, int maxReplicas) {
        if (signals.cpuUtilizationPct().isEmpty()) {
            return Decision.hold(current, "CPU strategy has no reading: metrics-server is absent "
                    + "or the containers declare no CPU request, so utilisation is undefined");
        }
        double utilization = signals.cpuUtilizationPct().getAsDouble();

        if (utilization > CPU_SCALE_UP_PCT) {
            // Size for the target utilisation in one move: doubling load twice while
            // adding one replica at a time is how an overloaded service stays
            // overloaded through several cycles.
            int wanted = (int) Math.ceil(current * utilization / CPU_TARGET_PCT);
            int next = Math.min(Math.max(wanted, current + 1), maxReplicas);
            if (next == current) {
                return Decision.hold(current, String.format(
                        "CPU at %.1f%% but already at the policy maximum of %d replicas",
                        utilization, maxReplicas));
            }
            return new Decision(true, current, next, "cpu", utilization, String.format(
                    "CPU at %.1f%% of request, above the %.0f%% ceiling; sizing for %.0f%% needs %d replicas",
                    utilization, CPU_SCALE_UP_PCT, CPU_TARGET_PCT, next));
        }

        if (utilization < CPU_SCALE_DOWN_PCT) {
            if (current <= 1) {
                // Saying "within the band" here would be untrue, and the ledger is
                // only useful when its no-action reasons are accurate.
                return Decision.hold(current, String.format(
                        "CPU at %.1f%% is below the %.0f%% floor, but the target is already at "
                                + "1 replica, which autonomous scaling never goes below",
                        utilization, CPU_SCALE_DOWN_PCT));
            }
            return new Decision(true, current, current - 1, "cpu", utilization, String.format(
                    "CPU at %.1f%% of request, below the %.0f%% floor; removing one replica",
                    utilization, CPU_SCALE_DOWN_PCT));
        }

        return Decision.hold(current, String.format(
                "CPU at %.1f%% is within the %.0f-%.0f%% band; no change",
                utilization, CPU_SCALE_DOWN_PCT, CPU_SCALE_UP_PCT));
    }

    private static Decision byLatency(Signals signals, int current, int maxReplicas) {
        if (signals.latencyObjectiveMs().isEmpty()) {
            return Decision.hold(current, "LATENCY strategy has no SLO to compare against; "
                    + "define a latency SLO for this target before it can be scaled on latency");
        }
        if (signals.latencyP95Ms().isEmpty()) {
            return Decision.hold(current, "LATENCY strategy has no p95 reading for this target yet");
        }

        double objective = signals.latencyObjectiveMs().getAsDouble();
        double observed = signals.latencyP95Ms().getAsDouble();

        if (observed > objective) {
            if (current >= maxReplicas) {
                return Decision.hold(current, String.format(
                        "p95 %.0fms exceeds the %.0fms objective but the target is already at the "
                                + "policy maximum of %d replicas", observed, objective, maxReplicas));
            }
            return new Decision(true, current, current + 1, "latency", observed, String.format(
                    "p95 %.0fms exceeds the %.0fms objective; adding one replica",
                    observed, objective));
        }

        if (observed < objective * LATENCY_SLACK_FRACTION && current > 1) {
            return new Decision(true, current, current - 1, "latency", observed, String.format(
                    "p95 %.0fms is under half the %.0fms objective; removing one replica",
                    observed, objective));
        }

        return Decision.hold(current, String.format(
                "p95 %.0fms is within the %.0fms objective; no change", observed, objective));
    }

    /**
     * Scales on where latency is heading rather than where it is.
     *
     * <p>A least-squares fit over the recent series, projected a few samples forward.
     * This is the one strategy that can add capacity before an SLO is breached, which
     * is also why it is the one most able to be wrong — so it acts only on a rising
     * line, never on a single reading.
     */
    private static Decision byTrend(Signals signals, int current, int maxReplicas) {
        if (signals.latencyObjectiveMs().isEmpty()) {
            return Decision.hold(current, "TREND strategy has no SLO to project against");
        }
        List<Double> series = signals.latencyTrend();
        if (series.size() < 3) {
            return Decision.hold(current, "TREND strategy needs at least 3 samples; have "
                    + series.size());
        }

        double slope = slope(series);
        double last = series.get(series.size() - 1);
        double projected = last + slope * TREND_PROJECTION_STEPS;
        double objective = signals.latencyObjectiveMs().getAsDouble();

        if (slope > 0 && projected > objective) {
            if (current >= maxReplicas) {
                return Decision.hold(current, String.format(
                        "p95 trending to %.0fms against a %.0fms objective, but already at the "
                                + "policy maximum of %d replicas", projected, objective, maxReplicas));
            }
            return new Decision(true, current, current + 1, "trend", projected, String.format(
                    "p95 rising %.1fms per sample, projected to %.0fms in %d samples against a "
                            + "%.0fms objective; adding one replica ahead of the breach",
                    slope, projected, TREND_PROJECTION_STEPS, objective));
        }

        if (slope <= 0 && projected < objective * LATENCY_SLACK_FRACTION && current > 1) {
            return new Decision(true, current, current - 1, "trend", projected, String.format(
                    "p95 falling to a projected %.0fms, under half the %.0fms objective; "
                            + "removing one replica", projected, objective));
        }

        return Decision.hold(current, String.format(
                "p95 projected to %.0fms against a %.0fms objective; no change",
                projected, objective));
    }

    /** Least-squares slope of the series against its sample index. */
    static double slope(List<Double> series) {
        int n = series.size();
        double meanX = (n - 1) / 2.0;
        double meanY = series.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        double covariance = 0;
        double variance = 0;
        for (int i = 0; i < n; i++) {
            double dx = i - meanX;
            covariance += dx * (series.get(i) - meanY);
            variance += dx * dx;
        }
        return variance == 0 ? 0 : covariance / variance;
    }
}
