package io.jenkins.plugins.bhe;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A parsed search box value.
 *
 * <p>Two forms are recognised:
 *
 * <ul>
 *   <li>{@code user:<id>} — match on the initiator's user ID or display name only
 *   <li>anything else — free text matched against build number, display name, description and
 *       initiator
 * </ul>
 *
 * Input is length-capped so that a pasted blob cannot turn into an expensive scan, and is never
 * treated as a pattern: all matching is plain substring containment.
 */
public final class SearchQuery {

    /** Longest accepted search string. Anything beyond this is cut. */
    static final int MAX_LENGTH = 200;

    private static final String USER_PREFIX = "user:";

    private final String text;
    private final String userTerm;

    private SearchQuery(@CheckForNull String text, @CheckForNull String userTerm) {
        this.text = text;
        this.userTerm = userTerm;
    }

    /** Parses a raw search box value, which may be {@code null} or blank. */
    @NonNull
    public static SearchQuery parse(@CheckForNull String raw) {
        if (raw == null) {
            return new SearchQuery(null, null);
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_LENGTH) {
            trimmed = trimmed.substring(0, MAX_LENGTH);
        }
        if (trimmed.isEmpty()) {
            return new SearchQuery(null, null);
        }
        if (trimmed.regionMatches(true, 0, USER_PREFIX, 0, USER_PREFIX.length())) {
            String userTerm = trimmed.substring(USER_PREFIX.length()).trim();
            // "user:" with nothing after it is not a user search; treat it as free text so the user
            // sees something sensible mid-typing rather than every build matching.
            if (userTerm.isEmpty()) {
                return new SearchQuery(trimmed, null);
            }
            return new SearchQuery(null, userTerm);
        }
        return new SearchQuery(trimmed, null);
    }

    /** True when there is nothing to filter on. */
    public boolean isEmpty() {
        return text == null && userTerm == null;
    }

    /** True for the {@code user:<id>} form. */
    public boolean isUserSearch() {
        return userTerm != null;
    }

    /** The free-text term, or {@code null} for an empty or user-scoped query. */
    @CheckForNull
    public String getText() {
        return text;
    }

    /** The user term from {@code user:<id>}, or {@code null} when this is not a user search. */
    @CheckForNull
    public String getUserTerm() {
        return userTerm;
    }
}
