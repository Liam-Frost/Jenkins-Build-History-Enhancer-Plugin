package io.jenkins.plugins.bhe;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Run;
import hudson.model.User;
import hudson.search.UserSearchProperty;
import java.util.Locale;
import jenkins.model.IdStrategy;
import jenkins.model.Jenkins;

/**
 * Decides whether a single run matches the active filters.
 *
 * <p>The two filters combine with AND semantics: with "only my builds" on, a search for someone
 * else's ID yields nothing rather than quietly widening the result set.
 *
 * <p>Build parameter values are not searched. Native Jenkins search does include them, but exposing
 * them through this plugin's API would leak parameter contents to anyone with Job/Read; see README.
 */
public final class BuildSearchFilter {

    private final SearchQuery query;
    private final String currentUserId;
    private final boolean onlyMine;

    /**
     * @param query parsed search box value
     * @param currentUserId authenticated user's Jenkins ID, or {@code null} when anonymous. Always
     *     derived server-side from the security context, never from a request parameter.
     * @param onlyMine whether the "only my builds" checkbox is on
     */
    public BuildSearchFilter(@NonNull SearchQuery query, @CheckForNull String currentUserId, boolean onlyMine) {
        this.query = query;
        this.currentUserId = currentUserId;
        this.onlyMine = onlyMine;
    }

    /** True when no filter is active, i.e. the native widget could handle this request unchanged. */
    public boolean isPassThrough() {
        return !onlyMine && query.isEmpty();
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
        if (query.isEmpty()) {
            return true;
        }
        if (query.isUserSearch()) {
            return matchesInitiator(initiator, query.getUserTerm());
        }
        return matchesText(run, initiator, query.getText());
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
