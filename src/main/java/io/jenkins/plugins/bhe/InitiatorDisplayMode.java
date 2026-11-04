package io.jenkins.plugins.bhe;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;

/** When the initiator metadata row is shown. */
public enum InitiatorDisplayMode {

    /**
     * Show the initiator for a mixed-user list, hide it while "only my builds" is on because the
     * identity is then already known. A {@code user:} search keeps it visible, since the point of
     * that search is to look at someone else's builds.
     */
    AUTO,

    /** Always show the metadata row. */
    ALWAYS,

    /** Never add a metadata row. */
    NEVER;

    /** Parses a mode name, falling back to {@link #AUTO} for anything unrecognised. */
    @NonNull
    public static InitiatorDisplayMode parse(@CheckForNull String value) {
        if (value == null) {
            return AUTO;
        }
        for (InitiatorDisplayMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        return AUTO;
    }
}
