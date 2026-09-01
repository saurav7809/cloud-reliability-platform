package io.aegiscloud.controlplane.k8s;

/**
 * The outcome of probing a cluster's Kubernetes API.
 *
 * <p>{@code reachable} is what the probe actually observed, never a stored value.
 * The distinction matters: a cluster row can say HEALTHY because someone typed it
 * during registration, and reporting that as live reachability is how an operator
 * console ends up lying about an outage.
 */
public record ClusterConnectivity(
        boolean reachable,
        int nodeCount,
        int readyNodeCount,
        String k8sVersion,
        String detail) {

    public static ClusterConnectivity reachable(int nodeCount, int readyNodeCount, String version) {
        return new ClusterConnectivity(true, nodeCount, readyNodeCount, version,
                readyNodeCount + "/" + nodeCount + " nodes ready");
    }

    public static ClusterConnectivity unreachable(String detail) {
        return new ClusterConnectivity(false, 0, 0, null, detail);
    }
}
