package io.jenkins.plugins.bhe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.User;
import hudson.triggers.SCMTrigger;
import hudson.triggers.TimerTrigger;
import java.util.List;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Build attribution from causes. */
@WithJenkins
class BuildInitiatorResolverTest {

    @Test
    void attributesBuildToTriggeringUser(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        User alice = User.getById("alice", true);
        alice.setFullName("Alice Example");

        FreeStyleBuild build = buildWith(r, job, new Cause.UserIdCause("alice"));

        BuildInitiator initiator = BuildInitiatorResolver.resolve(build);
        assertEquals(BuildInitiator.Type.USER, initiator.getType());
        assertEquals("alice", initiator.getUserId());
        assertEquals("Alice Example", initiator.getDisplayName());
    }

    @Test
    void attributesBuildToAnotherUser(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        User.getById("bob", true).setFullName("Bob Example");

        FreeStyleBuild build = buildWith(r, job, new Cause.UserIdCause("bob"));

        BuildInitiator initiator = BuildInitiatorResolver.resolve(build);
        assertEquals("bob", initiator.getUserId());
        assertEquals("Bob Example", initiator.getDisplayName());
    }

    @Test
    void classifiesTimerTrigger(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");

        FreeStyleBuild build = buildWith(r, job, new TimerTrigger.TimerTriggerCause());

        BuildInitiator initiator = BuildInitiatorResolver.resolve(build);
        assertEquals(BuildInitiator.Type.TIMER, initiator.getType());
        assertNull(initiator.getUserId(), "a trigger category must not look like a user ID");
    }

    @Test
    void classifiesScmTrigger(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");

        FreeStyleBuild build = buildWith(r, job, new SCMTrigger.SCMTriggerCause("polling"));

        assertEquals(
                BuildInitiator.Type.SCM, BuildInitiatorResolver.resolve(build).getType());
    }

    @Test
    void classifiesUpstreamTriggerWithoutTracingToUser(JenkinsRule r) throws Exception {
        FreeStyleProject upstream = r.createFreeStyleProject("upstream");
        FreeStyleProject downstream = r.createFreeStyleProject("downstream");
        // The upstream build itself was started by a user.
        FreeStyleBuild upstreamBuild = buildWith(r, upstream, new Cause.UserIdCause("alice"));

        FreeStyleBuild build = buildWith(r, downstream, new Cause.UpstreamCause(upstreamBuild));

        BuildInitiator initiator = BuildInitiatorResolver.resolve(build);
        assertEquals(BuildInitiator.Type.UPSTREAM, initiator.getType());
        // V1 deliberately does not walk the chain back to alice.
        assertNull(initiator.getUserId());
        assertTrue(initiator.getDisplayName().contains("upstream"), initiator.getDisplayName());
    }

    @Test
    void directUserCauseWinsOverOtherCauses(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        User.getById("carol", true).setFullName("Carol Example");

        FreeStyleBuild build = buildWith(
                r,
                job,
                new TimerTrigger.TimerTriggerCause(),
                new Cause.UserIdCause("carol"),
                new Cause.RemoteCause("10.0.0.1", "token"));

        BuildInitiator initiator = BuildInitiatorResolver.resolve(build);
        assertEquals(BuildInitiator.Type.USER, initiator.getType());
        assertEquals("carol", initiator.getUserId());
    }

    @Test
    void reportsRawIdWhenUserRecordIsMissing(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        // No User record is created for "ghost": the account was deleted, or never logged in.

        FreeStyleBuild build = buildWith(r, job, new Cause.UserIdCause("ghost"));

        BuildInitiator initiator = BuildInitiatorResolver.resolve(build);
        assertEquals(BuildInitiator.Type.USER, initiator.getType());
        assertEquals("ghost", initiator.getUserId());
        assertEquals("ghost", initiator.getDisplayName(), "falls back to the ID, still filterable");
    }

    @Test
    void classifiesAnonymousUserCauseAsSystemNotAsAUser(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");

        // A UserIdCause with no ID is what an anonymous session records.
        FreeStyleBuild build = buildWith(r, job, new Cause.UserIdCause((String) null));

        BuildInitiator initiator = BuildInitiatorResolver.resolve(build);
        assertEquals(
                BuildInitiator.Type.SYSTEM,
                initiator.getType(),
                "an anonymous trigger must not be attributed to any user");
        assertNull(initiator.getUserId());
    }

    @Test
    void classifiesBuildWithNoCauses(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");

        FreeStyleBuild build = job.scheduleBuild2(0, new CauseAction(List.of())).get();
        r.waitUntilNoActivity();

        BuildInitiator initiator = BuildInitiatorResolver.resolve(build);
        assertEquals(BuildInitiator.Type.UNKNOWN, initiator.getType());
    }

    @Test
    void labelsUnrecognisedPluginCause(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");

        FreeStyleBuild build = buildWith(r, job, new Cause.RemoteCause("10.0.0.1", "nightly"));

        BuildInitiator initiator = BuildInitiatorResolver.resolve(build);
        assertEquals(BuildInitiator.Type.SYSTEM, initiator.getType());
        assertNotNull(initiator.getDisplayName());
        assertNull(initiator.getUserId());
    }

    @Test
    void resolvesWithoutAnAuthenticatedSession(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        FreeStyleBuild build = buildWith(r, job, new Cause.UserIdCause("alice"));

        // Resolution must not depend on who is asking.
        try (var ignored = Jenkins.get().getACL().as2(Jenkins.ANONYMOUS2)) {
            BuildInitiator initiator = BuildInitiatorResolver.resolve(build);
            assertEquals("alice", initiator.getUserId());
        }
    }

    static FreeStyleBuild buildWith(JenkinsRule r, FreeStyleProject job, Cause... causes) throws Exception {
        FreeStyleBuild build = job.scheduleBuild2(0, new CauseAction(causes)).get();
        r.waitUntilNoActivity();
        return build;
    }
}
