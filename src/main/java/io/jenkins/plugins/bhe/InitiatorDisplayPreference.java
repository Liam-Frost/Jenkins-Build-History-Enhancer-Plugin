package io.jenkins.plugins.bhe;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.User;
import hudson.model.UserProperty;
import hudson.model.UserPropertyDescriptor;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * Per-user preference for when the initiator metadata row is shown.
 *
 * <p>Stored against the Jenkins user so that the choice follows the person across browsers. An
 * anonymous session has nowhere to store it, so it always sees {@code AUTO}; the control is hidden
 * in that case rather than appearing not to work.
 */
public class InitiatorDisplayPreference extends UserProperty {

    private static final Logger LOGGER = Logger.getLogger(InitiatorDisplayPreference.class.getName());

    private String mode;

    @DataBoundConstructor
    public InitiatorDisplayPreference(String mode) {
        this.mode = InitiatorDisplayMode.parse(mode).name();
    }

    @NonNull
    public String getMode() {
        return InitiatorDisplayMode.parse(mode).name();
    }

    public void setMode(String mode) {
        this.mode = InitiatorDisplayMode.parse(mode).name();
    }

    /** The current user's preference, or {@code AUTO} when anonymous or unset. */
    @NonNull
    public static InitiatorDisplayMode forCurrentUser() {
        User user = User.current();
        if (user == null) {
            return InitiatorDisplayMode.AUTO;
        }
        InitiatorDisplayPreference property = user.getProperty(InitiatorDisplayPreference.class);
        if (property == null) {
            return InitiatorDisplayMode.AUTO;
        }
        return InitiatorDisplayMode.parse(property.mode);
    }

    /**
     * Saves a new preference for the current user. Does nothing for an anonymous session.
     *
     * @return the mode now in effect
     */
    @NonNull
    public static InitiatorDisplayMode saveForCurrentUser(@NonNull InitiatorDisplayMode newMode) {
        User user = User.current();
        if (user == null) {
            return InitiatorDisplayMode.AUTO;
        }
        try {
            user.addProperty(new InitiatorDisplayPreference(newMode.name()));
            return newMode;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not save initiator display preference", e);
            return forCurrentUser();
        }
    }

    @Extension
    public static class DescriptorImpl extends UserPropertyDescriptor {

        @Override
        @NonNull
        public String getDisplayName() {
            return Messages.preference_displayName();
        }

        @Override
        public UserProperty newInstance(User user) {
            return new InitiatorDisplayPreference(InitiatorDisplayMode.AUTO.name());
        }

        @Override
        public UserProperty newInstance(StaplerRequest2 req, @NonNull JSONObject formData) {
            return new InitiatorDisplayPreference(formData.optString("mode", InitiatorDisplayMode.AUTO.name()));
        }

        /** Not shown on the user configuration page; it is set from the sidebar control. */
        @Override
        public boolean isEnabled() {
            return false;
        }
    }
}
