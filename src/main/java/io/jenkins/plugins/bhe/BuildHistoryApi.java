package io.jenkins.plugins.bhe;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.User;
import hudson.security.ACL;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import jenkins.model.Jenkins;
import jenkins.model.TransientActionFactory;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.WebMethod;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.kohsuke.stapler.verb.GET;
import org.kohsuke.stapler.verb.POST;

/**
 * Server-side API behind the sidebar enhancements, mounted at {@code <job>/bhe/}.
 *
 * <p>Endpoints:
 *
 * <ul>
 *   <li>{@code GET history} — one page of filtered history, rendered with Jenkins' own row markup
 *   <li>{@code GET initiators} — who started each of a bounded set of builds, as JSON
 *   <li>{@code POST initiatorMode} — saves the viewer's display preference for the initiator line
 * </ul>
 *
 * <p>All three check {@link Item#READ} on the job, and the reads skip individual runs the caller
 * cannot read. The reads change nothing. {@code initiatorMode} is the only state change and requires
 * POST, so Jenkins' CSRF protection covers it.
 *
 * <p>The identity used for "only my builds" comes from the authenticated security context. A user ID
 * in the query string is never trusted for ownership.
 */
public class BuildHistoryApi implements Action {

    /** Hard cap on build numbers per initiators request, to bound the work per call. */
    static final int MAX_INITIATOR_IDS = 200;

    private final Job<?, ?> job;

    BuildHistoryApi(@NonNull Job<?, ?> job) {
        this.job = job;
    }

    @Override
    @CheckForNull
    public String getIconFileName() {
        return null; // Not a visible sidebar task; this is a URL namespace only.
    }

    @Override
    @NonNull
    public String getDisplayName() {
        return Messages.config_displayName();
    }

    @Override
    @NonNull
    public String getUrlName() {
        return "bhe";
    }

    /**
     * Renders one page of filtered build history as an HTML fragment.
     *
     * <p>Query parameters: {@code search} (free text or {@code user:<id>}), {@code mine}
     * ({@code true}/{@code false}), and {@code before} (exclusive upper bound on build number, for
     * paging into older history).
     *
     * <p>The response body is the same markup core produces for its own ajax history endpoint,
     * wrapped in a div carrying this plugin's cursor attributes.
     */
    @GET
    @WebMethod(name = "history")
    public void doHistory(@NonNull StaplerRequest2 req, @NonNull StaplerResponse2 rsp)
            throws IOException, ServletException {

        job.checkPermission(Item.READ);

        BuildHistoryEnhancerConfiguration config = BuildHistoryEnhancerConfiguration.get();
        SearchQuery query = SearchQuery.parse(req.getParameter("search"));
        boolean mine = Boolean.parseBoolean(req.getParameter("mine"));
        Integer before = parsePositiveInt(req.getParameter("before"));

        BuildSearchFilter filter = new BuildSearchFilter(query, currentUserId(), mine);
        BuildHistoryService.Page page =
                BuildHistoryService.search(job, filter, before, config.getPageSize(), config.getMaxScan());

        HistoryPage model = new HistoryPage(job, page, config.getPageSize(), before == null);
        // Content type and cache headers are set by l:ajax in the view, as for core's own endpoint.
        req.setAttribute("bheHistoryPage", model);
        req.getView(this, "history.jelly").forward(req, rsp);
    }

