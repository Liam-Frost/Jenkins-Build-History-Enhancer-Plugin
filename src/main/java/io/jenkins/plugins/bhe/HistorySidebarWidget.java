package io.jenkins.plugins.bhe;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Job;
import hudson.model.User;
import hudson.widgets.Widget;
import java.util.Collection;
import java.util.List;
import jenkins.widgets.WidgetFactory;

/**
 * Carries the sidebar enhancement's assets and controls onto job pages.
 *
 * <p>Jenkins renders every widget a {@link WidgetFactory} contributes inside the job side panel,
 * next to the native build history card, on both the classic and the newer job page. That makes it
 * a supported place to add markup to a job page without touching core's own views.
 *
 * <p>This widget deliberately renders nothing visible. Its view emits the script and stylesheet
 * plus a hidden template block; the script then moves the controls underneath the existing search
 * field. If the script does not run, or the build history card is not the shape this plugin knows,
 * the hidden block stays hidden and native history is untouched.
 */
public class HistorySidebarWidget extends Widget {

    private final Job<?, ?> job;

    HistorySidebarWidget(@NonNull Job<?, ?> job) {
        this.job = job;
    }

    /** The job whose history is being enhanced. */
    @NonNull
    public Job<?, ?> getJob() {
        return job;
    }

    /** Base URL of this plugin's API for the current job, relative to the Jenkins root. */
    @NonNull
    public String getApiUrl() {
        return job.getUrl() + "bhe/";
    }

    /** Whether anyone is logged in. "Only my builds" is useless for an anonymous session. */
    public boolean isAuthenticated() {
        return User.current() != null;
    }

    /** The viewer's saved initiator display preference, defaulting to {@code AUTO}. */
    @NonNull
    public String getInitiatorMode() {
        return InitiatorDisplayPreference.forCurrentUser().name();
    }

    /** Number of builds on one page of filtered history, from the global configuration. */
    public int getPageSize() {
        return BuildHistoryEnhancerConfiguration.get().getPageSize();
    }

    /** Attaches the widget to every job, unless the plugin is switched off globally. */
    @Extension
    public static class FactoryImpl extends WidgetFactory<Job, HistorySidebarWidget> {

        @Override
        public Class<Job> type() {
            return Job.class;
        }

        @Override
        public Class<HistorySidebarWidget> widgetType() {
            return HistorySidebarWidget.class;
        }

        @Override
        @NonNull
        public Collection<HistorySidebarWidget> createFor(@NonNull Job target) {
            if (!BuildHistoryEnhancerConfiguration.get().isEnabled()) {
                return List.of();
            }
            return List.of(new HistorySidebarWidget(target));
        }
    }
}
