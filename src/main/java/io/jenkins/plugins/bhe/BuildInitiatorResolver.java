package io.jenkins.plugins.bhe;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Cause;
import hudson.model.Run;
import hudson.model.User;
import hudson.triggers.SCMTrigger;
import hudson.triggers.TimerTrigger;
import java.util.List;

/**
 * Maps a build's causes onto a single {@link BuildInitiator}.
 *
 * <p>Attribution is cause-based, which has known limits: a build only counts as a user's when the
 * build itself carries a {@link Cause.UserIdCause} for that user. Upstream builds are reported as
 * {@link BuildInitiator.Type#UPSTREAM} and deliberately not traced back to the person who started
 * the chain.
 */
public final class BuildInitiatorResolver {

    private BuildInitiatorResolver() {}

    /**
     * Resolves the initiator of a build.
     *
     * <p>When a build has several causes the first direct user cause wins, so a build that was
     * started by a user and also carries a timer or upstream cause is still attributed to the user.
     * Reading causes can touch the build record on disk, so failures are contained and reported as
     * {@link BuildInitiator.Type#UNKNOWN} rather than propagated.
     */
    @NonNull
    public static BuildInitiator resolve(@NonNull Run<?, ?> run) {
        List<Cause> causes;
        try {
            causes = run.getCauses();
        } catch (RuntimeException e) {
            return BuildInitiator.nonUser(BuildInitiator.Type.UNKNOWN, Messages.initiator_unknown());
        }
        if (causes == null || causes.isEmpty()) {
            return BuildInitiator.nonUser(BuildInitiator.Type.UNKNOWN, Messages.initiator_unknown());
        }

        // A direct user cause always wins, whatever else is on the build.
        for (Cause cause : causes) {
            if (cause instanceof Cause.UserIdCause userIdCause) {
                String userId = userIdCause.getUserId();
                if (userId != null && !userId.isBlank()) {
                    return BuildInitiator.user(userId, displayNameFor(userId));
                }
                // A UserIdCause with no ID means the build was started by an anonymous session.
                return BuildInitiator.nonUser(BuildInitiator.Type.SYSTEM, Messages.initiator_anonymous());
            }
        }

        // Legacy cause, superseded by UserIdCause but still present in old build records. It only
        // carries a display name, so it can never be matched against a user ID.
        for (Cause cause : causes) {
            if (cause instanceof Cause.UserCause userCause) {
                String name = userCause.getUserName();
                return BuildInitiator.nonUser(
                        BuildInitiator.Type.SYSTEM,
                        name == null || name.isBlank() ? Messages.initiator_anonymous() : name);
            }
        }

        for (Cause cause : causes) {
            if (cause instanceof TimerTrigger.TimerTriggerCause) {
                return BuildInitiator.nonUser(BuildInitiator.Type.TIMER, Messages.initiator_timer());
            }
            if (cause instanceof SCMTrigger.SCMTriggerCause) {
                return BuildInitiator.nonUser(BuildInitiator.Type.SCM, Messages.initiator_scm());
            }
            if (cause instanceof Cause.UpstreamCause upstream) {
                String project = upstream.getUpstreamProject();
                int number = upstream.getUpstreamBuild();
                String label = project == null || project.isBlank()
                        ? Messages.initiator_upstream()
                        : Messages.initiator_upstream_detail(project, number);
                return BuildInitiator.nonUser(BuildInitiator.Type.UPSTREAM, label);
            }
        }

        // Anything a plugin contributed that we do not recognise. The cause's own short description
        // is the most useful label available, but it is free text from another plugin, so it is
        // length-capped here and HTML-escaped by the renderer.
        Cause first = causes.get(0);
        String description;
        try {
            description = first.getShortDescription();
        } catch (RuntimeException e) {
            description = null;
        }
        if (description == null || description.isBlank()) {
            return BuildInitiator.nonUser(BuildInitiator.Type.SYSTEM, Messages.initiator_system());
        }
        return BuildInitiator.nonUser(BuildInitiator.Type.SYSTEM, truncate(description));
    }

    /**
     * Full name of a user when the account exists, otherwise the raw ID.
     *
     * <p>Deliberately does not create a user record: looking at build history must not populate the
     * users directory.
     */
    @NonNull
    private static String displayNameFor(@NonNull String userId) {
        try {
            User user = User.getById(userId, false);
            if (user != null) {
                String full = user.getFullName();
                if (full != null && !full.isBlank()) {
                    return full;
                }
            }
        } catch (RuntimeException e) {
            // Fall through to the raw ID: a broken or deleted user record must not hide the build.
        }
        return userId;
    }

    @NonNull
    private static String truncate(@NonNull String text) {
        String collapsed = text.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= 80 ? collapsed : collapsed.substring(0, 79) + "…";
    }
}
