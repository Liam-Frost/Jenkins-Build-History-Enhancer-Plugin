package io.jenkins.plugins.bhe;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Run;
import java.util.ArrayList;
import java.util.List;

/**
 * Walks a job's build history and returns one page of matching runs.
 *
 * <p>The walk is bounded twice over: it stops once a page is full, and it stops after
 * {@link BuildHistoryEnhancerConfiguration#getMaxScan()} runs have been examined even if the page
 * is still short. The second bound is what keeps a search over a job with tens of thousands of
 * builds from turning into an unbounded disk scan. When it trips, the page is marked truncated and
 * the caller can continue from {@link Page#getNextCursor()}.
 *
 * <p>Paging is by build number, not by offset, so builds appearing or being deleted while the user
 * pages through history cannot make rows duplicate or disappear.
 */
public final class BuildHistoryService {

    /** One page of results plus the cursor needed to continue. */
    public static final class Page {

        private final List<Run<?, ?>> runs;
        private final Integer nextCursor;
        private final boolean hasMore;
        private final boolean truncated;
        private final int scanned;

        Page(
                @NonNull List<Run<?, ?>> runs,
                @CheckForNull Integer nextCursor,
                boolean hasMore,
                boolean truncated,
                int scanned) {
            this.runs = runs;
            this.nextCursor = nextCursor;
            this.hasMore = hasMore;
            this.truncated = truncated;
            this.scanned = scanned;
        }

        /** Matching runs, newest first. */
        @NonNull
        public List<Run<?, ?>> getRuns() {
            return runs;
        }

        /**
         * Exclusive upper bound for the next page, or {@code null} when history is exhausted. The
         * next request should ask for builds strictly older than this number.
         */
        @CheckForNull
        public Integer getNextCursor() {
            return nextCursor;
        }

        /** Whether an older page exists. */
        public boolean isHasMore() {
            return hasMore;
        }

        /** Whether the scan budget ran out before the page filled. */
        public boolean isTruncated() {
            return truncated;
        }

        /** How many runs were examined, for the "searched #a-#b" empty state. */
        public int getScanned() {
            return scanned;
        }
    }

    private BuildHistoryService() {}

    /**
     * Fetches one page of matching builds.
     *
     * @param job the job to search; the caller must already have checked {@link Item#READ}
     * @param filter active search and ownership filters
     * @param before exclusive upper bound on build number, or {@code null} to start at the newest
     * @param pageSize how many matches to return at most
     * @param maxScan how many runs may be examined before giving up on filling the page
     */
    @NonNull
    public static Page search(
            @NonNull Job<?, ?> job,
            @NonNull BuildSearchFilter filter,
            @CheckForNull Integer before,
            int pageSize,
            int maxScan) {

        List<Run<?, ?>> matches = new ArrayList<>();
        if (filter.isImpossible()) {
            // "Only my builds" with nobody logged in: no build can match, so skip the walk.
            return new Page(matches, null, false, false, 0);
        }

        Run<?, ?> cursor = startingRun(job, before);
        int scanned = 0;
        boolean hasMore = false;
        boolean truncated = false;
        Integer nextCursor = null;
        int lastScannedNumber = -1;

        while (cursor != null) {
            if (scanned >= maxScan) {
                // Out of budget. Report where we stopped so the caller can resume from here.
                truncated = true;
                hasMore = true;
                nextCursor = lastScannedNumber;
                break;
            }
            scanned++;
            lastScannedNumber = cursor.getNumber();

            if (isReadable(cursor) && filter.matches(cursor)) {
                if (matches.size() == pageSize) {
                    // One past a full page: an older page exists, and it starts here.
                    hasMore = true;
                    nextCursor = matches.get(matches.size() - 1).getNumber();
                    break;
                }
                matches.add(cursor);
            }
            cursor = previousOf(cursor);
        }

        if (!hasMore) {
            // History exhausted within budget.
            nextCursor = null;
        }
        return new Page(matches, nextCursor, hasMore, truncated, scanned);
    }

    /** The newest run at or below {@code before}, or the newest run overall. */
    @CheckForNull
    private static Run<?, ?> startingRun(@NonNull Job<?, ?> job, @CheckForNull Integer before) {
        try {
            if (before == null) {
                return job.getLastBuild();
            }
            if (before <= 1) {
                return null;
            }
            // getNearestOldBuild returns the newest build at or below the given number, so ask for
            // one below the exclusive bound.
            return job.getNearestOldBuild(before - 1);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @CheckForNull
    private static Run<?, ?> previousOf(@NonNull Run<?, ?> run) {
        try {
            return run.getPreviousBuild();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Whether the current user may see this run.
     *
     * <p>Runs normally inherit the job's ACL, but a plugin can restrict individual runs, so this is
     * checked per run rather than assumed from the job.
     */
    private static boolean isReadable(@NonNull Run<?, ?> run) {
        try {
            return run.hasPermission(Item.READ);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
