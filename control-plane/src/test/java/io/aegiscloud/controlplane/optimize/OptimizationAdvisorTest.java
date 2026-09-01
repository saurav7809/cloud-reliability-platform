package io.aegiscloud.controlplane.optimize;

import io.aegiscloud.controlplane.domain.Models;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cost and performance advice, and the line it must not cross.
 *
 * <p>The tests that matter most here are the ones about refusing to recommend a
 * saving. A cost tool that is occasionally too cautious is annoying; one that trades
 * away reliability quietly is how a platform loses the trust it needs to act at all.
 */
class OptimizationAdvisorTest {

    private static OptimizationAdvisor.TargetFacts facts(int replicas, Double utilisation,
                                                         Double budgetRemaining, double cost,
                                                         Models.ScalingStrategy strategy,
                                                         boolean hasLatencySlo, int samples) {
        return new OptimizationAdvisor.TargetFacts(
                "target-1", "checkout", "prod-eks",
                replicas,
                utilisation == null ? OptionalDouble.empty() : OptionalDouble.of(utilisation),
                samples > 0 ? OptionalDouble.of(92) : OptionalDouble.empty(),
                budgetRemaining == null ? OptionalDouble.empty() : OptionalDouble.of(budgetRemaining),
                cost, strategy, hasLatencySlo, samples);
    }

