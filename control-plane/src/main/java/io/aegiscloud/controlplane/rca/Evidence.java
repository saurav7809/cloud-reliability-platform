package io.aegiscloud.controlplane.rca;

/**
 * One fact supporting or weakening a candidate root cause.
 *
 * <p>FR-29 is the reason this type exists at all: a verdict that cannot cite its
 * evidence must not be shown. Making evidence a first-class value, rather than a
 * sentence assembled at the end, means a verdict physically cannot be produced
 * without the facts it rests on — the confidence is computed <em>from</em> these, so
 * an empty list yields no verdict rather than an unexplained one.
 *
 * @param signal      which of the four signal classes in FR-28 this belongs to
 * @param weight      contribution to confidence, negative when the fact argues
 *                    against the candidate
 * @param description what an operator reads, naming the actual measurement or event
 *                    rather than summarising it
 */
public record Evidence(Signal signal, double weight, String description) {

    /**
     * The signal classes RCA correlates across (FR-28).
     *
     * <p>Deliberately separate rather than collapsed into one score: an incident
     * where three signals agree is a different kind of claim from one where a single
     * signal is shouting, and an operator can only tell those apart if the breakdown
     * survives to the verdict.
     */
    public enum Signal {
        /** Where the candidate sits relative to the other degraded services. */
        GRAPH_POSITION,
        /** What degraded first. A symptom cannot precede its cause. */
        TEMPORAL_ORDER,
        /** Deployments, scaling actions and healing events close to the incident. */
        CHANGE_EVENT,
        /** Pod-level trouble: restarts, crash loops, unschedulable pods. */
        RESOURCE_SATURATION
    }

    public static Evidence of(Signal signal, double weight, String description) {
        return new Evidence(signal, weight, description);
    }
}
