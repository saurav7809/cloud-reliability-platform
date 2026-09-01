package io.aegiscloud.controlplane.alerting;

import io.aegiscloud.controlplane.domain.Models;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When the platform wakes somebody up.
 *
 * <p>Alerting rules earn their tests more than most code: a rule that is slightly too
 * eager trains people to ignore alerts, and one that is slightly too quiet is
 * indistinguishable from having no alerting at all.
 */
class BurnRateAlertingTest {

    private static BurnRateAlerting.Verdict evaluate(double burnRate, double budgetLeft,
                                                     int samples) {
        return BurnRateAlerting.evaluate("checkout @ prod", Models.SliType.AVAILABILITY,
                burnRate, budgetLeft, samples);
    }

    @Test
    @DisplayName("a sustainable burn rate raises nothing")
    void sustainableBurnIsQuiet() {
        assertThat(evaluate(0.8, 90, 500).shouldAlert()).isFalse();
    }

    @Test
    @DisplayName("burning fast enough to exhaust the budget in a day is critical")
    void veryFastBurnIsCritical() {
        BurnRateAlerting.Verdict verdict = evaluate(20.0, 60, 500);

        assertThat(verdict.severity()).isEqualTo(Models.AlertSeverity.CRITICAL);
        assertThat(verdict.message()).contains("within a day");
    }

    @Test
    @DisplayName("burning through the budget in days is high, not critical")
    void fastBurnIsHigh() {
        assertThat(evaluate(8.0, 60, 500).severity()).isEqualTo(Models.AlertSeverity.HIGH);
    }

    @Test
    @DisplayName("a mild overspend is low severity, not silence")
    void mildOverspendIsLow() {
        assertThat(evaluate(2.5, 70, 500).severity()).isEqualTo(Models.AlertSeverity.LOW);
    }

    @Test
    @DisplayName("an almost-empty budget alerts even at a gentle burn rate")
    void exhaustedBudgetAlertsRegardless() {
        // 1.2x would normally be ignored; with 4% of the budget left there is no
        // headroom for the next incident, and that is worth saying.
        BurnRateAlerting.Verdict verdict = evaluate(1.2, 4.0, 500);

        assertThat(verdict.shouldAlert()).isTrue();
        assertThat(verdict.severity()).isEqualTo(Models.AlertSeverity.MEDIUM);
        assertThat(verdict.message()).contains("no headroom");
    }

    @Test
    @DisplayName("a dramatic rate from a handful of samples raises nothing")
    void tooFewSamplesIsNotEvidence() {
        // A 50x burn rate computed from four probes is a rumour. Paging on it is how
        // alerting loses the credibility it needs to be acted on.
        assertThat(evaluate(50.0, 1.0, 4).shouldAlert()).isFalse();
    }

    @Test
    @DisplayName("severity rises monotonically with burn rate")
    void severityIsOrdered() {
        assertThat(evaluate(2.5, 70, 500).severity()).isEqualTo(Models.AlertSeverity.LOW);
        assertThat(evaluate(7.0, 70, 500).severity()).isEqualTo(Models.AlertSeverity.HIGH);
        assertThat(evaluate(15.0, 70, 500).severity()).isEqualTo(Models.AlertSeverity.CRITICAL);
    }

    @Test
    @DisplayName("the message names the service, the objective and the numbers behind it")
    void messagesAreActionable() {
        String message = evaluate(20.0, 12.5, 500).message();

        assertThat(message).contains("checkout @ prod");
        assertThat(message).contains("AVAILABILITY");
        assertThat(message).contains("20.0x");
        assertThat(message).contains("12.5%");
    }
}
