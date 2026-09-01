package io.aegiscloud.controlplane.eval;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * The Reliability Score (FR-19): one number from 0 to 100 per target per window.
 *
 * <p>A single number is a lossy thing, and the temptation with one is to tune the
 * weights until the answer looks good. Two rules keep it honest here. Every component
 * is returned alongside the score, so a low score can always be read back to the
 * measurement that caused it. And an unmeasured component is dropped from the average
 * rather than scored as zero or as full marks — a target with no latency data is not
 * thereby perfect, nor thereby broken, and pretending either would make scores
 * incomparable across targets, which is exactly what FR-20 needs them to be.
 */
public final class ReliabilityScore {

    /**
     * Availability weighs most because it is the only component whose failure the
     * user always notices. Latency is second: slow is a degraded kind of working.
     * Error rate overlaps with availability by construction and is weighted lower to
     * avoid counting the same failure twice.
     */
    private static final double AVAILABILITY_WEIGHT = 0.5;
    private static final double LATENCY_WEIGHT = 0.3;
    private static final double ERROR_RATE_WEIGHT = 0.2;

    /**
     * Latency at or below the objective scores 100; at this multiple of it, zero.
     *
     * <p>Linear between the two. A service three times slower than its objective is
     * not "somewhat degraded" by any user's reckoning, so the scale bottoms out there
     * rather than trailing asymptotically toward zero.
     */
    private static final double LATENCY_ZERO_MULTIPLE = 3.0;

    private ReliabilityScore() {
    }

    /**
     * The measurements a score is computed from. Each is optional: absent means not
     * measured in this window, which is treated as no evidence rather than as bad or
     * good news.
     *
     * @param availabilityPct    successful probes as a percentage
     * @param latencyP95Ms       observed p95
     * @param latencyObjectiveMs the objective p95 is judged against
     * @param errorRatePct       failed responses as a percentage
     */
    public record Inputs(
            OptionalDouble availabilityPct,
            OptionalDouble latencyP95Ms,
            OptionalDouble latencyObjectiveMs,
            OptionalDouble errorRatePct) {
    }

    /**
     * @param score      0-100, or absent when nothing was measured at all
     * @param components each contributing sub-score by name, so the number can be
     *                   explained rather than merely reported
     */
    public record Result(OptionalDouble score, Map<String, Double> components, String detail) {
    }

    public static Result compute(Inputs inputs) {
        Map<String, Double> components = new LinkedHashMap<>();
        double weighted = 0;
        double weightUsed = 0;

        if (inputs.availabilityPct().isPresent()) {
            double component = clamp(inputs.availabilityPct().getAsDouble());
            components.put("availability", round(component));
            weighted += component * AVAILABILITY_WEIGHT;
            weightUsed += AVAILABILITY_WEIGHT;
        }

        if (inputs.latencyP95Ms().isPresent() && inputs.latencyObjectiveMs().isPresent()) {
            double component = latencyScore(inputs.latencyP95Ms().getAsDouble(),
                    inputs.latencyObjectiveMs().getAsDouble());
            components.put("latency", round(component));
            weighted += component * LATENCY_WEIGHT;
            weightUsed += LATENCY_WEIGHT;
        }

        if (inputs.errorRatePct().isPresent()) {
            double component = clamp(100 - inputs.errorRatePct().getAsDouble());
            components.put("errorRate", round(component));
            weighted += component * ERROR_RATE_WEIGHT;
            weightUsed += ERROR_RATE_WEIGHT;
        }

        if (weightUsed == 0) {
            return new Result(OptionalDouble.empty(), components,
                    "no measurements in this window; no score can be computed");
        }

        // Renormalising by the weight actually used is what makes a target with two
        // measured components comparable to one with three, instead of penalising it
        // for the data nobody collected.
        double score = round(weighted / weightUsed);

        return new Result(OptionalDouble.of(score), components,
                String.format("score %.1f from %d of 3 components", score, components.size()));
    }

    /**
     * Latency as a 0-100 component.
     *
     * <p>Full marks at or under the objective, falling linearly to zero at
     * {@link #LATENCY_ZERO_MULTIPLE} times it.
     */
    static double latencyScore(double observedMs, double objectiveMs) {
        if (objectiveMs <= 0) {
            return 0;
        }
        if (observedMs <= objectiveMs) {
            return 100;
        }
        double overshoot = (observedMs - objectiveMs) / (objectiveMs * (LATENCY_ZERO_MULTIPLE - 1));
        return clamp(100 * (1 - overshoot));
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(100, value));
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
