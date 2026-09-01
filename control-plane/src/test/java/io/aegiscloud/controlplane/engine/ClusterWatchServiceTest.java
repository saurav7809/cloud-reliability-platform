package io.aegiscloud.controlplane.engine;

import io.fabric8.kubernetes.api.model.ContainerStateBuilder;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which pod events wake the control loop.
 *
 * <p>The cost of the two mistakes is asymmetric: a false positive spends one
 * observation, a false negative spends detection time. These tests pin that
 * asymmetry down rather than leaving it to be re-argued later.
 */
class ClusterWatchServiceTest {

    private static Pod waiting(String reason) {
        return new PodBuilder()
                .withNewMetadata().withName("web-1").endMetadata()
                .withNewStatus()
                .withPhase("Pending")
                .withContainerStatuses(new ContainerStatusBuilder()
                        .withName("app")
                        .withState(new ContainerStateBuilder()
                                .withNewWaiting().withReason(reason).endWaiting()
                                .build())
                        .build())
                .endStatus()
                .build();
    }

    private static Pod terminated(int exitCode) {
        return new PodBuilder()
                .withNewMetadata().withName("web-1").endMetadata()
                .withNewStatus()
                .withPhase("Running")
                .withContainerStatuses(new ContainerStatusBuilder()
                        .withName("app")
                        .withState(new ContainerStateBuilder()
                                .withNewTerminated().withExitCode(exitCode).endTerminated()
                                .build())
                        .build())
                .endStatus()
                .build();
    }

    @Test
    @DisplayName("a crash-looping pod wakes the loop")
    void crashLoopIsUnhealthy() {
        assertThat(ClusterWatchService.isUnhealthy(waiting("CrashLoopBackOff"))).isTrue();
    }

    @Test
    @DisplayName("an unpullable image wakes the loop")
    void imagePullFailureIsUnhealthy() {
        assertThat(ClusterWatchService.isUnhealthy(waiting("ImagePullBackOff"))).isTrue();
        assertThat(ClusterWatchService.isUnhealthy(waiting("ErrImagePull"))).isTrue();
    }

    @Test
    @DisplayName("a pod that is merely starting does not")
    void normalStartupIsNotUnhealthy() {
        assertThat(ClusterWatchService.isUnhealthy(waiting("ContainerCreating"))).isFalse();
        assertThat(ClusterWatchService.isUnhealthy(waiting("PodInitializing"))).isFalse();
    }

    @Test
    @DisplayName("a container that exited non-zero wakes the loop")
    void nonZeroExitIsUnhealthy() {
        assertThat(ClusterWatchService.isUnhealthy(terminated(137))).isTrue();
    }

    @Test
    @DisplayName("a container that exited cleanly does not")
    void cleanExitIsNotUnhealthy() {
        assertThat(ClusterWatchService.isUnhealthy(terminated(0))).isFalse();
    }

    @Test
    @DisplayName("a failed pod phase wakes the loop even with no container detail")
    void failedPhaseIsUnhealthy() {
        Pod pod = new PodBuilder()
                .withNewMetadata().withName("web-1").endMetadata()
                .withNewStatus().withPhase("Failed").endStatus()
                .build();

        assertThat(ClusterWatchService.isUnhealthy(pod)).isTrue();
    }

    @Test
    @DisplayName("a healthy running pod is ignored")
    void runningPodIsIgnored() {
        Pod pod = new PodBuilder()
                .withNewMetadata().withName("web-1").endMetadata()
                .withNewStatus()
                .withPhase("Running")
                .withContainerStatuses(new ContainerStatusBuilder()
                        .withName("app")
                        .withState(new ContainerStateBuilder().withNewRunning().endRunning().build())
                        .build())
                .endStatus()
                .build();

        assertThat(ClusterWatchService.isUnhealthy(pod)).isFalse();
    }

    @Test
    @DisplayName("a pod with no status yet is ignored rather than treated as broken")
    void missingStatusIsIgnored() {
        Pod pod = new PodBuilder().withNewMetadata().withName("web-1").endMetadata().build();

        assertThat(ClusterWatchService.isUnhealthy(pod)).isFalse();
    }
}
