package io.aegiscloud.controlplane.eval;

import io.aegiscloud.controlplane.domain.Models;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Error budget and burn rate, which are the numbers people make decisions on. */
class SloEvaluatorTest {

    private static List<SloEvaluator.Sample> availability(int successes, int failures) {
        List<SloEvaluator.Sample> samples = new ArrayList<>();
        for (int i = 0; i < successes; i++) {
            samples.add(new SloEvaluator.Sample(100, true));
        }
        for (int i = 0; i < failures; i++) {
            samples.add(new SloEvaluator.Sample(0, false));
        }
        return samples;
    }

    private static List<SloEvaluator.Sample> latencies(double... values) {
        List<SloEvaluator.Sample> samples = new ArrayList<>();
        for (double value : values) {
            samples.add(new SloEvaluator.Sample(value, true));
        }
        return samples;
    }

    @Test
    @DisplayName("no samples yields no verdict, not a clean bill of health")
    void emptyWindowIsNotCompliance() {
        SloEvaluator.Evaluation evaluation = SloEvaluator.evaluate(
                Models.SliType.AVAILABILITY, 99.9, List.of());

        assertThat(evaluation.sampleCount()).isZero();
        assertThat(evaluation.budgetRemainingPct()).isZero();
        assertThat(evaluation.detail()).contains("nothing has been measured");
    }

    @Test
    @DisplayName("a perfect window leaves the whole budget intact and burns nothing")
    void perfectAvailability() {
        SloEvaluator.Evaluation evaluation = SloEvaluator.evaluate(
                Models.SliType.AVAILABILITY, 99.0, availability(100, 0));

        assertThat(evaluation.currentValue()).isEqualTo(100.0);
        assertThat(evaluation.budgetRemainingPct()).isEqualTo(100.0);
        assertThat(evaluation.burnRate()).isZero();
    }

    @Test
    @DisplayName("failing exactly at the objective spends the budget exactly once over")
    void burnRateOfOneAtTheObjective() {
        // 99% objective allows 1% bad; one failure in a hundred is precisely that.
        SloEvaluator.Evaluation evaluation = SloEvaluator.evaluate(
                Models.SliType.AVAILABILITY, 99.0, availability(99, 1));

        assertThat(evaluation.burnRate()).isCloseTo(1.0, within(0.001));
        assertThat(evaluation.budgetRemainingPct()).isCloseTo(0.0, within(0.001));
    }

    @Test
    @DisplayName("failing twice as often burns twice as fast")
    void burnRateScalesWithFailure() {
        SloEvaluator.Evaluation evaluation = SloEvaluator.evaluate(
                Models.SliType.AVAILABILITY, 99.0, availability(98, 2));

        assertThat(evaluation.burnRate()).isCloseTo(2.0, within(0.001));
        assertThat(evaluation.budgetRemainingPct()).isZero();
    }

    @Test
    @DisplayName("half the budget spent reads as half remaining")
    void halfBudgetRemaining() {
        // 99.0% objective over 200 samples allows 2 failures; one uses half of it.
        SloEvaluator.Evaluation evaluation = SloEvaluator.evaluate(
                Models.SliType.AVAILABILITY, 99.0, availability(199, 1));

        assertThat(evaluation.budgetRemainingPct()).isCloseTo(50.0, within(0.001));
        assertThat(evaluation.burnRate()).isCloseTo(0.5, within(0.001));
    }

    @Test
    @DisplayName("a 100% objective tolerates nothing, and says so without dividing by zero")
    void zeroToleranceObjective() {
        assertThat(SloEvaluator.evaluate(Models.SliType.AVAILABILITY, 100.0, availability(50, 0))
                .budgetRemainingPct()).isEqualTo(100.0);

        SloEvaluator.Evaluation breached = SloEvaluator.evaluate(
                Models.SliType.AVAILABILITY, 100.0, availability(49, 1));

        assertThat(breached.budgetRemainingPct()).isZero();
        assertThat(breached.burnRate()).isInfinite();
    }

    @Test
    @DisplayName("p95 is the percentile, so one outlier in twenty does not become the reading")
    void latencyReportsThePercentileNotTheOutlier() {
        // Nineteen samples at 100ms and one at 2000ms. The mean would be 195ms - a
        // number nothing measured - and the max would be 2000ms. Nearest-rank p95 of
        // twenty samples is the 19th, which is what a p95 objective actually promises.
        double[] values = new double[20];
        for (int i = 0; i < 19; i++) {
            values[i] = 100;
        }
        values[19] = 2000;

        SloEvaluator.Evaluation evaluation = SloEvaluator.evaluate(
                Models.SliType.LATENCY_P95, 250, latencies(values));

        assertThat(evaluation.currentValue()).isEqualTo(100.0);
        assertThat(evaluation.detail()).contains("19 of 20 samples were within it");
    }

    @Test
    @DisplayName("p99 catches the outlier that p95 is entitled to ignore")
    void p99SeesWhatP95Tolerates() {
        double[] values = new double[20];
        for (int i = 0; i < 19; i++) {
            values[i] = 100;
        }
        values[19] = 2000;

        SloEvaluator.Evaluation p99 = SloEvaluator.evaluate(
                Models.SliType.LATENCY_P99, 250, latencies(values));

        assertThat(p99.currentValue()).isEqualTo(2000.0);
        // 95% within the objective, against a 99% requirement: the budget is gone.
        assertThat(p99.budgetRemainingPct()).isZero();
    }

    @Test
    @DisplayName("one slow request in twenty does not breach a p95 objective")
    void oneSlowSampleDoesNotBreachP95() {
        double[] values = new double[20];
        for (int i = 0; i < 19; i++) {
            values[i] = 100;
        }
        values[19] = 900;

        SloEvaluator.Evaluation evaluation = SloEvaluator.evaluate(
                Models.SliType.LATENCY_P95, 250, latencies(values));

        // 95% of samples were within the objective, which is exactly what p95 claims.
        assertThat(evaluation.burnRate()).isCloseTo(1.0, within(0.001));
    }

    @Test
    @DisplayName("a latency objective missed by most requests exhausts the budget")
    void widespreadSlownessBreaches() {
        SloEvaluator.Evaluation evaluation = SloEvaluator.evaluate(
                Models.SliType.LATENCY_P95, 250, latencies(400, 500, 600, 700, 800));

        assertThat(evaluation.budgetRemainingPct()).isZero();
        assertThat(evaluation.burnRate()).isGreaterThan(1.0);
    }

    @Test
    @DisplayName("error rate is judged against a ceiling, not a floor")
    void errorRateAgainstCeiling() {
        SloEvaluator.Evaluation evaluation = SloEvaluator.evaluate(
                Models.SliType.ERROR_RATE, 5.0, availability(98, 2));

        assertThat(evaluation.currentValue()).isCloseTo(2.0, within(0.001));
        assertThat(evaluation.budgetRemainingPct()).isCloseTo(60.0, within(0.001));
        assertThat(evaluation.burnRate()).isCloseTo(0.4, within(0.001));
    }

    @Test
    @DisplayName("the percentile is nearest-rank, so it is always a value that was measured")
    void percentileIsNearestRank() {
        List<SloEvaluator.Sample> samples = latencies(10, 20, 30, 40, 50, 60, 70, 80, 90, 100);

        assertThat(SloEvaluator.percentile(samples, 95)).isEqualTo(100.0);
        assertThat(SloEvaluator.percentile(samples, 50)).isEqualTo(50.0);
        assertThat(SloEvaluator.percentile(latencies(42), 99)).isEqualTo(42.0);
    }
}
