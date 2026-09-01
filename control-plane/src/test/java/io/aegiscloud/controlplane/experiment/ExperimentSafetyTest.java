package io.aegiscloud.controlplane.experiment;

import io.aegiscloud.controlplane.engine.PolicyLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules that stand between an experiment and an outage.
 *
 * <p>This is the only part of the platform that causes harm deliberately, so these
 * are the tests most worth having.
 */
class ExperimentSafetyTest {

    private static final PolicyLimits DEFAULTS = new PolicyLimits(10, 1, List.of("kube-system"));

    private static ExperimentSafety.Request request(FaultType type, String namespace,
                                                    int replicas, int magnitude,
                                                    int duration, int running) {
        return new ExperimentSafety.Request(type, namespace, replicas, magnitude, duration, running);
    }

    @Test
    @DisplayName("killing one of four pods is allowed")
    void modestPodKillAllowed() {
        ExperimentSafety.Verdict verdict = ExperimentSafety.check(
                request(FaultType.POD_KILL, "shop", 4, 1, 60, 0), DEFAULTS);

        assertThat(verdict.allowed()).isTrue();
    }

    @Test
    @DisplayName("taking more than half the replicas is refused")
    void blastRadiusEnforced() {
        assertThat(ExperimentSafety.check(
                request(FaultType.POD_KILL, "shop", 4, 2, 60, 0), DEFAULTS).allowed()).isTrue();

        ExperimentSafety.Verdict tooMuch = ExperimentSafety.check(
                request(FaultType.POD_KILL, "shop", 4, 3, 60, 0), DEFAULTS);

        assertThat(tooMuch.allowed()).isFalse();
        assertThat(tooMuch.reason()).contains("blast-radius limit");
    }

    @Test
    @DisplayName("a single-replica target cannot be experimented on: that is just an outage")
    void singleReplicaRefused() {
        ExperimentSafety.Verdict verdict = ExperimentSafety.check(
                request(FaultType.POD_KILL, "shop", 1, 1, 60, 0), DEFAULTS);

        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.reason()).contains("full outage, not an experiment");
    }

    @Test
    @DisplayName("a protected namespace is off limits to chaos")
    void protectedNamespaceRefused() {
        ExperimentSafety.Verdict verdict = ExperimentSafety.check(
                request(FaultType.POD_KILL, "kube-system", 6, 1, 60, 0), DEFAULTS);

        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.reason()).contains("protected by policy");
    }

    @Test
    @DisplayName("namespace protection ignores case")
    void protectedNamespaceIgnoresCase() {
        PolicyLimits limits = new PolicyLimits(10, 1, List.of("Kube-System"));

        assertThat(ExperimentSafety.check(
                request(FaultType.POD_KILL, "kube-system", 6, 1, 60, 0), limits).allowed()).isFalse();
    }

    @Test
    @DisplayName("a second concurrent experiment is refused, because overlapping faults are unreadable")
    void concurrencyLimitEnforced() {
        ExperimentSafety.Verdict verdict = ExperimentSafety.check(
                request(FaultType.POD_KILL, "shop", 6, 1, 60, 1), DEFAULTS);

        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.reason()).contains("already has 1 experiment");
    }

    @Test
    @DisplayName("a higher policy limit permits more concurrent experiments")
    void concurrencyLimitIsPolicyDriven() {
        PolicyLimits generous = new PolicyLimits(10, 3, List.of());

        assertThat(ExperimentSafety.check(
                request(FaultType.POD_KILL, "shop", 6, 1, 60, 2), generous).allowed()).isTrue();
    }

    @Test
    @DisplayName("an experiment longer than the recovery window is refused")
    void durationCapEnforced() {
        ExperimentSafety.Verdict verdict = ExperimentSafety.check(
                request(FaultType.REPLICA_LOSS, "shop", 6, 1, 3600, 0), DEFAULTS);

        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.reason()).contains("exceeds the 900s maximum");
    }

    @Test
    @DisplayName("a zero or negative duration is refused")
    void durationMustBePositive() {
        assertThat(ExperimentSafety.check(
                request(FaultType.POD_KILL, "shop", 6, 1, 0, 0), DEFAULTS).allowed()).isFalse();
    }

    @Test
    @DisplayName("a dependency outage may take its target to zero, which is the point of it")
    void dependencyOutageBypassesBlastRadius() {
        ExperimentSafety.Verdict verdict = ExperimentSafety.check(
                request(FaultType.DEPENDENCY_OUTAGE, "shop", 1, 1, 60, 0), DEFAULTS);

        assertThat(verdict.allowed()).isTrue();
        assertThat(verdict.reason()).contains("restored to 1");
    }

    @Test
    @DisplayName("a dependency already at zero has nothing to take down")
    void dependencyAlreadyDownRefused() {
        ExperimentSafety.Verdict verdict = ExperimentSafety.check(
                request(FaultType.DEPENDENCY_OUTAGE, "shop", 0, 1, 60, 0), DEFAULTS);

        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.reason()).contains("already at zero");
    }
}
