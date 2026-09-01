package io.aegiscloud.controlplane.engine;

import java.util.List;

/**
 * The guardrails a cluster's policy row expresses, as the engines consume them.
 *
 * @param protectedNamespaces namespaces the platform will not act in at all —
 *                            {@code kube-system} and anything else an operator has
 *                            declared off limits
 */
public record PolicyLimits(
        int maxReplicas,
        int maxConcurrentExperiments,
        List<String> protectedNamespaces) {

    /**
     * The limits in force when no policy row exists.
     *
     * <p>Matches the column defaults in the schema so that an unconfigured cluster is
     * governed by exactly what the database would have given it.
     */
    public static PolicyLimits defaults() {
        return new PolicyLimits(10, 1, List.of());
    }
}
