package io.jenkins.plugins.bhe;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Action;
import hudson.model.Item;
import hudson.model.ParametersAction;
import hudson.model.Run;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Works out where a build's editable "reuse parameters" form lives.
 *
 * <p>The target is the Rebuilder plugin's parameterized form. Rebuilder is optional, so everything
 * here is reflective and failure-tolerant: with the plugin absent the result is simply "no link",
 * and the sidebar shows no reuse control.
 *
 * <h2>Why the route is resolved at runtime</h2>
 *
 * Rebuilder exposes two routes on each build. {@code rebuild/parameterized} renders the editable
 * form, while {@code rebuild/} schedules a build straight away when auto-rebuild is enabled on the
 * job or when the build has no parameters. Its own {@code AbstractRebuildAction.getTaskUrl()}
 * returns whichever applies, so that is what is consulted here. A URL is only produced when it
 * resolves to the editable form; the immediate route is deliberately rejected, since clicking a
 * reuse control must never schedule a build.
 *
 * <p>Permission is enforced by Rebuilder itself ({@code Item.BUILD} is checked inside the form and
 * again on submit). This class checks it too so that an unusable control is not rendered, but the
 * UI check is a convenience and not the security boundary.
 */
public final class RebuildLinkService {

    private static final Logger LOGGER = Logger.getLogger(RebuildLinkService.class.getName());

    private static final String ACTION_CLASS = "com.sonyericsson.rebuild.RebuildAction";
    private static final String ABSTRACT_ACTION_CLASS = "com.sonyericsson.rebuild.AbstractRebuildAction";

    /** The editable-form route. Anything else from getTaskUrl() is treated as "not usable". */
    private static final String EDITABLE_SUFFIX = "rebuild/parameterized";

    private RebuildLinkService() {}

    /** Whether the Rebuilder plugin is installed and loaded. */
    public static boolean isRebuilderAvailable() {
        return loadActionClass() != null;
    }

    /**
     * Relative URL of the editable parameter form for a build, or {@code null} when there is none.
     *
     * <p>The URL is relative to the Jenkins root URL, matching what Rebuilder's own sidebar link
     * does, and is built from {@link Run#getUrl()} so that jobs inside folders work.
     *
     * <p>Returns {@code null} when Rebuilder is missing, when the build has no parameters, when the
     * job is unbuildable or rebuild-disabled, when the user lacks {@link Item#BUILD}, or when
     * Rebuilder reports the immediate-rebuild route rather than the form.
     */
    @CheckForNull
    public static String getEditableRebuildUrl(@NonNull Run<?, ?> run) {
        Class<?> actionClass = loadActionClass();
        if (actionClass == null) {
            return null;
        }

        // Only parameterized builds have anything to reuse.
        if (run.getAction(ParametersAction.class) == null) {
            return null;
        }

        try {
            if (!run.getParent().hasPermission(Item.BUILD)) {
                return null;
            }
        } catch (RuntimeException e) {
            return null;
        }

        try {
            // Ask Rebuilder's own action, attached to this run, rather than guessing a route.
            Action action = run.getAction(asActionClass(actionClass));
            if (action == null) {
                return null;
            }
            Class<?> abstractClass = Class.forName(ABSTRACT_ACTION_CLASS, false, actionClass.getClassLoader());
            boolean available =
                    (Boolean) abstractClass.getMethod("isRebuildAvailable").invoke(action);
            if (!available) {
                return null;
            }
            Object taskUrl = abstractClass.getMethod("getTaskUrl").invoke(action);
            if (!(taskUrl instanceof String url) || url.isBlank()) {
                return null;
            }
            // Reject the immediate-rebuild route: a reuse click must open a form, never schedule.
            if (!stripSlashes(url).equals(EDITABLE_SUFFIX)) {
                LOGGER.log(
                        Level.FINE,
                        () -> "Rebuilder reports non-editable route '" + url + "' for " + run.getFullDisplayName()
                                + "; no reuse link offered");
                return null;
            }
            return run.getUrl() + url;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // A Rebuilder version whose API differs from what we expect. Degrade to no link.
            LOGGER.log(Level.FINE, "Could not resolve Rebuilder form URL", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Action> asActionClass(@NonNull Class<?> clazz) {
        return (Class<? extends Action>) clazz;
    }

    @CheckForNull
    private static Class<?> loadActionClass() {
        try {
            return Class.forName(ACTION_CLASS, false, PluginClassLoaders.uberClassLoader());
        } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
            return null;
        }
    }

    @NonNull
    private static String stripSlashes(@NonNull String url) {
        String result = url;
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
