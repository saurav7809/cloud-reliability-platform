package io.aegiscloud.controlplane.engine;

import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * The gate every autonomous action passes through before it reaches a cluster.
 *
 * <p>Two properties matter more than the rules themselves. First, the check is
 * mandatory: the engines cannot act except by asking this class, so there is no path
 * to a cluster that skips the guardrails. Second, a rejection is recorded with its
 * reason, so a decision the platform declined to carry out is as visible as one it
 * carried out.
 */
@Service
public class PolicyEngine {

    private final ControlPlaneStore store;

    public PolicyEngine(ControlPlaneStore store) {
        this.store = store;
    }

    /**
     * @param reason why the action was allowed or refused, in the words an operator
     *               reading the action ledger needs to see
     */
    public record Decision(boolean allowed, String reason) {

        public static Decision allow(String reason) {
            return new Decision(true, reason);
        }

        public static Decision refuse(String reason) {
            return new Decision(false, reason);
        }
    }

    /** Checks a proposed replica change for a target. */
    public Decision checkScale(ManagedTarget target, int proposedReplicas) {
        PolicyLimits limits = store.limitsFor(target.clusterId());

        Decision namespaceCheck = checkNamespace(target, limits);
        if (!namespaceCheck.allowed()) {
            return namespaceCheck;
        }

        if (proposedReplicas < 1) {
            // Scaling to zero is a deliberate act of taking a service down. The
            // control plane is not permitted to decide that on its own, whatever
            // the metrics say.
            return Decision.refuse("refusing to scale " + target.workload()
                    + " to " + proposedReplicas + ": autonomous scaling never goes below 1 replica");
        }

        if (proposedReplicas > limits.maxReplicas()) {
            return Decision.refuse("refusing to scale " + target.workload() + " to "
                    + proposedReplicas + ": policy caps this cluster at " + limits.maxReplicas()
                    + " replicas");
        }

        return Decision.allow("within policy: " + proposedReplicas + " of at most "
                + limits.maxReplicas() + " replicas");
    }

    /** Checks a proposed pod replacement for a target. */
    public Decision checkHeal(ManagedTarget target) {
        PolicyLimits limits = store.limitsFor(target.clusterId());

        Decision namespaceCheck = checkNamespace(target, limits);
        if (!namespaceCheck.allowed()) {
            return namespaceCheck;
        }

        return Decision.allow("namespace " + target.namespace() + " is not protected");
    }

    private Decision checkNamespace(ManagedTarget target, PolicyLimits limits) {
        boolean protectedNamespace = limits.protectedNamespaces().stream()
                .anyMatch(n -> n.toLowerCase(Locale.ROOT).equals(
                        target.namespace().toLowerCase(Locale.ROOT)));

        if (protectedNamespace) {
            return Decision.refuse("namespace " + target.namespace()
                    + " is protected by policy; the control plane does not act in it");
        }
        return Decision.allow("namespace " + target.namespace() + " is not protected");
    }
}
