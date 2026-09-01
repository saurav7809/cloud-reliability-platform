package io.aegiscloud.controlplane.engine;

import io.aegiscloud.controlplane.k8s.WorkloadOperations.PodObservation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Failure classification, and specifically which failures a restart is allowed to answer. */
class SelfHealingEngineTest {

    private static PodObservation pod(String name, boolean ready, int restarts, String reason) {
        return new PodObservation(name, ready ? "Running" : "Pending", ready, restarts, reason);
    }

    @Test
    @DisplayName("healthy pods produce no diagnosis at all")
    void healthyPodsAreIgnored() {
        assertThat(SelfHealingEngine.diagnose(List.of(
                pod("web-1", true, 0, ""),
                pod("web-2", true, 2, ""))))
                .isEmpty();
    }

    @Test
    @DisplayName("a crash-looping pod is replaced")
    void crashLoopIsRestarted() {
        List<SelfHealingEngine.Diagnosis> found =
                SelfHealingEngine.diagnose(List.of(pod("web-1", false, 2, "CrashLoopBackOff")));

        assertThat(found).singleElement().satisfies(d -> {
            assertThat(d.failure()).isEqualTo(SelfHealingEngine.Failure.CRASH_LOOP);
            assertThat(d.action()).isEqualTo(ActionType.RESTART_POD);
            assertThat(d.actionTaken()).isEqualTo("RESTARTED");
        });
    }

    @Test
    @DisplayName("a pod between crash restarts is diagnosed, not only one in CrashLoopBackOff")
    void terminatedWithErrorIsAlsoACrash() {
        // CrashLoopBackOff is only what a crashing pod reports while it waits for its
        // next attempt; between attempts it reports the termination reason instead. A
        // classifier recognising only the waiting state sees the same broken pod as
        // healthy for part of every cycle, and the watch that wakes on a non-zero exit
        // then finds nothing to do.
        for (String reason : List.of("Error", "StartError", "ContainerCannotRun")) {
            List<SelfHealingEngine.Diagnosis> found =
                    SelfHealingEngine.diagnose(List.of(pod("web-1", false, 2, reason)));

            assertThat(found).singleElement().satisfies(d -> {
                assertThat(d.failure()).isEqualTo(SelfHealingEngine.Failure.CRASH_LOOP);
                assertThat(d.action()).isEqualTo(ActionType.RESTART_POD);
            });
        }
    }

    @Test
    @DisplayName("a crash loop that has survived repeated replacement is escalated instead")
    void persistentCrashLoopIsEscalated() {
        List<SelfHealingEngine.Diagnosis> found =
                SelfHealingEngine.diagnose(List.of(pod("web-1", false, 7, "CrashLoopBackOff")));

        assertThat(found).singleElement().satisfies(d -> {
            assertThat(d.action()).isEqualTo(ActionType.ESCALATE);
            assertThat(d.actionTaken()).isEqualTo("ESCALATED");
            assertThat(d.reason()).contains("plausible remedy");
        });
    }

    @Test
    @DisplayName("an image that cannot be pulled is never answered with a restart")
    void imagePullFailureIsEscalated() {
        for (String reason : List.of("ImagePullBackOff", "ErrImagePull", "InvalidImageName")) {
            List<SelfHealingEngine.Diagnosis> found =
                    SelfHealingEngine.diagnose(List.of(pod("web-1", false, 0, reason)));

            assertThat(found).singleElement().satisfies(d -> {
                assertThat(d.failure()).isEqualTo(SelfHealingEngine.Failure.IMAGE_PULL_FAILURE);
                assertThat(d.action()).isEqualTo(ActionType.ESCALATE);
            });
        }
    }

    @Test
    @DisplayName("an unschedulable pod is escalated: deleting it changes nothing")
    void unschedulableIsEscalated() {
        List<SelfHealingEngine.Diagnosis> found =
                SelfHealingEngine.diagnose(List.of(pod("web-1", false, 0, "Unschedulable")));

        assertThat(found).singleElement().satisfies(d -> {
            assertThat(d.failure()).isEqualTo(SelfHealingEngine.Failure.UNSCHEDULABLE);
            assertThat(d.action()).isEqualTo(ActionType.ESCALATE);
        });
    }

    @Test
    @DisplayName("an OOM kill is replaced once, then escalated")
    void oomKillIsRestartedThenEscalated() {
        assertThat(SelfHealingEngine.diagnose(List.of(pod("web-1", false, 1, "OOMKilled"))))
                .singleElement()
                .satisfies(d -> assertThat(d.action()).isEqualTo(ActionType.RESTART_POD));

        assertThat(SelfHealingEngine.diagnose(List.of(pod("web-1", false, 9, "OOMKilled"))))
                .singleElement()
                .satisfies(d -> assertThat(d.action()).isEqualTo(ActionType.ESCALATE));
    }

    @Test
    @DisplayName("a config error is escalated: the spec, not the pod, is wrong")
    void configErrorIsEscalated() {
        List<SelfHealingEngine.Diagnosis> found = SelfHealingEngine.diagnose(
                List.of(pod("web-1", false, 0, "CreateContainerConfigError")));

        assertThat(found).singleElement().satisfies(d ->
                assertThat(d.failure()).isEqualTo(SelfHealingEngine.Failure.CONFIG_ERROR));
    }

    @Test
    @DisplayName("many restarts with no reported reason still count as a failure")
    void repeatedRestartsAreEscalated() {
        List<SelfHealingEngine.Diagnosis> found =
                SelfHealingEngine.diagnose(List.of(pod("web-1", false, 6, "")));

        assertThat(found).singleElement().satisfies(d -> {
            assertThat(d.failure()).isEqualTo(SelfHealingEngine.Failure.REPEATED_RESTARTS);
            assertThat(d.action()).isEqualTo(ActionType.ESCALATE);
        });
    }

    @Test
    @DisplayName("a restarted but now-ready pod is not treated as failing")
    void recoveredPodIsNotDiagnosed() {
        assertThat(SelfHealingEngine.diagnose(List.of(pod("web-1", true, 6, ""))))
                .isEmpty();
    }

    @Test
    @DisplayName("each failing pod in a workload is diagnosed separately")
    void everyFailingPodIsReported() {
        List<SelfHealingEngine.Diagnosis> found = SelfHealingEngine.diagnose(List.of(
                pod("web-1", true, 0, ""),
                pod("web-2", false, 1, "CrashLoopBackOff"),
                pod("web-3", false, 0, "ImagePullBackOff")));

        assertThat(found).hasSize(2);
        assertThat(found).extracting(SelfHealingEngine.Diagnosis::podName)
                .containsExactly("web-2", "web-3");
    }
}
