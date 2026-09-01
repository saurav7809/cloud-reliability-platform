package io.aegiscloud.controlplane.engine;

import io.aegiscloud.controlplane.domain.Models;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scaling rules, exercised without a cluster.
 *
 * <p>These are the decisions that will move real replicas, so each case states the
 * behaviour it is protecting rather than just the arithmetic.
 */
class ScalingEngineTest {

    private static final Duration COOLDOWN = Duration.ofSeconds(180);

    private static ScalingEngine.Signals cpu(double utilization) {
        return new ScalingEngine.Signals(OptionalDouble.of(utilization), OptionalDouble.empty(),
                OptionalDouble.empty(), List.of());
    }

    private static ScalingEngine.Signals latency(double observed, double objective) {
        return new ScalingEngine.Signals(OptionalDouble.empty(), OptionalDouble.of(observed),
                OptionalDouble.of(objective), List.of(observed));
    }

    private static ScalingEngine.Signals trend(List<Double> series, double objective) {
        return new ScalingEngine.Signals(OptionalDouble.empty(),
                OptionalDouble.of(series.get(series.size() - 1)), OptionalDouble.of(objective), series);
    }

    @Test
    @DisplayName("NONE never scales, however bad the readings are")
    void noneStrategyHolds() {
        ScalingEngine.Decision decision = ScalingEngine.decide(
                Models.ScalingStrategy.NONE, cpu(300), 2, 10, null, COOLDOWN);

        assertThat(decision.act()).isFalse();
        assertThat(decision.reason()).contains("NONE");
    }

    @Test
    @DisplayName("CPU above the ceiling sizes for the target utilisation in one move")
    void cpuScalesUpProportionally() {
        // 2 replicas at 120% of request; sizing for 60% needs 4.
        ScalingEngine.Decision decision = ScalingEngine.decide(
                Models.ScalingStrategy.CPU, cpu(120), 2, 10, null, COOLDOWN);

        assertThat(decision.act()).isTrue();
        assertThat(decision.toReplicas()).isEqualTo(4);
        assertThat(decision.actionType()).isEqualTo(ActionType.SCALE_UP);
        assertThat(decision.triggerMetric()).isEqualTo("cpu");
    }

    @Test
    @DisplayName("CPU scale-up is capped by the policy maximum")
    void cpuScaleUpRespectsPolicyCeiling() {
        ScalingEngine.Decision decision = ScalingEngine.decide(
                Models.ScalingStrategy.CPU, cpu(400), 3, 4, null, COOLDOWN);

        assertThat(decision.toReplicas()).isEqualTo(4);
    }

    @Test
    @DisplayName("CPU at the maximum with nowhere to go says so instead of pretending to act")
    void cpuAtCeilingHolds() {
        ScalingEngine.Decision decision = ScalingEngine.decide(
                Models.ScalingStrategy.CPU, cpu(400), 4, 4, null, COOLDOWN);

        assertThat(decision.act()).isFalse();
        assertThat(decision.reason()).contains("policy maximum");
    }

    @Test
    @DisplayName("CPU scale-down removes one replica at a time")
    void cpuScalesDownOneStep() {
        ScalingEngine.Decision decision = ScalingEngine.decide(
                Models.ScalingStrategy.CPU, cpu(10), 5, 10, null, COOLDOWN);

        assertThat(decision.act()).isTrue();
        assertThat(decision.toReplicas()).isEqualTo(4);
        assertThat(decision.actionType()).isEqualTo(ActionType.SCALE_DOWN);
    }

    @Test
    @DisplayName("autonomous scaling never removes the last replica")
    void cpuNeverScalesToZero() {
        ScalingEngine.Decision decision = ScalingEngine.decide(
                Models.ScalingStrategy.CPU, cpu(1), 1, 10, null, COOLDOWN);

        assertThat(decision.act()).isFalse();
    }

    @Test
    @DisplayName("a single idle replica is reported as at the minimum, not as inside the band")
    void singleIdleReplicaSaysWhyItStays() {
        ScalingEngine.Decision decision = ScalingEngine.decide(
                Models.ScalingStrategy.CPU, cpu(1.1), 1, 10, null, COOLDOWN);

        assertThat(decision.act()).isFalse();
        assertThat(decision.reason()).contains("already at 1 replica");
        assertThat(decision.reason()).doesNotContain("within");
    }

    @Test
    @DisplayName("the deadband between the floor and ceiling produces no change")
    void cpuInsideDeadbandHolds() {
        assertThat(ScalingEngine.decide(Models.ScalingStrategy.CPU, cpu(60), 3, 10, null, COOLDOWN).act())
                .isFalse();
        assertThat(ScalingEngine.decide(Models.ScalingStrategy.CPU, cpu(74.9), 3, 10, null, COOLDOWN).act())
                .isFalse();
        assertThat(ScalingEngine.decide(Models.ScalingStrategy.CPU, cpu(30.1), 3, 10, null, COOLDOWN).act())
                .isFalse();
    }

