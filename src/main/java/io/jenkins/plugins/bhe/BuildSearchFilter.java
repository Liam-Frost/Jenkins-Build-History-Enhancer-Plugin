package io.jenkins.plugins.bhe;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Cause;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.User;
import hudson.search.UserSearchProperty;
import hudson.triggers.SCMTrigger;
import hudson.triggers.TimerTrigger;
import java.util.List;
import java.util.Locale;
import jenkins.model.IdStrategy;
import jenkins.model.Jenkins;

/**
 * Decides whether a single run matches the active filters.
 *
 * <p>All filters combine with AND semantics: with "only my builds" on, a search for someone else's
 * ID yields nothing rather than quietly widening the result set, and the same holds for the
 * advanced filter's status, trigger, time and keep-forever conditions.
 *
 * <p>Build parameter values are not searched. Native Jenkins search does include them, but exposing
 * them through this plugin's API would leak parameter contents to anyone with Job/Read; see README.
 */
public final class BuildSearchFilter {

    private final SearchQuery query;
    private final String currentUserId;
    private final boolean onlyMine;
    private final AdvancedFilter advanced;

    /**
     * @param query parsed search box value
     * @param currentUserId authenticated user's Jenkins ID, or {@code null} when anonymous. Always
     *     derived server-side from the security context, never from a request parameter.
     * @param onlyMine whether the "only my builds" checkbox is on
     * @param advanced advanced filter parameters (result, trigger, time, flags)
     */
    public BuildSearchFilter(
            @NonNull SearchQuery query,
            @CheckForNull String currentUserId,
            boolean onlyMine,
            @NonNull AdvancedFilter advanced) {
        this.query = query;
        this.currentUserId = currentUserId;
        this.onlyMine = onlyMine;
        this.advanced = advanced;
    }

    /** Convenience constructor for tests and backward compatibility. */
    public BuildSearchFilter(@NonNull SearchQuery query, @CheckForNull String currentUserId, boolean onlyMine) {
        this(query, currentUserId, onlyMine, AdvancedFilter.empty());
    }

    /** True when no filter is active, i.e. the native widget could handle this request unchanged. */
    public boolean isPassThrough() {
        return !onlyMine && query.isEmpty() && advanced.isEmpty();
    }

    /**
     * True when "only my builds" is on but nobody is logged in.
     *
     * <p>In that case no build can match: an anonymous session has no identity to attribute builds
     * to, and treating every anonymous or system build as "mine" would be wrong.
     */
    public boolean isImpossible() {
        return onlyMine && (currentUserId == null || currentUserId.isBlank());
    }

    /** Tests one run against the active filters. */
    public boolean matches(@NonNull Run<?, ?> run) {
        if (isImpossible()) {
            return false;
        }
        BuildInitiator initiator = BuildInitiatorResolver.resolve(run);

        if (onlyMine && !isOwnedByCurrentUser(initiator)) {
            return false;
        }
        if (!matchesAdvanced(run, initiator)) {
            return false;
        }
        if (query.isEmpty()) {
            return true;
        }
        if (query.isUserSearch()) {
            return matchesInitiator(initiator, query.getUserTerm());
        }
        return matchesText(run, initiator, query.getText());
    }

    /**
     * The advanced filter's conditions: status, trigger, time and keep-forever.
     *
     * <p>Cheapest checks first. The status and timestamp are fields on the run; causes may need the
     * build record, so they come last.
     */
    private boolean matchesAdvanced(@NonNull Run<?, ?> run, @NonNull BuildInitiator initiator) {
        if (advanced.isEmpty()) {
            return true;
        }
        if (advanced.isKeepForeverOnly() && !run.isKeepLog()) {
            return false;
        }
        Long since = advanced.getSinceTimestamp();
        if (since != null && run.getTimeInMillis() < since) {
            return false;
        }
        Long until = advanced.getUntilTimestamp();
        if (until != null && run.getTimeInMillis() > until) {
            return false;
        }
        if (!advanced.getStatuses().contains(statusOf(run))) {
            return false;
        }
        if (advanced.getTriggers().size() < AdvancedFilter.Trigger.values().length) {
            return matchesTriggers(run);
        }
        return true;
    }

