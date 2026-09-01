package io.aegiscloud.controlplane.experiment;

import io.aegiscloud.controlplane.engine.PolicyLimits;

import java.util.Locale;

/**
 * The rules an experiment must pass before anything is broken on purpose.
 *
 * <p>Chaos engineering is the one part of this platform that deliberately causes
 * harm, so it is the part that most needs a gate it cannot route around. The checks
 * are pure functions of the request and the cluster's policy, which makes them
 * testable and keeps the reasoning in one place instead of scattered through the
 * engine.
 */
public final class ExperimentSafety {

    /**
     * The longest an experiment may run.
     *
     * <p>Not a preference. Every fault here is undone by the engine at the end of the
     * run, so the duration is also the longest a service can be left degraded if the
     * process is killed mid-experiment. Fifteen minutes is the outer edge of what is
     * recoverable by hand without anyone having to guess what was done.
     */
    static final int MAX_DURATION_SECONDS = 900;

    /**
     * The share of a target's replicas an experiment may remove.
     *
     * <p>An experiment that takes down everything is not an experiment; it is an
     * outage with paperwork. The point is to observe a system under partial failure,
     * which requires part of it to still be running.
     */
    static final double MAX_BLAST_RADIUS = 0.5;

    private ExperimentSafety() {
    }

    /** @param reason why the experiment was permitted or refused, recorded either way */
    public record Verdict(boolean allowed, String reason) {

        static Verdict allow(String reason) {
            return new Verdict(true, reason);
        }

        static Verdict refuse(String reason) {
            return new Verdict(false, reason);
        }
    }

    /**
     * @param namespace         where the fault would be injected
     * @param currentReplicas   what the target is running right now
     * @param runningExperiments how many experiments are already in flight on this cluster
     */
    public record Request(
            FaultType faultType,
            String namespace,
            int currentReplicas,
            int magnitude,
            int durationSeconds,
            int runningExperiments) {
    }

    public static Verdict check(Request request, PolicyLimits limits) {
        if (isProtected(request.namespace(), limits)) {
            return Verdict.refuse("namespace " + request.namespace()
                    + " is protected by policy; experiments are not run in it");
        }

        if (request.durationSeconds() <= 0) {
            return Verdict.refuse("an experiment needs a positive duration");
        }

        if (request.durationSeconds() > MAX_DURATION_SECONDS) {
            return Verdict.refuse("duration " + request.durationSeconds() + "s exceeds the "
                    + MAX_DURATION_SECONDS + "s maximum: a fault left injected longer than that "
                    + "cannot be reliably undone by hand if the platform dies mid-run");
        }

        if (request.runningExperiments() >= limits.maxConcurrentExperiments()) {
            // Concurrent experiments make each one uninterpretable: when two faults
            // overlap, neither result tells you which fault caused what.
            return Verdict.refuse("this cluster already has " + request.runningExperiments()
                    + " experiment(s) running and policy allows "
                    + limits.maxConcurrentExperiments());
        }

        if (request.magnitude() < 1) {
            return Verdict.refuse("magnitude must be at least 1");
        }

        return switch (request.faultType()) {
            case POD_KILL, REPLICA_LOSS -> checkBlastRadius(request);
            // A dependency outage takes the dependency to zero by design - that is
            // the fault. It is bounded instead by the dependency being a different
            // target from the one under observation, which the engine enforces.
            case DEPENDENCY_OUTAGE -> checkDependencyOutage(request);
        };
    }

    private static Verdict checkBlastRadius(Request request) {
        if (request.currentReplicas() < 2) {
            return Verdict.refuse("target runs " + request.currentReplicas()
                    + " replica(s); removing any of them is a full outage, not an experiment");
        }

        int allowed = (int) Math.floor(request.currentReplicas() * MAX_BLAST_RADIUS);
        if (request.magnitude() > allowed) {
            return Verdict.refuse("removing " + request.magnitude() + " of "
                    + request.currentReplicas() + " replicas exceeds the "
                    + (int) (MAX_BLAST_RADIUS * 100) + "% blast-radius limit (at most "
                    + allowed + ")");
        }

        return Verdict.allow("removing " + request.magnitude() + " of " + request.currentReplicas()
                + " replicas is within the blast-radius limit");
    }

    private static Verdict checkDependencyOutage(Request request) {
        if (request.currentReplicas() < 1) {
            return Verdict.refuse("the dependency is already at zero replicas; "
                    + "there is nothing to take down");
        }
        return Verdict.allow("dependency will be scaled to zero for "
                + request.durationSeconds() + "s and restored to " + request.currentReplicas());
    }

    private static boolean isProtected(String namespace, PolicyLimits limits) {
        return limits.protectedNamespaces().stream()
                .anyMatch(n -> n.toLowerCase(Locale.ROOT).equals(namespace.toLowerCase(Locale.ROOT)));
    }
}
