package io.aegiscloud.controlplane.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The Reliability Score, and specifically its refusal to reward missing data. */
class ReliabilityScoreTest {

    private static ReliabilityScore.Inputs inputs(Double availability, Double p95,
                                                  Double objective, Double errorRate) {
        return new ReliabilityScore.Inputs(
                availability == null ? OptionalDouble.empty() : OptionalDouble.of(availability),
                p95 == null ? OptionalDouble.empty() : OptionalDouble.of(p95),
                objective == null ? OptionalDouble.empty() : OptionalDouble.of(objective),
                errorRate == null ? OptionalDouble.empty() : OptionalDouble.of(errorRate));
    }

    @Test
    @DisplayName("a perfectly healthy target scores 100")
    void perfectTarget() {
        ReliabilityScore.Result result = ReliabilityScore.compute(inputs(100.0, 120.0, 250.0, 0.0));

        assertThat(result.score()).hasValue(100.0);
        assertThat(result.components()).containsKeys("availability", "latency", "errorRate");
    }

    @Test
    @DisplayName("nothing measured means no score, not a zero and not a hundred")
    void nothingMeasuredHasNoScore() {
        ReliabilityScore.Result result = ReliabilityScore.compute(inputs(null, null, null, null));

        assertThat(result.score()).isEmpty();
        assertThat(result.detail()).contains("no measurements");
    }

    @Test
    @DisplayName("a target with no latency data is neither rewarded nor punished for it")
    void missingComponentIsRenormalised() {
        // Availability 90, error rate 10 -> both components score 90. With latency
        // absent the score must still be 90, not 90 diluted by a phantom zero.
        ReliabilityScore.Result result = ReliabilityScore.compute(inputs(90.0, null, null, 10.0));

        assertThat(result.score()).hasValue(90.0);
        assertThat(result.components()).doesNotContainKey("latency");
    }

    @Test
    @DisplayName("latency at or under the objective is full marks")
    void latencyWithinObjective() {
        assertThat(ReliabilityScore.latencyScore(100, 250)).isEqualTo(100.0);
        assertThat(ReliabilityScore.latencyScore(250, 250)).isEqualTo(100.0);
    }

    @Test
    @DisplayName("latency degrades linearly and bottoms out at three times the objective")
    void latencyDegradesLinearly() {
        assertThat(ReliabilityScore.latencyScore(500, 250)).isCloseTo(50.0, within(0.001));
        assertThat(ReliabilityScore.latencyScore(750, 250)).isCloseTo(0.0, within(0.001));
        assertThat(ReliabilityScore.latencyScore(5000, 250)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("an outage drags the score down without zeroing it, since latency still counts")
    void outageDominatesButDoesNotErase() {
        // Half the probes failed; the ones that answered were fast.
        ReliabilityScore.Result result = ReliabilityScore.compute(inputs(50.0, 100.0, 250.0, 50.0));

        // 50*0.5 + 100*0.3 + 50*0.2 = 65
        assertThat(result.score()).hasValue(65.0);
    }

    @Test
    @DisplayName("every component is reported so a low score can be explained")
    void componentsAreExplained() {
        ReliabilityScore.Result result = ReliabilityScore.compute(inputs(99.0, 500.0, 250.0, 1.0));

        assertThat(result.components()).containsEntry("availability", 99.0);
        assertThat(result.components()).containsEntry("latency", 50.0);
        assertThat(result.components()).containsEntry("errorRate", 99.0);
    }

    @Test
    @DisplayName("a latency reading with no objective to judge it by is not scored")
    void latencyWithoutObjectiveIsSkipped() {
        ReliabilityScore.Result result = ReliabilityScore.compute(inputs(100.0, 900.0, null, 0.0));

        assertThat(result.components()).doesNotContainKey("latency");
        assertThat(result.score()).hasValue(100.0);
    }
}
