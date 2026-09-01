package io.aegiscloud.controlplane.optimize;

import io.aegiscloud.controlplane.domain.Models;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Cost and performance recommendations (FR-31 to FR-34).
 *
 * <p>Pure, like the other engines that have to be right. Every recommendation is a
 * function of measurements passed in, so the advice can be tested against situations
 * that are awkward to reproduce on a cluster — a service with a burning error budget,
 * one with no measurements at all, one that is both over-provisioned and unreliable.
 *
 * <p><b>The rule that shapes everything here (FR-33, UC-7).</b> Reliability is not
 * currency. A recommendation that would breach an existing SLO is never surfaced as
 * safe, and where the error budget is already burning the advisor withholds the
 * saving entirely rather than presenting it with a warning attached. Warnings get
 * skimmed; an absent recommendation cannot be applied by accident. The saving is
 * still calculated and stated in the rationale, so nothing is hidden — it is simply
 * not offered as a thing to do.
 */
public final class OptimizationAdvisor {

    /**
     * Utilisation below which a workload is considered over-provisioned.
     *
     * <p>Lower than the scaling engine's scale-down floor of 30%. The two make
     * different commitments: the scaler removes one replica and watches, while a
     * recommendation asks a human to accept a standing change. The advisor should not
     * be nagging about workloads the scaler is already handling.
     */
    static final double OVER_PROVISIONED_UTILISATION = 20.0;

    /** Utilisation above which a workload is starved rather than merely busy. */
    static final double UNDER_PROVISIONED_UTILISATION = 85.0;

    /** Error budget below this fraction means reliability is already under strain. */
    static final double BUDGET_AT_RISK_PCT = 25.0;

    private OptimizationAdvisor() {
    }

    /**
     * What the advisor knows about one target.
     *
     * @param cpuUtilizationPct  absent when nothing has measured it, in which case no
     *                           resource advice is given rather than guessed
     * @param budgetRemainingPct the tightest error budget across the target's SLOs,
     *                           absent when it has none
     * @param monthlyCostUsd     current spend, used to size the estimated saving
     */
    public record TargetFacts(
            String targetId,
            String serviceName,
            String clusterName,
            int replicas,
            OptionalDouble cpuUtilizationPct,
            OptionalDouble reliabilityScore,
            OptionalDouble budgetRemainingPct,
            double monthlyCostUsd,
            Models.ScalingStrategy scalingStrategy,
            boolean hasLatencySlo,
            int sampleCount) {
    }

    /** How much reliability a change puts at risk. */
    public enum ReliabilityImpact {
        NONE, LOW, MEDIUM, HIGH
    }

    /**
     * @param safeToApply false when the change would trade reliability the target
     *                    cannot currently spare. Such a recommendation is still
     *                    recorded and shown, but never as advice to act on
     */
    public record Recommendation(
            String targetId,
            String kind,
            String title,
            String rationale,
            Map<String, Object> evidence,
            double estimatedMonthlySavingUsd,
            ReliabilityImpact reliabilityImpact,
            boolean safeToApply,
            Integer proposedReplicas) {
    }

    /** All advice for one target. */
    public static List<Recommendation> advise(TargetFacts facts) {
        List<Recommendation> recommendations = new ArrayList<>();

        overProvisionedReplicas(facts).ifPresent(recommendations::add);
        starvedWorkload(facts).ifPresent(recommendations::add);
        scalingStrategyMismatch(facts).ifPresent(recommendations::add);
        unmeasuredWorkload(facts).ifPresent(recommendations::add);

        return recommendations;
    }

    /**
     * The classic saving: a workload sized for traffic it does not have.
     *
     * <p>Requires a measurement. Recommending a replica reduction from an assumption
     * is how a cost tool causes an outage.
     */
    private static java.util.Optional<Recommendation> overProvisionedReplicas(TargetFacts facts) {
        if (facts.cpuUtilizationPct().isEmpty() || facts.replicas() < 2) {
            return java.util.Optional.empty();
        }

        double utilisation = facts.cpuUtilizationPct().getAsDouble();
        if (utilisation >= OVER_PROVISIONED_UTILISATION) {
            return java.util.Optional.empty();
        }

        int proposed = Math.max(1, facts.replicas() - 1);
        double perReplicaCost = facts.monthlyCostUsd() / Math.max(1, facts.replicas());
        double saving = perReplicaCost * (facts.replicas() - proposed);

        boolean budgetAtRisk = facts.budgetRemainingPct().isPresent()
                && facts.budgetRemainingPct().getAsDouble() < BUDGET_AT_RISK_PCT;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("cpuUtilizationPct", round(utilisation));
        evidence.put("replicas", facts.replicas());
        evidence.put("proposedReplicas", proposed);
        evidence.put("samplesBehindReading", facts.sampleCount());
        facts.budgetRemainingPct().ifPresent(b -> evidence.put("errorBudgetRemainingPct", round(b)));

        String rationale = String.format(
                "CPU sits at %.1f%% of request across %d replicas. Dropping to %d would save about "
                        + "$%.2f a month.", utilisation, facts.replicas(), proposed, saving);

        if (budgetAtRisk) {
            // FR-33 and UC-7: the saving is real, and it is still the wrong thing to
            // do while the service is already spending its error budget.
            return java.util.Optional.of(new Recommendation(facts.targetId(),
                    "REPLICA_REDUCTION",
                    "Not safe to reduce replicas on " + facts.serviceName() + " yet",
                    rationale + " Withheld: only "
                            + String.format("%.1f%%", facts.budgetRemainingPct().getAsDouble())
                            + " of the error budget remains, so removing capacity now would spend "
                            + "reliability the service cannot currently spare.",
                    evidence, saving, ReliabilityImpact.HIGH, false, proposed));
        }

        return java.util.Optional.of(new Recommendation(facts.targetId(),
                "REPLICA_REDUCTION",
                "Reduce " + facts.serviceName() + " from " + facts.replicas()
                        + " to " + proposed + " replicas",
                rationale + " One replica is removed at a time so the effect can be observed"
                        + " before going further.",
                evidence, saving,
                // Never NONE. Removing capacity always costs some headroom, and a
                // recommendation claiming otherwise would be selling certainty it
                // does not have.
                facts.replicas() - proposed == 1 ? ReliabilityImpact.LOW : ReliabilityImpact.MEDIUM,
                true, proposed));
    }