    @Test
    @DisplayName("a missing CPU reading holds and explains why, rather than assuming a value")
    void cpuWithoutMetricsHolds() {
        ScalingEngine.Signals none = new ScalingEngine.Signals(
                OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty(), List.of());

        ScalingEngine.Decision decision = ScalingEngine.decide(
                Models.ScalingStrategy.CPU, none, 2, 10, null, COOLDOWN);

        assertThat(decision.act()).isFalse();
        assertThat(decision.reason()).contains("undefined");
    }

    @Test
    @DisplayName("the cooldown blocks a decision that would otherwise be taken, and says how long is left")
    void cooldownPreventsFlapping() {
        ScalingEngine.Decision decision = ScalingEngine.decide(Models.ScalingStrategy.CPU, cpu(120),
                2, 10, Duration.ofSeconds(30), COOLDOWN);

        assertThat(decision.act()).isFalse();
        assertThat(decision.reason()).contains("150s of the 180s cooldown remain");
    }

    @Test
    @DisplayName("once the cooldown has elapsed the same decision goes through")
    void cooldownElapsedAllowsAction() {
        ScalingEngine.Decision decision = ScalingEngine.decide(Models.ScalingStrategy.CPU, cpu(120),
                2, 10, Duration.ofSeconds(181), COOLDOWN);

        assertThat(decision.act()).isTrue();
    }

    @Test
    @DisplayName("latency over the SLO adds a replica")
    void latencyBreachScalesUp() {
        ScalingEngine.Decision decision = ScalingEngine.decide(
                Models.ScalingStrategy.LATENCY, latency(420, 300), 2, 10, null, COOLDOWN);

        assertThat(decision.act()).isTrue();
        assertThat(decision.toReplicas()).isEqualTo(3);
        assertThat(decision.triggerMetric()).isEqualTo("latency");
    }

    @Test
    @DisplayName("latency well under the SLO gives a replica back")
    void latencySlackScalesDown() {
        ScalingEngine.Decision decision = ScalingEngine.decide(
                Models.ScalingStrategy.LATENCY, latency(100, 300), 3, 10, null, COOLDOWN);

        assertThat(decision.act()).isTrue();
        assertThat(decision.toReplicas()).isEqualTo(2);
    }

    @Test
    @DisplayName("LATENCY without an SLO refuses to invent a threshold")
    void latencyWithoutObjectiveHolds() {
        ScalingEngine.Signals noObjective = new ScalingEngine.Signals(OptionalDouble.empty(),
                OptionalDouble.of(900), OptionalDouble.empty(), List.of(900.0));

        ScalingEngine.Decision decision = ScalingEngine.decide(
                Models.ScalingStrategy.LATENCY, noObjective, 2, 10, null, COOLDOWN);

        assertThat(decision.act()).isFalse();
        assertThat(decision.reason()).contains("no SLO");
    }

    @Test
    @DisplayName("TREND adds capacity before the projection crosses the objective")
    void trendScalesUpAheadOfBreach() {
        // Still inside the objective, but climbing 25ms a sample.
        ScalingEngine.Decision decision = ScalingEngine.decide(Models.ScalingStrategy.TREND,
                trend(List.of(180.0, 205.0, 230.0, 255.0), 300), 2, 10, null, COOLDOWN);

        assertThat(decision.act()).isTrue();
        assertThat(decision.toReplicas()).isEqualTo(3);
        assertThat(decision.triggerMetric()).isEqualTo("trend");
        assertThat(decision.reason()).contains("ahead of the breach");
    }

    @Test
    @DisplayName("TREND ignores a flat series that happens to sit near the objective")
    void trendFlatSeriesHolds() {
        ScalingEngine.Decision decision = ScalingEngine.decide(Models.ScalingStrategy.TREND,
                trend(List.of(290.0, 291.0, 289.0, 290.0), 300), 2, 10, null, COOLDOWN);

        assertThat(decision.act()).isFalse();
    }

    @Test
    @DisplayName("TREND will not fit a line through fewer than three samples")
    void trendNeedsEnoughSamples() {
        ScalingEngine.Decision decision = ScalingEngine.decide(Models.ScalingStrategy.TREND,
                trend(List.of(100.0, 400.0), 300), 2, 10, null, COOLDOWN);

        assertThat(decision.act()).isFalse();
        assertThat(decision.reason()).contains("at least 3 samples");
    }

    @Test
    @DisplayName("the slope is a least-squares fit, not the last delta")
    void slopeIsLeastSquares() {
        assertThat(ScalingEngine.slope(List.of(10.0, 20.0, 30.0, 40.0))).isEqualTo(10.0);
        assertThat(ScalingEngine.slope(List.of(50.0, 50.0, 50.0))).isEqualTo(0.0);
        assertThat(ScalingEngine.slope(List.of(40.0, 30.0, 20.0))).isEqualTo(-10.0);
    }
}
