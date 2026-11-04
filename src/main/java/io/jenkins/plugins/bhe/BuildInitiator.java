package io.jenkins.plugins.bhe;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/**
 * Who, or what, started a build.
 *
 * <p>Instances are immutable and carry only the fields the sidebar renders. {@link #userId} is only
 * set for {@link Type#USER}; for every other type it is {@code null} so that callers cannot confuse
 * a trigger category with a real Jenkins user ID.
 */
public final class BuildInitiator {

    /** Trigger category of a build. */
    public enum Type {
        /** A {@link hudson.model.Cause.UserIdCause} or {@link hudson.model.Cause.UserCause} directly on the build. */
        USER,
        /** {@link hudson.triggers.TimerTrigger.TimerTriggerCause}. */
        TIMER,
        /** {@link hudson.triggers.SCMTrigger.SCMTriggerCause}. */
        SCM,
        /** {@link hudson.model.Cause.UpstreamCause}. Not traced back to the originating user; see README. */
        UPSTREAM,
        /** A remote/CLI trigger, or a cause contributed by a plugin that we cannot classify further. */
        SYSTEM,
        /** No cause recorded at all. */
        UNKNOWN
    }

    private final Type type;
    private final String userId;
    private final String displayName;

    private BuildInitiator(@NonNull Type type, @CheckForNull String userId, @NonNull String displayName) {
        this.type = Objects.requireNonNull(type);
        this.userId = userId;
        this.displayName = Objects.requireNonNull(displayName);
    }

    /**
     * A build started by a named Jenkins user.
     *
     * @param userId the Jenkins user ID recorded on the cause; never {@code null}
     * @param displayName the user's full name when the account still exists, otherwise the raw ID
     */
    @NonNull
    public static BuildInitiator user(@NonNull String userId, @NonNull String displayName) {
        return new BuildInitiator(Type.USER, userId, displayName);
    }

    /** A build started by something other than a logged-in user. */
    @NonNull
    public static BuildInitiator nonUser(@NonNull Type type, @NonNull String displayName) {
        if (type == Type.USER) {
            throw new IllegalArgumentException("use user() for Type.USER");
        }
        return new BuildInitiator(type, null, displayName);
    }

    @NonNull
    public Type getType() {
        return type;
    }

    /** The Jenkins user ID, or {@code null} for any non-user trigger. */
    @CheckForNull
    public String getUserId() {
        return userId;
    }

    /** Human-readable label for the metadata row. */
    @NonNull
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return "BuildInitiator[" + type + (userId == null ? "" : ", " + userId) + "]";
    }
}
