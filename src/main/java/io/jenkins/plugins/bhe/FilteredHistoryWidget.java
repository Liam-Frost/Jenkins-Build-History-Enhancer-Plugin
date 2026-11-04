package io.jenkins.plugins.bhe;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Job;
import hudson.model.ModelObject;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.widgets.BuildHistoryWidget;
import hudson.widgets.HistoryWidget;
import java.util.List;
import jenkins.model.HistoricalBuild;
import jenkins.widgets.HistoryPageFilter;

/**
 * Renders a pre-filtered list of runs through Jenkins' own history widget.
 *
 * <p>This exists so that filtered rows are produced by core's {@code entry.jelly} rather than by
 * markup of our own. Status icons, badges, the Keep Forever lock, progress bars for running builds,
 * the cancel button, the row jumplist and date headings therefore all behave exactly as they do in
 * unfiltered history, and keep working when core changes them.
 *
 * <p>The widget is handed a list that has already been filtered and cut to one page by
 * {@link BuildHistoryService}. The inherited paging fields are left unset, so core's
 * {@link HistoryPageFilter} simply renders what it is given instead of filtering again.
 */
final class FilteredHistoryWidget {

    private FilteredHistoryWidget() {}

    /**
     * Builds a page filter for the given runs, using the widget type that matches the job.
     *
     * <p>Jobs that are {@link Queue.Task}s — which covers pipelines and freestyle projects — get a
     * {@link BuildHistoryWidget}, so queued builds appear above the history exactly as in the
     * native sidebar. Other job types fall back to a plain {@link HistoryWidget}; such jobs cannot
     * be queued, so nothing is lost.
     *
     * @param job the job being rendered
     * @param runs one page of runs, newest first
     * @param pageSize page size, used as the widget's entry cap
     * @param includeQueue whether queued items should be included (only true on the first page)
     */
    @NonNull
    static HistoryPageFilter<?> pageFilterFor(
            @NonNull Job<?, ?> job, @NonNull List<Run<?, ?>> runs, int pageSize, boolean includeQueue) {

        List<HistoricalBuild> historical = List.copyOf(runs);

        HistoryWidget<?, HistoricalBuild> widget = includeQueue && job instanceof Queue.Task task
                ? new QueueAwareWidget(task, historical, pageSize)
                : new PlainWidget(job, historical, pageSize);
        HistoryPageFilter<HistoricalBuild> filter = widget.getHistoryPageFilter();
        // entry.jelly reads it.widget for the row link base URL, the cancel permission and the
        // transient-build marker. BuildHistoryWidget.getHistoryPageFilter() does not set it; core's
        // own HistoryWidget.doAjax assigns it afterwards, so do the same here. Without it the row
        // links come out as "/6/" instead of "/job/<name>/6/".
        filter.widget = widget;
        return filter;
    }

    /** Includes queued items, like the native build history sidebar. */
    private static final class QueueAwareWidget extends BuildHistoryWidget<HistoricalBuild> {

        private final int pageSize;

        QueueAwareWidget(@NonNull Queue.Task task, @NonNull List<HistoricalBuild> runs, int pageSize) {
            super(task, runs, Job.HISTORY_ADAPTER);
            this.pageSize = pageSize;
        }

        @Override
        protected HistoryPageFilter<HistoricalBuild> newPageFilter() {
            // Core's default cap is a fixed 30; honour the configured page size instead.
            return new HistoryPageFilter<>(pageSize);
        }
    }

    /** Fallback for job types that are not queue tasks. */
    private static final class PlainWidget extends HistoryWidget<ModelObject, HistoricalBuild> {

        private final int pageSize;

        PlainWidget(@NonNull Job<?, ?> job, @NonNull List<HistoricalBuild> runs, int pageSize) {
            super(job, runs, Job.HISTORY_ADAPTER);
            this.pageSize = pageSize;
        }

        @Override
        protected HistoryPageFilter<HistoricalBuild> newPageFilter() {
            return new HistoryPageFilter<>(pageSize);
        }
    }
}
