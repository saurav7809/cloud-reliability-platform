package io.aegiscloud.controlplane.engine;

import io.aegiscloud.controlplane.k8s.WorkloadOperations.PodObservation;

import java.util.ArrayList;
import java.util.List;

/**
 * Failure classification, as a pure function of the observed pods.
 *
 * <p>The distinction that matters here is between failures a restart can clear and
 * failures a restart only repeats. A crash-looping pod may well come back healthy
 * somewhere else; a pod that cannot pull its image will fail identically forever, and
 * deleting it in a loop turns a broken deployment into a broken deployment plus
 * churn. Those cases are escalated instead — recorded, surfaced, and left alone.
 */
public final class SelfHealingEngine {

    /**
     * Restarts after which the platform stops trying. Past this, the pod is not
     * failing transiently, and repeating the same remedy is not self-healing.
     */
    static final int RESTART_ESCALATION_THRESHOLD = 5;

    private SelfHealingEngine() {
    }

    /** The failure classes the engine distinguishes; the value written to healing_event.reason. */
    public enum Failure {
        CRASH_LOOP,
        OOM_KILLED,
        IMAGE_PULL_FAILURE,
        UNSCHEDULABLE,
        CONFIG_ERROR,
        REPEATED_RESTARTS
    }

    /**
     * @param action {@link ActionType#RESTART_POD} when replacing the pod is a
     *               plausible remedy, {@link ActionType#ESCALATE} when it is not
     */
    public record Diagnosis(String podName, Failure failure, ActionType action, String reason) {

        /** The healing_event.action_taken value for this diagnosis when it is carried out. */
        public String actionTaken() {
            return action == ActionType.RESTART_POD ? "RESTARTED" : "ESCALATED";
        }
    }

    /** Classifies every unhealthy pod in an observation. Healthy pods are omitted. */
    public static List<Diagnosis> diagnose(List<PodObservation> pods) {
        List<Diagnosis> found = new ArrayList<>();
        for (PodObservation pod : pods) {
            classify(pod).ifPresent(found::add);
        }
        return found;
    }

    private static java.util.Optional<Diagnosis> classify(PodObservation pod) {
        String reason = pod.reason() == null ? "" : pod.reason();

        if (reason.contains("ImagePull") || reason.contains("ErrImage") || reason.equals("InvalidImageName")) {
            return java.util.Optional.of(new Diagnosis(pod.name(), Failure.IMAGE_PULL_FAILURE,
                    ActionType.ESCALATE,
                    "pod cannot obtain its image (" + reason + "); a restart would fail identically, "
                            + "so this needs the image or its credentials fixed"));
        }

        if (reason.equals("Unschedulable")) {
            return java.util.Optional.of(new Diagnosis(pod.name(), Failure.UNSCHEDULABLE,
                    ActionType.ESCALATE,
                    "pod cannot be scheduled; the cluster has no node that satisfies it, which "
                            + "deleting the pod does not change"));
        }

        if (reason.startsWith("CreateContainerConfigError") || reason.startsWith("CreateContainerError")) {
            return java.util.Optional.of(new Diagnosis(pod.name(), Failure.CONFIG_ERROR,
                    ActionType.ESCALATE,
                    "pod cannot be created from its configuration (" + reason
                            + "); the spec or a referenced secret is wrong"));
        }

        if (reason.equals("CrashLoopBackOff")) {
            return java.util.Optional.of(escalateIfPersistent(pod, Failure.CRASH_LOOP,
                    "pod is crash-looping after " + pod.restarts() + " restarts"));
        }

        if (reason.equals("OOMKilled")) {
            return java.util.Optional.of(escalateIfPersistent(pod, Failure.OOM_KILLED,
                    "pod was killed for exceeding its memory limit (" + pod.restarts() + " restarts)"));
        }

        if (pod.restarts() >= RESTART_ESCALATION_THRESHOLD && !pod.ready()) {
            return java.util.Optional.of(new Diagnosis(pod.name(), Failure.REPEATED_RESTARTS,
                    ActionType.ESCALATE,
                    "pod has restarted " + pod.restarts() + " times and is still not ready; "
                            + "this is not a transient failure"));
        }

        return java.util.Optional.empty();
    }

    /**
     * Replaces the pod the first few times, then stops.
     *
     * <p>A restart is a hypothesis — that the failure was local to this pod. Each
     * repetition is evidence against it, and past the threshold the platform stops
     * asserting a hypothesis its own history has disproved.
     */
    private static Diagnosis escalateIfPersistent(PodObservation pod, Failure failure, String observed) {
        if (pod.restarts() >= RESTART_ESCALATION_THRESHOLD) {
            return new Diagnosis(pod.name(), failure, ActionType.ESCALATE,
                    observed + "; past " + RESTART_ESCALATION_THRESHOLD
                            + " restarts a replacement has stopped being a plausible remedy");
        }
        return new Diagnosis(pod.name(), failure, ActionType.RESTART_POD,
                observed + "; replacing it so the ReplicaSet reschedules a fresh one");
    }
}
