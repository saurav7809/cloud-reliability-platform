package io.aegiscloud.controlplane.engine;

/**
 * How much the platform is permitted to do on its own, per cluster and action type.
 *
 * <p>The default everywhere is {@link #SUGGEST} (FR-36). An autonomous platform that
 * arrives acting is not trustworthy; it earns ACT one action type at a time, once its
 * recorded outcomes show the decisions were right.
 */
public enum AutonomyLevel {

    /** Watch and record. Nothing is proposed and nothing is done. */
    OBSERVE,

    /** Decide and record the proposal, policy-checked, but do not touch the cluster. */
    SUGGEST,

    /** Decide, policy-check, act, and verify the result afterwards. */
    ACT
}
