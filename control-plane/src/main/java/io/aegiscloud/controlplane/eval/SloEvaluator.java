package io.aegiscloud.controlplane.eval;

import io.aegiscloud.controlplane.domain.Models;

import java.util.ArrayList;
import java.util.List;

/**
 * SLO arithmetic: what the measurements say, how much error budget is left, and how
 * fast it is being spent.
 *
 * <p>Pure by design — this is the part of the Evaluation Engine that has to be right,
 * and it is fully testable without a probe, a cluster or a database.
 *
 * <p>Every SLO type reduces to the same question: what fraction of measurements were
 * good, against what fraction the objective allows to be bad. Availability counts
 * failed probes; latency counts probes slower than the objective; error rate counts
 * error responses. Expressing them uniformly is what lets one budget calculation
 * serve all of them, rather than four subtly different ones that drift apart.
 */
public final class SloEvaluator {

    /**
     * The share of requests a percentile objective permits to exceed it.
     *
     * <p>A p95 objective of 250ms is a claim that 95% of requests come in under 250ms
     * — not that every request does. Treating a single slow request as a breach would
     * make the objective mean something it does not say.
     */
    private static final double P95_COMPLIANCE = 95.0;
    private static final double P99_COMPLIANCE = 99.0;

    private SloEvaluator() {
    }

    /** One measurement, as the evaluator needs it. */
    public record Sample(double value, boolean success) {
    }

    /**
     * @param currentValue       the SLI as measured over the window, in the SLI's own
     *                           units (a percentage, a latency in ms, a rate)
     * @param budgetRemainingPct how much of the allowed failure budget is left, 0-100
     * @param burnRate           observed failure over allowed failure. 1.0 means the
     *                           budget is being spent exactly as fast as the window
     *                           permits; above 1.0 it will be exhausted early
     * @param sampleCount        how many measurements this verdict rests on, so a
     *                           reading from three probes is not mistaken for a
     *                           reading from three thousand
     */
    public record Evaluation(
            double currentValue,
            double budgetRemainingPct,
            double burnRate,
            int sampleCount,
            String detail) {
    }

    /** Evaluates one SLO against the samples collected in its window. */
    public static Evaluation evaluate(Models.SliType sliType, double objective, List<Sample> samples) {
        if (samples.isEmpty()) {
            // No data is not the same as compliance. Reporting a full budget here
            // would tell an operator the service is healthy when nothing has
            // measured it at all.
            return new Evaluation(0, 0, 0, 0, "no samples in window; nothing has been measured");
        }

        return switch (sliType) {
            case AVAILABILITY -> compliance(samples, objective,
                    Sample::success, "availability");
            case LATENCY_P95 -> latency(samples, objective, P95_COMPLIANCE, "p95");
            case LATENCY_P99 -> latency(samples, objective, P99_COMPLIANCE, "p99");
            case ERROR_RATE -> errorRate(samples, objective);
            case THROUGHPUT -> throughput(samples, objective);
        };
    }

    /**
     * The shared budget calculation.
     *
     * @param good      how many measurements met the bar
     * @param total     how many were taken
     * @param objective the percentage of measurements required to be good
     */
    private static Evaluation budget(int good, int total, double objective, double currentValue,
                                     String detail) {
        double observedGoodPct = (good * 100.0) / total;
        double allowedBadPct = 100.0 - objective;
        double observedBadPct = 100.0 - observedGoodPct;

        // An objective of exactly 100% permits no failure at all, so any failure
        // exhausts a budget of zero. Dividing by that would give infinity; saying
        // "nothing left" is both true and useful.
        double budgetRemaining = allowedBadPct <= 0
                ? (observedBadPct > 0 ? 0 : 100)
                : clamp((1 - observedBadPct / allowedBadPct) * 100);

        double burnRate = allowedBadPct <= 0
                ? (observedBadPct > 0 ? Double.POSITIVE_INFINITY : 0)
                : observedBadPct / allowedBadPct;

        return new Evaluation(currentValue, budgetRemaining, burnRate, total,
                String.format("%d of %d measurements met the %s objective (%.2f%%)",
                        good, total, detail, observedGoodPct));
    }

    private static Evaluation compliance(List<Sample> samples, double objective,
                                         java.util.function.Predicate<Sample> isGood, String label) {
        int good = (int) samples.stream().filter(isGood).count();
        double currentValue = (good * 100.0) / samples.size();
        return budget(good, samples.size(), objective, currentValue, label);
    }

    /**
     * Latency against a percentile objective.
     *
     * <p>The reported current value is the actual percentile, because that is the
     * number an operator compares to the objective. The budget, though, is computed
     * from how many samples exceeded it — a p95 that sits just over the line with 6%
     * of requests slow is in a very different state from one where 40% are.
     */
    private static Evaluation latency(List<Sample> samples, double objective,
                                      double compliance, String label) {
        int within = (int) samples.stream().filter(s -> s.value() <= objective).count();
        double percentile = percentile(samples, label.equals("p99") ? 99 : 95);

        Evaluation base = budget(within, samples.size(), compliance, percentile, label);
        return new Evaluation(percentile, base.budgetRemainingPct(), base.burnRate(),
                base.sampleCount(),
                String.format("%s is %.0fms against a %.0fms objective; %d of %d samples were within it",
                        label, percentile, objective, within, samples.size()));
    }

    private static Evaluation errorRate(List<Sample> samples, double objective) {
        int errors = (int) samples.stream().filter(s -> !s.success()).count();
        double observed = (errors * 100.0) / samples.size();

        double budgetRemaining = objective <= 0
                ? (observed > 0 ? 0 : 100)
                : clamp((1 - observed / objective) * 100);
        double burnRate = objective <= 0
                ? (observed > 0 ? Double.POSITIVE_INFINITY : 0)
                : observed / objective;

        return new Evaluation(observed, budgetRemaining, burnRate, samples.size(),
                String.format("error rate %.2f%% against a %.2f%% ceiling (%d of %d failed)",
                        observed, objective, errors, samples.size()));
    }

    /**
     * Throughput, where the objective is a floor rather than a ceiling.
     *
     * <p>Included for completeness of the SLI types the schema defines. Unlike the
     * others it is not derived from probe outcomes: a synthetic prober measures its
     * own request rate, not the service's real traffic, so a meaningful throughput
     * SLO needs metrics pushed from the workload. Until Prometheus ingestion exists
     * this evaluates whatever samples were pushed, and says so when there are none.
     */
    private static Evaluation throughput(List<Sample> samples, double objective) {
        double mean = samples.stream().mapToDouble(Sample::value).average().orElse(0);
        int meeting = (int) samples.stream().filter(s -> s.value() >= objective).count();

        Evaluation base = budget(meeting, samples.size(), 95.0, mean, "throughput");
        return new Evaluation(mean, base.budgetRemainingPct(), base.burnRate(), base.sampleCount(),
                String.format("mean throughput %.1f against a floor of %.1f", mean, objective));
    }

    /**
     * The nearest-rank percentile.
     *
     * <p>Nearest-rank rather than interpolated: with the sample counts a probe
     * schedule produces - tens, not millions - an interpolated percentile invents a
     * value that was never measured.
     */
    static double percentile(List<Sample> samples, int percentile) {
        List<Double> sorted = new ArrayList<>(samples.stream().map(Sample::value).toList());
        sorted.sort(Double::compareTo);

        int rank = (int) Math.ceil((percentile / 100.0) * sorted.size());
        return sorted.get(Math.min(Math.max(rank, 1), sorted.size()) - 1);
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(100, value));
    }
}
