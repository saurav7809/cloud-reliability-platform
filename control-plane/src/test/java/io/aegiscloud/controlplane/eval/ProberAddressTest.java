package io.aegiscloud.controlplane.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cluster address parsing.
 *
 * <p>Worth testing on its own because a malformed address here does not fail loudly:
 * it becomes a probe that reports a service as down when the service is fine.
 */
class ProberAddressTest {

    @Test
    @DisplayName("a full cluster address parses into its four parts")
    void parsesFullAddress() {
        Prober.ClusterAddress address = Prober.ClusterAddress.parse(
                "k8s://aegiscloud/auth-service:80/healthz");

        assertThat(address.namespace()).isEqualTo("aegiscloud");
        assertThat(address.service()).isEqualTo("auth-service");
        assertThat(address.port()).isEqualTo(80);
        assertThat(address.path()).isEqualTo("/healthz");
    }

    @Test
    @DisplayName("an address with no path probes the service root")
    void defaultsToRootPath() {
        Prober.ClusterAddress address = Prober.ClusterAddress.parse("k8s://default/api:8080");

        assertThat(address.path()).isEqualTo("/");
        assertThat(address.port()).isEqualTo(8080);
    }

    @Test
    @DisplayName("a nested path is kept whole")
    void keepsNestedPath() {
        Prober.ClusterAddress address = Prober.ClusterAddress.parse(
                "k8s://shop/checkout:8080/actuator/health/readiness");

        assertThat(address.path()).isEqualTo("/actuator/health/readiness");
    }

    @Test
    @DisplayName("a missing port is rejected rather than guessed")
    void rejectsMissingPort() {
        assertThatThrownBy(() -> Prober.ClusterAddress.parse("k8s://aegiscloud/auth-service/healthz"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }

    @Test
    @DisplayName("a missing service is rejected")
    void rejectsMissingService() {
        assertThatThrownBy(() -> Prober.ClusterAddress.parse("k8s://aegiscloud"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Prober.ClusterAddress.parse("k8s://aegiscloud/:80/healthz"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
    }

    @Test
    @DisplayName("a non-numeric port is rejected")
    void rejectsNonNumericPort() {
        assertThatThrownBy(() -> Prober.ClusterAddress.parse("k8s://ns/svc:http/healthz"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a number");
    }

    @Test
    @DisplayName("an ordinary URL is not a cluster address")
    void rejectsPlainUrl() {
        assertThatThrownBy(() -> Prober.ClusterAddress.parse("https://example.com/healthz"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a cluster address");
    }
}
