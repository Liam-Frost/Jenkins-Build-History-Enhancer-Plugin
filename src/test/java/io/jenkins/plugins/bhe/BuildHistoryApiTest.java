package io.jenkins.plugins.bhe;

import static io.jenkins.plugins.bhe.BuildInitiatorResolverTest.buildWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Cause;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** End-to-end behaviour of the HTTP endpoints, including authorization. */
@WithJenkins
class BuildHistoryApiTest {

    @Test
    void historyEndpointRendersNativeRowMarkup(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        User.getById("alice", true).setFullName("Alice Example");
        FreeStyleBuild build = buildWith(r, job, new Cause.UserIdCause("alice"));
        build.setDisplayName("2.0.3.1-QA-signed");

        String html = fetchHistory(r, job, "search=QA-signed&mine=false");

        // The row must come from core's own entry view, not from markup of our own.
        assertTrue(html.contains("app-builds-container__item"), html);
        assertTrue(html.contains("2.0.3.1-QA-signed"), html);
        assertTrue(html.contains("page-entry-id="), "native entry id missing");
        assertTrue(html.contains("data-bhe-has-more="), "paging state missing");

        // The status icon must be the real symbol SVG. Without l:ajax page globals, l:icon cannot
        // resolve it and emits an empty <img>, which is invisible in the sidebar.
        assertTrue(html.contains("<svg"), "status icon symbol missing: " + html);
        assertFalse(html.contains("<img>"), "status icon rendered as an empty img: " + html);
        // Row links must carry the job path, as in core's own history.
        assertTrue(html.contains("href=\"/jenkins/job/p/" + build.getNumber() + "/\""), html);
        assertTrue(html.contains("/job/p/" + build.getNumber() + "/console"), "icon link to console missing");
    }

    @Test
    void historyEndpointFiltersByUser(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        User.getById("alice", true);
        User.getById("bob", true);
        buildWith(r, job, new Cause.UserIdCause("alice")).setDisplayName("by-alice");
        buildWith(r, job, new Cause.UserIdCause("bob")).setDisplayName("by-bob");

        String html = fetchHistory(r, job, "search=user:alice&mine=false");

        assertTrue(html.contains("by-alice"), html);
        assertFalse(html.contains("by-bob"), html);
    }

    @Test
    void historyEndpointReportsEmptyResult(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        r.buildAndAssertSuccess(job);

        String html = fetchHistory(r, job, "search=nothing-matches-this&mine=false");

        assertTrue(html.contains("data-bhe-empty=\"true\""), html);
    }

    @Test
    void initiatorsEndpointReturnsOnlyDisplayFields(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        job.addProperty(new hudson.model.ParametersDefinitionProperty(
                new hudson.model.StringParameterDefinition("TOKEN", "default")));
        User.getById("liam", true).setFullName("Liam Example");
        FreeStyleBuild build = r.assertBuildStatusSuccess(job.scheduleBuild2(
                0,
                new hudson.model.CauseAction(new Cause.UserIdCause("liam")),
                new hudson.model.ParametersAction(new hudson.model.StringParameterValue("TOKEN", "secret-value"))));
        r.waitUntilNoActivity();

        JSONObject payload = fetchInitiators(r, job, String.valueOf(build.getNumber()));
        JSONArray builds = payload.getJSONArray("builds");

        assertEquals(1, builds.size());
        JSONObject entry = builds.getJSONObject(0);
        assertEquals(build.getNumber(), entry.getInt("number"));
        assertEquals("USER", entry.getString("type"));
        assertEquals("liam", entry.getString("userId"));
        assertEquals("Liam Example", entry.getString("displayName"));
        // No parameter data of any kind in the response.
        assertFalse(payload.toString().contains("secret-value"), payload.toString());
        assertFalse(payload.toString().contains("TOKEN"), payload.toString());
    }

    @Test
    void initiatorsEndpointCapsAndIgnoresBadInput(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        r.buildAndAssertSuccess(job);

        StringBuilder many = new StringBuilder("1,not-a-number,-5,0,");
        for (int i = 0; i < BuildHistoryApi.MAX_INITIATOR_IDS + 50; i++) {
            many.append(1000 + i).append(',');
        }

        JSONObject payload = fetchInitiators(r, job, many.toString());

        // Only the one real build comes back; garbage is dropped, nonexistent builds are omitted.
        assertEquals(1, payload.getJSONArray("builds").size());
    }

    @Test
    void initiatorsEndpointOmitsNonexistentBuilds(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");

        JSONObject payload = fetchInitiators(r, job, "999");

        assertTrue(payload.getJSONArray("builds").isEmpty(), "must not confirm or deny unknown builds");
    }

