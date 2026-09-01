package io.aegiscloud.controlplane.experiment;

/**
 * The faults the Experiment Engine can inject.
 *
 * <p>Every one of these is performed through the Kubernetes API alone, which is what
 * keeps the cloud-agnostic boundary intact and means an experiment behaves the same
 * on kind and on EKS. It is also the honest limit of what can be injected without
 * additional machinery.
 *
 * <p><b>What is deliberately absent.</b> Network latency, packet loss, partitions and
 * in-container CPU or memory pressure cannot be produced through the Kubernetes API:
 * they need a privileged agent on the node, which is what Chaos Mesh installs. Rather
 * than approximate them with something that merely looks similar - restarting a pod
 * is not a network partition, and a resource-limit edit is not CPU contention -
 * those fault types are left unimplemented until Chaos Mesh is present. A chaos
 * engine whose faults do not do what their names say produces experiments whose
 * conclusions are wrong, and Phase 8's RCA is measured against exactly these
 * conclusions.
 */
public enum FaultType {

    /**
     * Deletes one or more pods of the target under test.
     *
     * <p>The classic experiment: does the workload survive losing an instance, and
     * how fast does the platform notice and replace it.
     */
    POD_KILL,

    /**
     * Reduces the target's replica count for the duration of the experiment.
     *
     * <p>Distinct from POD_KILL because the controller does not immediately replace
     * what was removed. This tests whether the remaining replicas can carry the load,
     * which is a capacity question rather than a recovery one.
     */
    REPLICA_LOSS,

    /**
     * Scales a dependency to zero for the duration.
     *
     * <p>The experiment that matters most for Phase 8: it produces a failure in one
     * service and symptoms in others, which is precisely the situation root-cause
     * analysis has to get right. Because the engine knows which service it took
     * down, the correct answer is known in advance.
     */
    DEPENDENCY_OUTAGE
}
