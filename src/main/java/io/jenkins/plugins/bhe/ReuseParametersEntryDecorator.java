package io.jenkins.plugins.bhe;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import hudson.widgets.HistoryWidget;
import jenkins.model.HistoricalBuild;
import jenkins.widgets.HistoryPageEntryDecorator;

/**
 * Adds a reuse-parameters column to the builds list of Jenkins' experimental new job page.
 *
 * <p>The classic job page gets this shortcut from {@code sidebar.js}. The new page renders its rows
 * with different markup and offers {@link HistoryPageEntryDecorator} for plugins to add a column, so
 * the shortcut is contributed through that instead of through the script.
 *
 * <p>{@code HistoryPageEntryDecorator} is new in Jenkins 2.589, while this plugin supports older
 * versions. The extension is therefore {@code optional}: on a Jenkins without the class, loading it
 * fails and Jenkins skips it quietly. Nothing else in the plugin refers to this class. See
 * CONTRIBUTING.md for how it is compiled against the newer API.
 *
 * <p>The new page already shows who started each build and has its own search, so those features
 * are not repeated here.
 */
@Extension(optional = true)
public class ReuseParametersEntryDecorator extends HistoryPageEntryDecorator {

    @Override
    public boolean isApplicable(@NonNull HistoryWidget<?, ?> widget, @NonNull HistoricalBuild build) {
        return BuildHistoryEnhancerConfiguration.get().isEnabled() && reuseUrl(build) != null;
    }

    /**
     * URL of the editable rebuild form for a build, relative to the Jenkins root, or {@code null}.
     * Same rules as the classic page: Rebuilder installed, the build had parameters, the viewer may
     * build the job, and Rebuilder would show its form rather than rebuild immediately.
     */
    @CheckForNull
    public String reuseUrl(@NonNull HistoricalBuild build) {
        return build instanceof Run<?, ?> run ? RebuildLinkService.getEditableRebuildUrl(run) : null;
    }
}