    /** A workload being throttled: the performance half of FR-32. */
    private static java.util.Optional<Recommendation> starvedWorkload(TargetFacts facts) {
        if (facts.cpuUtilizationPct().isEmpty()) {
            return java.util.Optional.empty();
        }

        double utilisation = facts.cpuUtilizationPct().getAsDouble();
        if (utilisation < UNDER_PROVISIONED_UTILISATION) {
            return java.util.Optional.empty();
        }

        int proposed = facts.replicas() + 1;
        double perReplicaCost = facts.monthlyCostUsd() / Math.max(1, facts.replicas());

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("cpuUtilizationPct", round(utilisation));
        evidence.put("replicas", facts.replicas());
        evidence.put("proposedReplicas", proposed);

        return java.util.Optional.of(new Recommendation(facts.targetId(),
                "CAPACITY_INCREASE",
                "Add capacity to " + facts.serviceName(),
                String.format("CPU is at %.1f%% of request, above the %.0f%% mark where a workload "
                                + "is being throttled rather than merely busy. Adding a replica costs "
                                + "about $%.2f a month and buys headroom before latency starts to "
                                + "suffer.", utilisation, UNDER_PROVISIONED_UTILISATION, perReplicaCost),
                evidence,
                // A cost increase, expressed as a negative saving so the ledger adds up.
                -perReplicaCost,
                ReliabilityImpact.NONE, true, proposed));
    }

    /**
     * A scaling strategy that cannot fire.
     *
     * <p>Worth surfacing because it is invisible otherwise: a target set to LATENCY
     * with no latency SLO is not autoscaled at all, and nothing about the
     * configuration says so.
     */
    private static java.util.Optional<Recommendation> scalingStrategyMismatch(TargetFacts facts) {
        boolean latencyWithoutSlo =
                (facts.scalingStrategy() == Models.ScalingStrategy.LATENCY
                        || facts.scalingStrategy() == Models.ScalingStrategy.TREND)
                        && !facts.hasLatencySlo();

        if (!latencyWithoutSlo) {
            return java.util.Optional.empty();
        }

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("scalingStrategy", facts.scalingStrategy().name());
        evidence.put("hasLatencySlo", false);

        return java.util.Optional.of(new Recommendation(facts.targetId(),
                "SCALING_STRATEGY",
                facts.serviceName() + " is set to " + facts.scalingStrategy()
                        + " scaling but has no latency SLO",
                "The " + facts.scalingStrategy() + " strategy compares observed latency against a "
                        + "latency SLO. Without one it can never act, so this target is effectively "
                        + "not autoscaled. Either define a latency SLO or switch the strategy to CPU.",
                evidence, 0, ReliabilityImpact.MEDIUM, false, null));
    }

    /**
     * A managed target nobody is measuring.
     *
     * <p>Not a cost or performance finding, and included anyway: an unmeasured target
     * is the precondition for every other piece of advice here being unavailable, and
     * it is the most actionable thing on the list.
     */
    private static java.util.Optional<Recommendation> unmeasuredWorkload(TargetFacts facts) {
        if (facts.sampleCount() > 0 || facts.reliabilityScore().isPresent()) {
            return java.util.Optional.empty();
        }

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("sampleCount", 0);
        evidence.put("monthlyCostUsd", round(facts.monthlyCostUsd()));

        return java.util.Optional.of(new Recommendation(facts.targetId(),
                "OBSERVABILITY_GAP",
                "Nothing is measuring " + facts.serviceName() + " on " + facts.clusterName(),
                String.format("This target has no probe samples, so its reliability is unknown and "
                        + "no cost or capacity advice can be given about it. It is costing about "
                        + "$%.2f a month either way. Register an endpoint so it can be evaluated.",
                        facts.monthlyCostUsd()),
                evidence, 0, ReliabilityImpact.NONE, false, null));
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
