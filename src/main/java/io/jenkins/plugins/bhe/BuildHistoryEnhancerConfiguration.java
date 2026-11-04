package io.jenkins.plugins.bhe;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest2;

/** Global settings for the plugin, under Manage Jenkins &gt; System. */
@Extension
@Symbol("buildHistoryEnhancer")
public class BuildHistoryEnhancerConfiguration extends GlobalConfiguration {

    static final int DEFAULT_PAGE_SIZE = 30;
    static final int DEFAULT_MAX_SCAN = 2000;
    private static final int MIN_PAGE_SIZE = 5;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MIN_MAX_SCAN = 50;
    private static final int MAX_MAX_SCAN = 50_000;

    private boolean enabled = true;
    private int pageSize = DEFAULT_PAGE_SIZE;
    private int maxScan = DEFAULT_MAX_SCAN;

    public BuildHistoryEnhancerConfiguration() {
        load();
    }

    @NonNull
    @Override
    public String getDisplayName() {
        return Messages.config_displayName();
    }

    @NonNull
    public static BuildHistoryEnhancerConfiguration get() {
        BuildHistoryEnhancerConfiguration config =
                GlobalConfiguration.all().get(BuildHistoryEnhancerConfiguration.class);
        if (config == null) {
            // Can happen in tests that run without the extension list; defaults are safe.
            return new BuildHistoryEnhancerConfiguration();
        }
        return config;
    }

    /**
     * Master switch. When off, no script is injected and the build history sidebar is left exactly
     * as Jenkins renders it.
     */
    public boolean isEnabled() {
        return enabled;
    }

    @DataBoundSetter
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        save();
    }

    /** How many matching builds one page of filtered history holds. */
    public int getPageSize() {
        return clamp(pageSize, MIN_PAGE_SIZE, MAX_PAGE_SIZE, DEFAULT_PAGE_SIZE);
    }

    @DataBoundSetter
    public void setPageSize(int pageSize) {
        this.pageSize = clamp(pageSize, MIN_PAGE_SIZE, MAX_PAGE_SIZE, DEFAULT_PAGE_SIZE);
        save();
    }

    /**
     * How many builds a single filtered request may examine. This bounds the cost of a search on a
     * job with a very long history; when it is reached the page is marked truncated and the user is
     * offered a "search older builds" control rather than the server scanning the whole job.
     */
    public int getMaxScan() {
        return clamp(maxScan, MIN_MAX_SCAN, MAX_MAX_SCAN, DEFAULT_MAX_SCAN);
    }

    @DataBoundSetter
    public void setMaxScan(int maxScan) {
        this.maxScan = clamp(maxScan, MIN_MAX_SCAN, MAX_MAX_SCAN, DEFAULT_MAX_SCAN);
        save();
    }

    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) {
        // Reset to defaults first so that cleared fields fall back rather than keeping old values.
        this.enabled = true;
        this.pageSize = DEFAULT_PAGE_SIZE;
        this.maxScan = DEFAULT_MAX_SCAN;
        req.bindJSON(this, json);
        save();
        return true;
    }

    private static int clamp(int value, int min, int max, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }
}
