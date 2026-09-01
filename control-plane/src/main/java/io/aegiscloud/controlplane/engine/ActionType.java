package io.aegiscloud.controlplane.engine;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The autonomous actions the control plane can take.
 *
 * <p>Autonomy is configured per action type, not per cluster as a whole: replacing a
 * crash-looping pod is a far smaller commitment than changing a workload's size, and
 * an operator has every reason to permit one unattended while withholding the other.
 */
public enum ActionType {

    SCALE_UP,
    SCALE_DOWN,
    /** Delete a failing pod so its ReplicaSet recreates it. */
    RESTART_POD,
    /** Raise a failure the platform must not try to fix by itself. */
    ESCALATE;

    /** The set of action types as a PostgreSQL {@code text[]} literal. */
    public static String namesAsPgArray() {
        return Arrays.stream(values()).map(Enum::name)
                .collect(Collectors.joining(",", "{", "}"));
    }
}