    private static OptimizationAdvisor.Recommendation only(String kind,
                                                           List<OptimizationAdvisor.Recommendation> all) {
        return all.stream().filter(r -> r.kind().equals(kind)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("an idle over-provisioned workload gets a replica reduction with a costed saving")
    void overProvisionedWorkloadIsFlagged() {
        List<OptimizationAdvisor.Recommendation> advice = OptimizationAdvisor.advise(
                facts(4, 8.0, 90.0, 400, Models.ScalingStrategy.CPU, true, 120));

        OptimizationAdvisor.Recommendation recommendation = only("REPLICA_REDUCTION", advice);

        assertThat(recommendation.safeToApply()).isTrue();
        assertThat(recommendation.proposedReplicas()).isEqualTo(3);
        assertThat(recommendation.estimatedMonthlySavingUsd()).isEqualTo(100.0);
        assertThat(recommendation.title()).contains("4 to 3 replicas");
    }

    @Test
    @DisplayName("the same saving is withheld when the error budget is already burning")
    void savingIsWithheldWhenReliabilityIsAtRisk() {
        List<OptimizationAdvisor.Recommendation> advice = OptimizationAdvisor.advise(
                facts(4, 8.0, 5.0, 400, Models.ScalingStrategy.CPU, true, 120));

        OptimizationAdvisor.Recommendation recommendation = only("REPLICA_REDUCTION", advice);

        assertThat(recommendation.safeToApply()).isFalse();
        assertThat(recommendation.reliabilityImpact())
                .isEqualTo(OptimizationAdvisor.ReliabilityImpact.HIGH);
        assertThat(recommendation.title()).startsWith("Not safe");
        // The saving is still stated. Nothing is hidden - it is simply not offered.
        assertThat(recommendation.estimatedMonthlySavingUsd()).isEqualTo(100.0);
        assertThat(recommendation.rationale()).contains("error budget");
    }

    @Test
    @DisplayName("no replica advice without a utilisation measurement")
    void noAdviceWithoutMeasurement() {
        List<OptimizationAdvisor.Recommendation> advice = OptimizationAdvisor.advise(
                facts(6, null, 90.0, 600, Models.ScalingStrategy.CPU, true, 50));

        assertThat(advice).noneMatch(r -> r.kind().equals("REPLICA_REDUCTION"));
    }

    @Test
    @DisplayName("a single-replica workload is never recommended down")
    void singleReplicaIsLeftAlone() {
        List<OptimizationAdvisor.Recommendation> advice = OptimizationAdvisor.advise(
                facts(1, 2.0, 100.0, 100, Models.ScalingStrategy.CPU, true, 120));

        assertThat(advice).noneMatch(r -> r.kind().equals("REPLICA_REDUCTION"));
    }

    @Test
    @DisplayName("removing capacity never claims zero reliability impact")
    void capacityRemovalAlwaysCostsSomething() {
        OptimizationAdvisor.Recommendation recommendation = only("REPLICA_REDUCTION",
                OptimizationAdvisor.advise(
                        facts(3, 5.0, 100.0, 300, Models.ScalingStrategy.CPU, true, 200)));

        assertThat(recommendation.reliabilityImpact())
                .isNotEqualTo(OptimizationAdvisor.ReliabilityImpact.NONE);
    }

    @Test
    @DisplayName("a workload in the normal band gets no resource advice either way")
    void healthyUtilisationProducesNoResourceAdvice() {
        List<OptimizationAdvisor.Recommendation> advice = OptimizationAdvisor.advise(
                facts(3, 55.0, 90.0, 300, Models.ScalingStrategy.CPU, true, 200));

        assertThat(advice).noneMatch(r -> r.kind().equals("REPLICA_REDUCTION"));
        assertThat(advice).noneMatch(r -> r.kind().equals("CAPACITY_INCREASE"));
    }

    @Test
    @DisplayName("a starved workload is told to grow, and the cost is stated as negative saving")
    void starvedWorkloadGetsCapacityAdvice() {
        OptimizationAdvisor.Recommendation recommendation = only("CAPACITY_INCREASE",
                OptimizationAdvisor.advise(
                        facts(2, 94.0, 80.0, 200, Models.ScalingStrategy.CPU, true, 200)));

        assertThat(recommendation.proposedReplicas()).isEqualTo(3);
        assertThat(recommendation.estimatedMonthlySavingUsd()).isNegative();
        assertThat(recommendation.safeToApply()).isTrue();
    }

    @Test
    @DisplayName("a latency strategy with no latency SLO is reported as unable to fire")
    void scalingStrategyMismatchIsSurfaced() {
        OptimizationAdvisor.Recommendation recommendation = only("SCALING_STRATEGY",
                OptimizationAdvisor.advise(
                        facts(3, 40.0, 90.0, 300, Models.ScalingStrategy.LATENCY, false, 200)));

        assertThat(recommendation.rationale()).contains("can never act");
        assertThat(recommendation.safeToApply()).isFalse();
        assertThat(recommendation.proposedReplicas()).isNull();
    }

    @Test
    @DisplayName("a latency strategy with an SLO behind it is not flagged")
    void configuredLatencyStrategyIsFine() {
        List<OptimizationAdvisor.Recommendation> advice = OptimizationAdvisor.advise(
                facts(3, 40.0, 90.0, 300, Models.ScalingStrategy.LATENCY, true, 200));

        assertThat(advice).noneMatch(r -> r.kind().equals("SCALING_STRATEGY"));
    }

    @Test
    @DisplayName("a target nobody measures is reported as an observability gap, with its cost")
    void unmeasuredTargetIsSurfaced() {
        OptimizationAdvisor.Recommendation recommendation = only("OBSERVABILITY_GAP",
                OptimizationAdvisor.advise(
                        facts(3, null, null, 250, Models.ScalingStrategy.NONE, false, 0)));

        assertThat(recommendation.rationale()).contains("$250.00");
        assertThat(recommendation.rationale()).contains("Register an endpoint");
        assertThat(recommendation.safeToApply()).isFalse();
    }

    @Test
    @DisplayName("a measured target is not reported as an observability gap")
    void measuredTargetIsNotAGap() {
        List<OptimizationAdvisor.Recommendation> advice = OptimizationAdvisor.advise(
                facts(3, 40.0, 90.0, 300, Models.ScalingStrategy.CPU, true, 200));

        assertThat(advice).noneMatch(r -> r.kind().equals("OBSERVABILITY_GAP"));
    }

    @Test
    @DisplayName("advice that cannot be automated carries no proposed replica count")
    void advisoryOnlyRecommendationsHaveNoAction() {
        List<OptimizationAdvisor.Recommendation> advice = OptimizationAdvisor.advise(
                facts(3, null, null, 250, Models.ScalingStrategy.TREND, false, 0));

        assertThat(advice).filteredOn(r -> !r.kind().equals("REPLICA_REDUCTION"))
                .allSatisfy(r -> assertThat(r.proposedReplicas()).isNull());
    }
}