    /** The status bucket a run falls into. A running build has no result yet, so it is BUILDING. */
    @NonNull
    static AdvancedFilter.Status statusOf(@NonNull Run<?, ?> run) {
        if (run.isBuilding()) {
            return AdvancedFilter.Status.BUILDING;
        }
        Result result = run.getResult();
        if (result == Result.SUCCESS) {
            return AdvancedFilter.Status.SUCCESS;
        }
        if (result == Result.UNSTABLE) {
            return AdvancedFilter.Status.UNSTABLE;
        }
        if (result == Result.FAILURE) {
            return AdvancedFilter.Status.FAILURE;
        }
        if (result == Result.ABORTED) {
            return AdvancedFilter.Status.ABORTED;
        }
        // NOT_BUILT, or a finished build with no recorded result.
        return AdvancedFilter.Status.NOT_BUILT;
    }

    /**
     * A build matches when any of its causes is in a selected trigger category. A build started by
     * a timer and later replayed by a user carries both causes and matches either choice.
     */
    private boolean matchesTriggers(@NonNull Run<?, ?> run) {
        List<Cause> causes;
        try {
            causes = run.getCauses();
        } catch (RuntimeException e) {
            causes = null;
        }
        if (causes == null || causes.isEmpty()) {
            return advanced.getTriggers().contains(AdvancedFilter.Trigger.OTHER);
        }
        for (Cause cause : causes) {
            if (advanced.getTriggers().contains(triggerOf(cause))) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    static AdvancedFilter.Trigger triggerOf(@NonNull Cause cause) {
        if (cause instanceof Cause.UserIdCause || cause instanceof Cause.UserCause) {
            return AdvancedFilter.Trigger.USER;
        }
        if (cause instanceof TimerTrigger.TimerTriggerCause) {
            return AdvancedFilter.Trigger.TIMER;
        }
        if (cause instanceof SCMTrigger.SCMTriggerCause) {
            return AdvancedFilter.Trigger.SCM;
        }
        if (cause instanceof Cause.UpstreamCause) {
            return AdvancedFilter.Trigger.UPSTREAM;
        }
        if (cause instanceof Cause.RemoteCause) {
            return AdvancedFilter.Trigger.REMOTE;
        }
        return AdvancedFilter.Trigger.OTHER;
    }

    /** The advanced filter in force, so the history walk can stop at the time bound. */
    @NonNull
    AdvancedFilter getAdvanced() {
        return advanced;
    }

    private boolean isOwnedByCurrentUser(@NonNull BuildInitiator initiator) {
        String initiatorId = initiator.getUserId();
        return initiator.getType() == BuildInitiator.Type.USER
                && currentUserId != null
                && initiatorId != null
                && sameUser(currentUserId, initiatorId);
    }

    /**
     * Compares user IDs the way the security realm does.
     *
     * <p>LDAP and Active Directory realms usually treat IDs case-insensitively, so a build recorded
     * as {@code frost} belongs to a user who logged in as {@code Frost}. A plain {@code equals}
     * would hide that user's own builds. The realm's {@link IdStrategy} is the authority on this.
     */
    private static boolean sameUser(@NonNull String a, @NonNull String b) {
        if (Jenkins.getInstanceOrNull() == null) {
            return a.equals(b);
        }
        return User.idStrategy().equals(a, b);
    }

    /** {@code user:<term>} matches the initiator's ID or display name, and nothing else. */
    private boolean matchesInitiator(@NonNull BuildInitiator initiator, @CheckForNull String term) {
        if (term == null) {
            return true;
        }
        return contains(initiator.getUserId(), term) || contains(initiator.getDisplayName(), term);
    }

    private boolean matchesText(@NonNull Run<?, ?> run, @NonNull BuildInitiator initiator, @CheckForNull String term) {
        if (term == null) {
            return true;
        }
        if (contains(Integer.toString(run.getNumber()), term)) {
            return true;
        }
        if (contains(safeDisplayName(run), term)) {
            return true;
        }
        if (contains(safeDescription(run), term)) {
            return true;
        }
        // Free text also matches the initiator so that typing a bare name works, as asked for in
        // the brief. user:<id> remains the way to search initiators exclusively.
        return matchesInitiator(initiator, term);
    }

    @CheckForNull
    private static String safeDisplayName(@NonNull Run<?, ?> run) {
        try {
            return run.getDisplayName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @CheckForNull
    private static String safeDescription(@NonNull Run<?, ?> run) {
        try {
            return run.getDescription();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Substring containment, honouring the user's own case-sensitivity preference so that search
     * behaves the same as elsewhere in Jenkins.
     */
    private static boolean contains(@CheckForNull String haystack, @NonNull String needle) {
        if (haystack == null || haystack.isEmpty()) {
            return false;
        }
        if (UserSearchProperty.isCaseInsensitive()) {
            return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
        }
        return haystack.contains(needle);
    }
}
