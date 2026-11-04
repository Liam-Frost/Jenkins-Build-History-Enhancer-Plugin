package io.jenkins.plugins.bhe;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Job;
import jenkins.widgets.HistoryPageFilter;

/**
 * View model for {@code history.jelly}: one page of filtered history plus its cursor state.
 *
 * <p>Exposes the page filter so that the view can hand it straight to Jenkins' own
 * {@code entries.jelly}, which is what keeps filtered rows identical to native ones.
 */
public class HistoryPage {

    private final HistoryPageFilter<?> pageFilter;
    private final BuildHistoryService.Page page;
    private final boolean firstPage;

    HistoryPage(@NonNull Job<?, ?> job, @NonNull BuildHistoryService.Page page, int pageSize, boolean firstPage) {
        this.page = page;
        this.firstPage = firstPage;
        // Queued items belong only on the first page, matching native behaviour.
        this.pageFilter = FilteredHistoryWidget.pageFilterFor(job, page.getRuns(), pageSize, firstPage);
    }

    /** The core page filter, for {@code st:include} of core's entries view. */
    @NonNull
    public HistoryPageFilter<?> getPageFilter() {
        return pageFilter;
    }

    /**
     * True when this page has no matching runs and no queued items.
     *
     * <p>Not named {@code isEmpty}: {@code empty} is a JEXL operator, so {@code ${x.empty}} does
     * not parse in a Jelly expression.
     */
    public boolean isNoResults() {
        return pageFilter.runs.isEmpty() && pageFilter.queueItems.isEmpty();
    }

    /** True when an older page exists. */
    public boolean isHasMore() {
        return page.isHasMore();
    }

    /** True when the scan budget ran out before the page filled. */
    public boolean isTruncated() {
        return page.isTruncated();
    }

    /** Exclusive upper bound for the next page, or {@code null} when history is exhausted. */
    @CheckForNull
    public Integer getNextCursor() {
        return page.getNextCursor();
    }

    /** How many builds this request examined. */
    public int getScanned() {
        return page.getScanned();
    }

    /** True when this is the newest page, i.e. no cursor was supplied. */
    public boolean isFirstPage() {
        return firstPage;
    }
}
