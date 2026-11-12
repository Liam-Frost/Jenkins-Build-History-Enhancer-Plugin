package io.jenkins.plugins.bhe;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Advanced filter parameters: build results, trigger types, time ranges, and special flags.
 *
 * <p>All filters combine with AND semantics. An empty filter matches everything; selectively
 * unchecked options narrow the result set.
 */
public final class AdvancedFilter {

    /** Build status categories: success, unstable, failure, aborted, not-built, or currently building. */
    public enum Status {
        SUCCESS,
        UNSTABLE,
        FAILURE,
        ABORTED,
        NOT_BUILT,
        BUILDING
    }

    /** Trigger type categories, mapped from Jenkins cause classes. */
    public enum Trigger {
        USER, // UserIdCause, UserCause
        TIMER, // TimerTrigger.TimerTriggerCause
        SCM, // SCMTrigger.SCMTriggerCause
        UPSTREAM, // UpstreamCause
        REMOTE, // RemoteCause
        OTHER // Everything else, including plugin causes
    }

    /** Predefined time periods, relative to request time. */
    public enum Period {
        DAY(86400000L), // 24 hours
        WEEK(604800000L), // 7 days
        MONTH(2592000000L); // 30 days

        private final long milliseconds;

        Period(long milliseconds) {
            this.milliseconds = milliseconds;
        }

        public long getMilliseconds() {
            return milliseconds;
        }
    }

    private static final int MAX_ITEMS = 20;

    private final Set<Status> statuses;
    private final Set<Trigger> triggers;
    private final Long sinceTimestamp;
    private final Long untilTimestamp;
    private final boolean keepForeverOnly;

    private AdvancedFilter(
            @NonNull Set<Status> statuses,
            @NonNull Set<Trigger> triggers,
            @CheckForNull Long sinceTimestamp,
            @CheckForNull Long untilTimestamp,
            boolean keepForeverOnly) {
        this.statuses = Collections.unmodifiableSet(statuses);
        this.triggers = Collections.unmodifiableSet(triggers);
        this.sinceTimestamp = sinceTimestamp;
        this.untilTimestamp = untilTimestamp;
        this.keepForeverOnly = keepForeverOnly;
    }

    /**
     * Parses advanced filter parameters from query string values.
     *
     * @param statusesParam comma-separated status enum names, or {@code null}
     * @param triggersParam comma-separated trigger enum names, or {@code null}
     * @param periodParam period enum name (DAY, WEEK, MONTH), or {@code null}
     * @param keepOnlyParam "true" to filter kept-forever builds only, or {@code null}
     * @return parsed filter with whitelisted values; unrecognized names are dropped
     */
    @NonNull
    public static AdvancedFilter parse(
            @CheckForNull String statusesParam,
            @CheckForNull String triggersParam,
            @CheckForNull String periodParam,
            @CheckForNull String keepOnlyParam) {
        return parse(statusesParam, triggersParam, periodParam, null, null, keepOnlyParam);
    }

    /**
     * Parses advanced filter parameters from query string values with custom time range.
     *
     * @param statusesParam comma-separated status enum names, or {@code null}
     * @param triggersParam comma-separated trigger enum names, or {@code null}
     * @param periodParam period enum name (DAY, WEEK, MONTH), or {@code null}
     * @param fromMillisParam custom range start (epoch millis), or {@code null}
     * @param toMillisParam custom range end (epoch millis), or {@code null}
     * @param keepOnlyParam "true" to filter kept-forever builds only, or {@code null}
     * @return parsed filter with whitelisted values; unrecognized names are dropped
     */
    @NonNull
    public static AdvancedFilter parse(
            @CheckForNull String statusesParam,
            @CheckForNull String triggersParam,
            @CheckForNull String periodParam,
            @CheckForNull String fromMillisParam,
            @CheckForNull String toMillisParam,
            @CheckForNull String keepOnlyParam) {

        Set<Status> statuses = parseEnumSet(Status.class, statusesParam);
        Set<Trigger> triggers = parseEnumSet(Trigger.class, triggersParam);

        // Period takes precedence over custom range
        Long since = parsePeriod(periodParam);
        Long until = null;
        if (since == null) {
            since = parseMillis(fromMillisParam);
            until = parseMillis(toMillisParam);
        }

        boolean keepOnly = Boolean.parseBoolean(keepOnlyParam);

        return new AdvancedFilter(statuses, triggers, since, until, keepOnly);
    }

    /** Empty filter: matches everything. */
    @NonNull
    public static AdvancedFilter empty() {
        return new AdvancedFilter(EnumSet.allOf(Status.class), EnumSet.allOf(Trigger.class), null, null, false);
    }

    /** True when no advanced filters are active. */
    public boolean isEmpty() {
        return statuses.size() == Status.values().length
                && triggers.size() == Trigger.values().length
                && sinceTimestamp == null
                && untilTimestamp == null
                && !keepForeverOnly;
    }

    /** Status categories to match. Empty means nothing matches. */
    @NonNull
    public Set<Status> getStatuses() {
        return statuses;
    }

    /** Trigger types to match. Empty means nothing matches. */
    @NonNull
    public Set<Trigger> getTriggers() {
        return triggers;
    }

    /** Inclusive lower timestamp bound (milliseconds), or {@code null} for all time. */
    @CheckForNull
    public Long getSinceTimestamp() {
        return sinceTimestamp;
    }

    /** Inclusive upper timestamp bound (milliseconds), or {@code null} for no upper bound. */
    @CheckForNull
    public Long getUntilTimestamp() {
        return untilTimestamp;
    }

    /** True to match only builds marked {@code isKeepLog()}. */
    public boolean isKeepForeverOnly() {
        return keepForeverOnly;
    }

    @NonNull
    private static <E extends Enum<E>> Set<E> parseEnumSet(@NonNull Class<E> enumClass, @CheckForNull String csv) {
        if (csv == null || csv.isBlank()) {
            return EnumSet.allOf(enumClass);
        }
        // A present but unrecognisable value selects nothing rather than everything: a narrowing
        // request must never silently widen into "all builds".
        return Arrays.stream(csv.split(",", MAX_ITEMS + 1))
                .limit(MAX_ITEMS)
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .map(s -> safeValueOf(enumClass, s))
                .filter(e -> e != null)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(enumClass)));
    }

    @CheckForNull
    private static <E extends Enum<E>> E safeValueOf(@NonNull Class<E> enumClass, @NonNull String name) {
        try {
            return Enum.valueOf(enumClass, name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @CheckForNull
    private static Long parsePeriod(@CheckForNull String param) {
        if (param == null || param.isBlank()) {
            return null;
        }
        Period period = safeValueOf(Period.class, param.trim().toUpperCase(Locale.ROOT));
        if (period == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        long cutoff = now - period.getMilliseconds();
        return cutoff > 0 ? cutoff : 0L;
    }

    /** Reads a non-negative epoch-millis value; anything else means "no bound". */
    @CheckForNull
    private static Long parseMillis(@CheckForNull String param) {
        if (param == null || param.isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(param.trim());
            return value >= 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
