package io.aegiscloud.controlplane.alerting;

import io.aegiscloud.controlplane.domain.Models;

/**
 * When a burn rate deserves to wake somebody (FR-39).
 *
 * <p>Pure, and severity is derived from how fast the budget is disappearing rather
 * than from how bad the number looks. A burn rate of 2 means the month's error budget
 * will be gone in a fortnight — worth knowing, not worth a phone call at 3am. A burn
 * rate of 30 means it is gone within a day, and that is a different conversation.
 *
 * <p>The thresholds follow the multi-window burn-rate practice from Google's SRE
 * workbook, collapsed to the single window the platform currently evaluates. Stated
 * plainly rather than tuned by feel, because an alerting threshold nobody can justify
 * is the first thing an on-call engineer learns to ignore.
 */
public final class BurnRateAlerting {

    /**
     * Budget consumed at this multiple of the sustainable rate exhausts a 30-day
     * budget in roughly a day.
     */
    static final double CRITICAL_BURN_RATE = 14.4;

    /** Exhausts the budget in about three days. */
    static final double HIGH_BURN_RATE = 6.0;

    /** Exhausts the budget before the window ends, but with room to respond. */
    static final double MEDIUM_BURN_RATE = 2.0;

    /**
     * Below this much budget remaining, even a slow burn matters: there is nothing
     * left to absorb the next incident.
     */
    static final double EXHAUSTED_BUDGET_PCT = 10.0;

    private BurnRateAlerting() {
    }

    /**
     * @param severity absent when nothing should be raised
     */
    public record Verdict(Models.AlertSeverity severity, String message, boolean shouldAlert) {

        static Verdict quiet() {
            return new Verdict(null, "", false);
        }

        static Verdict raise(Models.AlertSeverity severity, String message) {
            return new Verdict(severity, message, true);
        }
    }

    /**
     * Decides whether an SLO's current state warrants an alert.
     *
     * @param sampleCount how many measurements the burn rate rests on. A dramatic
     *                    rate computed from four probes is a rumour, not a signal,
     *                    and paging on it is how alerting loses its credibility.
     */
    public static Verdict evaluate(String targetLabel, Models.SliType sliType,
                                   double burnRate, double budgetRemainingPct, int sampleCount) {

        if (sampleCount < 10) {
            return Verdict.quiet();
        }

        if (burnRate >= CRITICAL_BURN_RATE) {
            return Verdict.raise(Models.AlertSeverity.CRITICAL, String.format(
                    "%s is burning its %s error budget %.1fx faster than sustainable; "
                            + "at this rate the budget is gone within a day (%.1f%% left)",
                    targetLabel, sliType, burnRate, budgetRemainingPct));
        }

        if (burnRate >= HIGH_BURN_RATE) {
            return Verdict.raise(Models.AlertSeverity.HIGH, String.format(
                    "%s is burning its %s error budget %.1fx faster than sustainable; "
                            + "about three days of budget remain at this rate (%.1f%% left)",
                    targetLabel, sliType, burnRate, budgetRemainingPct));
        }

        if (budgetRemainingPct <= EXHAUSTED_BUDGET_PCT) {
            // A slow burn against an almost-empty budget is worth raising even though
            // the rate alone would not: there is no headroom left for the next thing
            // to go wrong.
            return Verdict.raise(Models.AlertSeverity.MEDIUM, String.format(
                    "%s has %.1f%% of its %s error budget left, burning at %.1fx; "
                            + "there is no headroom for another incident this window",
                    targetLabel, budgetRemainingPct, sliType, burnRate));
        }

        if (burnRate >= MEDIUM_BURN_RATE) {
            return Verdict.raise(Models.AlertSeverity.LOW, String.format(
                    "%s is burning its %s error budget %.1fx faster than sustainable "
                            + "(%.1f%% left)", targetLabel, sliType, burnRate, budgetRemainingPct));
        }

        return Verdict.quiet();
    }
}