    /**
     * Returns initiator metadata for specific build numbers.
     *
     * <p>Query parameter {@code builds} is a comma-separated list of build numbers, capped at
     * {@link #MAX_INITIATOR_IDS} entries. Unknown, unreadable or malformed entries are omitted
     * rather than reported, so that this endpoint cannot be used to probe for builds the caller
     * cannot see.
     *
     * <p>The response carries only what the metadata row renders. Build parameters, including
     * secret ones, are never included.
     */
    @GET
    @WebMethod(name = "initiators")
    public void doInitiators(@NonNull StaplerRequest2 req, @NonNull StaplerResponse2 rsp) throws IOException {

        job.checkPermission(Item.READ);

        JSONObject result = new JSONObject();
        JSONArray builds = new JSONArray();

        for (int number : parseBuildNumbers(req.getParameter("builds"))) {
            Run<?, ?> run;
            try {
                run = job.getBuildByNumber(number);
            } catch (RuntimeException e) {
                continue;
            }
            if (run == null || !run.hasPermission(Item.READ)) {
                continue;
            }
            BuildInitiator initiator = BuildInitiatorResolver.resolve(run);

            JSONObject entry = new JSONObject();
            entry.put("number", number);
            entry.put("type", initiator.getType().name());
            entry.put("userId", initiator.getUserId());
            entry.put("displayName", initiator.getDisplayName());

            String rebuildUrl = RebuildLinkService.getEditableRebuildUrl(run);
            if (rebuildUrl != null) {
                // Root-relative, never absolute: an absolute URL would be built from the request's
                // Host header, so a forged header could point the link at another site.
                entry.put("reuseUrl", req.getContextPath() + "/" + rebuildUrl);
            }
            entry.put("rebuildAvailable", rebuildUrl != null);
            builds.add(entry);
        }

        result.put("builds", builds);
        rsp.setContentType("application/json;charset=UTF-8");
        rsp.getWriter().write(result.toString());
    }

    /**
     * Saves the viewer's initiator display preference.
     *
     * <p>This is the only endpoint here that changes state, so it requires POST and is covered by
     * Jenkins' CSRF protection. The preference belongs to the authenticated user; an anonymous
     * session has nowhere to store one and gets {@code AUTO} back unchanged.
     */
    @POST
    @RequirePOST
    @WebMethod(name = "initiatorMode")
    public void doInitiatorMode(@NonNull StaplerRequest2 req, @NonNull StaplerResponse2 rsp) throws IOException {

        job.checkPermission(Item.READ);

        InitiatorDisplayMode requested = InitiatorDisplayMode.parse(req.getParameter("mode"));
        InitiatorDisplayMode effective = InitiatorDisplayPreference.saveForCurrentUser(requested);

        JSONObject result = new JSONObject();
        result.put("mode", effective.name());
        rsp.setContentType("application/json;charset=UTF-8");
        rsp.getWriter().write(result.toString());
    }

    /** The authenticated user's Jenkins ID, or {@code null} when the request is anonymous. */
    @CheckForNull
    private static String currentUserId() {
        if (ACL.isAnonymous2(Jenkins.getAuthentication2())) {
            return null;
        }
        User current = User.current();
        return current == null ? null : current.getId();
    }

    @CheckForNull
    private static Integer parsePositiveInt(@CheckForNull String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parses and bounds the {@code builds} parameter, ignoring anything unparseable. */
    @NonNull
    private static List<Integer> parseBuildNumbers(@CheckForNull String raw) {
        List<Integer> numbers = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return numbers;
        }
        Set<Integer> seen = new java.util.HashSet<>();
        for (String token : raw.split(",", MAX_INITIATOR_IDS + 1)) {
            if (numbers.size() >= MAX_INITIATOR_IDS) {
                break;
            }
            try {
                int value = Integer.parseInt(token.trim());
                if (value > 0 && seen.add(value)) {
                    numbers.add(value);
                }
            } catch (NumberFormatException e) {
                // Ignore: a malformed id is not worth failing the whole request over.
            }
        }
        return numbers;
    }

    /** Attaches the API to every job. */
    @Extension
    public static class FactoryImpl extends TransientActionFactory<Job> {

        @Override
        public Class<Job> type() {
            return Job.class;
        }

        @Override
        @NonNull
        public Collection<? extends Action> createFor(@NonNull Job target) {
            if (!BuildHistoryEnhancerConfiguration.get().isEnabled()) {
                return Set.of();
            }
            return Set.of(new BuildHistoryApi(target));
        }
    }
}
