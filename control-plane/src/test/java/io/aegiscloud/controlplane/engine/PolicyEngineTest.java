package io.aegiscloud.controlplane.engine;

import io.aegiscloud.controlplane.domain.Models;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The guardrails, checked against the cases they exist to refuse. */
class PolicyEngineTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private static ManagedTarget target(String namespace, int replicas) {
        return new ManagedTarget(UUID.randomUUID(), "checkout", CLUSTER, "prod-eu",
                "kind-aegiscloud-local", namespace, "checkout", Models.ScalingStrategy.CPU,
                replicas, OptionalDouble.of(300));
    }

    private static PolicyEngine engineWith(PolicyLimits limits) {
        ControlPlaneStore store = mock(ControlPlaneStore.class);
        when(store.limitsFor(any())).thenReturn(limits);
        return new PolicyEngine(store);
    }

    @Test
    @DisplayName("a replica count within the cap is allowed")
    void withinCapIsAllowed() {
        PolicyEngine engine = engineWith(new PolicyLimits(10, 1, List.of()));

        PolicyEngine.Decision decision = engine.checkScale(target("shop", 3), 5);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reason()).contains("at most 10");
    }

    @Test
    @DisplayName("the policy cap refuses the scale-up, and says what the cap is")
    void aboveCapIsRefused() {
        PolicyEngine engine = engineWith(new PolicyLimits(4, 1, List.of()));

        PolicyEngine.Decision decision = engine.checkScale(target("shop", 4), 6);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("caps this cluster at 4 replicas");
    }

    @Test
    @DisplayName("scaling to zero is refused however the engine arrived at it")
    void scaleToZeroIsRefused() {
        PolicyEngine engine = engineWith(new PolicyLimits(10, 1, List.of()));

        PolicyEngine.Decision decision = engine.checkScale(target("shop", 1), 0);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("never goes below 1 replica");
    }

    @Test
    @DisplayName("a protected namespace refuses both scaling and healing")
    void protectedNamespaceIsRefused() {
        PolicyEngine engine = engineWith(new PolicyLimits(10, 1, List.of("kube-system", "istio-system")));

        assertThat(engine.checkScale(target("kube-system", 2), 3).allowed()).isFalse();
        assertThat(engine.checkHeal(target("istio-system", 2)).allowed()).isFalse();
        assertThat(engine.checkHeal(target("shop", 2)).allowed()).isTrue();
    }

    @Test
    @DisplayName("namespace protection is not case-sensitive")
    void protectedNamespaceIgnoresCase() {
        PolicyEngine engine = engineWith(new PolicyLimits(10, 1, List.of("Kube-System")));

        assertThat(engine.checkHeal(target("kube-system", 2)).allowed()).isFalse();
    }

    @Test
    @DisplayName("an unconfigured cluster is still governed by the schema defaults")
    void defaultsStillGovern() {
        PolicyLimits defaults = PolicyLimits.defaults();

        assertThat(defaults.maxReplicas()).isEqualTo(10);
        assertThat(engineWith(defaults).checkScale(target("shop", 9), 11).allowed()).isFalse();
    }
}