    @Test
    void endpointsRequireJobRead(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("secret");
        r.buildAndAssertSuccess(job);

        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        // Both users may reach Jenkins itself; only "allowed" may read this job.
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ)
                .everywhere()
                .to("nosy", "allowed")
                .grant(Item.READ)
                .onItems(job)
                .to("allowed"));

        JenkinsRule.WebClient nosy = r.createWebClient().login("nosy");
        nosy.setThrowExceptionOnFailingStatusCode(false);
        Page denied = nosy.goTo("job/secret/bhe/history?mine=false", null);
        assertEquals(
                HttpURLConnection.HTTP_NOT_FOUND,
                denied.getWebResponse().getStatusCode(),
                "Jenkins returns 404 for jobs the user cannot read (hides job existence)");

        Page deniedJson = nosy.goTo("job/secret/bhe/initiators?builds=1", null);
        assertEquals(
                HttpURLConnection.HTTP_NOT_FOUND,
                deniedJson.getWebResponse().getStatusCode(),
                "Jenkins returns 404 for jobs the user cannot read (hides job existence)");

        // A user who may read the job gets a normal response.
        JenkinsRule.WebClient allowed = r.createWebClient().login("allowed");
        Page ok = allowed.goTo("job/secret/bhe/history?mine=false", null);
        assertEquals(HttpURLConnection.HTTP_OK, ok.getWebResponse().getStatusCode());
    }

    @Test
    void onlyMineUsesTheAuthenticatedIdentityNotARequestParameter(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        buildWith(r, job, new Cause.UserIdCause("alice")).setDisplayName("by-alice");
        buildWith(r, job, new Cause.UserIdCause("bob")).setDisplayName("by-bob");

        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new FullControlOnceLoggedInAuthorizationStrategy());

        JenkinsRule.WebClient bobClient = r.createWebClient().login("bob");
        // Bob asks for "only my builds" while also claiming to be alice in the query string.
        String html = bobClient
                .goTo("job/p/bhe/history?mine=true&userId=alice&user=alice", null)
                .getWebResponse()
                .getContentAsString();

        assertTrue(html.contains("by-bob"), html);
        assertFalse(html.contains("by-alice"), "a client-supplied user ID must be ignored");
    }

    @Test
    void anonymousOnlyMineReturnsNothingRatherThanEverything(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        buildWith(r, job, new Cause.UserIdCause("alice")).setDisplayName("by-alice");
        buildWith(r, job, new Cause.UserIdCause((String) null)).setDisplayName("by-anon");

        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ)
                .everywhere()
                .toEveryone());

        String html = r.createWebClient()
                .goTo("job/p/bhe/history?mine=true", null)
                .getWebResponse()
                .getContentAsString();

        assertTrue(html.contains("data-bhe-empty=\"true\""), html);
        assertFalse(html.contains("by-anon"), "anonymous builds are not the anonymous viewer's own");
        assertFalse(html.contains("by-alice"), html);
    }

    @Test
    void preferenceEndpointRejectsGet(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");

        JenkinsRule.WebClient client = r.createWebClient();
        client.setThrowExceptionOnFailingStatusCode(false);
        Page page = client.goTo("job/p/bhe/initiatorMode?mode=ALWAYS", null);

        assertEquals(
                HttpURLConnection.HTTP_BAD_METHOD,
                page.getWebResponse().getStatusCode(),
                "a state-changing endpoint must not accept GET");
    }

    @Test
    void preferenceEndpointStoresModeForAuthenticatedUser(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new FullControlOnceLoggedInAuthorizationStrategy());

        JenkinsRule.WebClient client = r.createWebClient().login("dana");
        WebRequest request = new WebRequest(new URL(r.getURL(), "job/p/bhe/initiatorMode"), HttpMethod.POST);
        request.setRequestParameters(List.of(new NameValuePair("mode", "ALWAYS")));
        Page response = client.getPage(client.addCrumb(request));

        assertEquals(HttpURLConnection.HTTP_OK, response.getWebResponse().getStatusCode());
        assertTrue(response.getWebResponse().getContentAsString().contains("ALWAYS"));

        User dana = User.getById("dana", false);
        assertEquals(
                InitiatorDisplayMode.ALWAYS,
                InitiatorDisplayMode.parse(
                        dana.getProperty(InitiatorDisplayPreference.class).getMode()));
    }

    private static String fetchHistory(JenkinsRule r, FreeStyleProject job, String query) throws Exception {
        return r.createWebClient()
                .goTo("job/" + job.getName() + "/bhe/history?" + query, null)
                .getWebResponse()
                .getContentAsString();
    }

    private static JSONObject fetchInitiators(JenkinsRule r, FreeStyleProject job, String builds) throws Exception {
        String body = r.createWebClient()
                .goTo("job/" + job.getName() + "/bhe/initiators?builds=" + builds, "application/json")
                .getWebResponse()
                .getContentAsString();
        return JSONObject.fromObject(body);
    }
}
