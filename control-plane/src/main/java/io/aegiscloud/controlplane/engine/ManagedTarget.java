package io.aegiscloud.controlplane.engine;

import io.aegiscloud.controlplane.domain.Models;

import java.util.OptionalDouble;
import java.util.UUID;

/**
 * One deployment target, joined to everything the control loop needs to act on it.
 *
 * <p>Assembled once per cycle so the engines never re-query per decision, and so a
 * target's cluster context and namespace cannot change underneath a decision that
 * was made about it.
 *
 * @param workload           the Kubernetes Deployment name, which is the target's
 *                           label when it has one and the service name otherwise
 * @param latencyObjectiveMs the p95 objective from the target's SLO, absent when no
 *                           latency SLO is defined — the LATENCY strategy has nothing
 *                           to compare against in that case and declines to act
 */
public record ManagedTarget(
        UUID targetId,
        String serviceName,
        UUID clusterId,
        String clusterName,
        String kubeContext,
        String namespace,
        String workload,
        Models.ScalingStrategy strategy,
        int recordedReplicas,
        OptionalDouble latencyObjectiveMs) {

    /** How this target is named in logs, events and the dashboard. */
    public String label() {
        return serviceName + " @ " + clusterName + "/" + namespace;
    }
}
