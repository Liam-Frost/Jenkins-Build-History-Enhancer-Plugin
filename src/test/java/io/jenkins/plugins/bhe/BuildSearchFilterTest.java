package io.jenkins.plugins.bhe;

import static io.jenkins.plugins.bhe.BuildInitiatorResolverTest.buildWith;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Cause;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.User;
import hudson.triggers.TimerTrigger;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Matching of individual runs against the search box and the "only my builds" checkbox. */
@WithJenkins
class BuildSearchFilterTest {

    @Test
    void onlyMineMatchesOwnBuildsOnly(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        User.getById("alice", true);
        User.getById("bob", true);
        FreeStyleBuild mine = buildWith(r, job, new Cause.UserIdCause("alice"));
        FreeStyleBuild theirs = buildWith(r, job, new Cause.UserIdCause("bob"));
        FreeStyleBuild timer = buildWith(r, job, new TimerTrigger.TimerTriggerCause());

        BuildSearchFilter filter = new BuildSearchFilter(SearchQuery.parse(null), "alice", true);

        assertTrue(filter.matches(mine));
        assertFalse(filter.matches(theirs));
        assertFalse(filter.matches(timer));
    }

    @Test
    void onlyMineFollowsACaseInsensitiveRealm(JenkinsRule r) throws Exception {
        // Jenkins' default, and what LDAP and Active Directory realms use.
        r.jenkins.setSecurityRealm(new FixedIdStrategyRealm(jenkins.model.IdStrategy.CASE_INSENSITIVE));
        FreeStyleProject job = r.createFreeStyleProject("p");
        FreeStyleBuild mine = buildWith(r, job, new Cause.UserIdCause("frost"));
        FreeStyleBuild theirs = buildWith(r, job, new Cause.UserIdCause("bob"));

        // Logged in as "Frost", build recorded as "frost": still this user's build.
        BuildSearchFilter filter = new BuildSearchFilter(SearchQuery.parse(null), "Frost", true);

        assertTrue(filter.matches(mine), "the realm says Frost and frost are the same user");
        assertFalse(filter.matches(theirs));
    }

    @Test
    void onlyMineStaysExactUnderACaseSensitiveRealm(JenkinsRule r) throws Exception {
        // A realm that distinguishes case: a differently-cased ID is somebody else. This proves the
        // filter follows the realm's rule rather than always ignoring case.
        r.jenkins.setSecurityRealm(new FixedIdStrategyRealm(new jenkins.model.IdStrategy.CaseSensitive()));
        FreeStyleProject job = r.createFreeStyleProject("p");
        FreeStyleBuild other = buildWith(r, job, new Cause.UserIdCause("frost"));

        BuildSearchFilter filter = new BuildSearchFilter(SearchQuery.parse(null), "Frost", true);

        assertFalse(filter.matches(other), "a case-sensitive realm treats Frost and frost as two users");
    }

    /** A realm with a chosen user ID strategy, standing in for LDAP, AD or a custom realm. */
    private static final class FixedIdStrategyRealm extends hudson.security.AbstractPasswordBasedSecurityRealm {
        private final jenkins.model.IdStrategy strategy;

        FixedIdStrategyRealm(jenkins.model.IdStrategy strategy) {
            this.strategy = strategy;
        }

        @Override
        protected org.springframework.security.core.userdetails.UserDetails authenticate2(
                String username, String password) {
            throw new org.springframework.security.authentication.BadCredentialsException("not used");
        }

        @Override
        public org.springframework.security.core.userdetails.UserDetails loadUserByUsername2(String username) {
            throw new org.springframework.security.core.userdetails.UsernameNotFoundException(username);
        }

        @Override
        public jenkins.model.IdStrategy getUserIdStrategy() {
            return strategy;
        }
    }

    @Test
    void disabledOnlyMineMatchesEverything(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        FreeStyleBuild mine = buildWith(r, job, new Cause.UserIdCause("alice"));
        FreeStyleBuild theirs = buildWith(r, job, new Cause.UserIdCause("bob"));
        FreeStyleBuild timer = buildWith(r, job, new TimerTrigger.TimerTriggerCause());

        BuildSearchFilter filter = new BuildSearchFilter(SearchQuery.parse(null), "alice", false);

        assertTrue(filter.matches(mine));
        assertTrue(filter.matches(theirs));
        assertTrue(filter.matches(timer));
        assertTrue(filter.isPassThrough(), "no filter active: native history can serve this");
    }

    @Test
    void anonymousSessionMatchesNothingWithOnlyMine(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        FreeStyleBuild anonymous = buildWith(r, job, new Cause.UserIdCause((String) null));
        FreeStyleBuild system = buildWith(r, job, new TimerTrigger.TimerTriggerCause());

        BuildSearchFilter filter = new BuildSearchFilter(SearchQuery.parse(null), null, true);

        assertTrue(filter.isImpossible());
        assertFalse(filter.matches(anonymous), "anonymous builds are not 'mine'");
        assertFalse(filter.matches(system));
    }

    @Test
    void searchesByBuildNumber(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        FreeStyleBuild first = r.buildAndAssertSuccess(job);
        FreeStyleBuild second = r.buildAndAssertSuccess(job);

        BuildSearchFilter filter = filterFor(Integer.toString(second.getNumber()));

        assertTrue(filter.matches(second));
        assertFalse(filter.matches(first));
    }

    @Test
    void searchesByDisplayName(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        FreeStyleBuild signed = r.buildAndAssertSuccess(job);
        signed.setDisplayName("2.0.3.1-QA-signed");
        FreeStyleBuild other = r.buildAndAssertSuccess(job);
        other.setDisplayName("2.0.3.2-dev");

        assertTrue(filterFor("QA-signed").matches(signed));
        assertFalse(filterFor("QA-signed").matches(other));
        assertTrue(filterFor("qa-SIGNED").matches(signed), "default search is case-insensitive");
    }

    @Test
    void searchesByDescription(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        FreeStyleBuild build = r.buildAndAssertSuccess(job);
        build.setDescription("跑 SSRB");
        FreeStyleBuild other = r.buildAndAssertSuccess(job);

        assertTrue(filterFor("跑 SSRB").matches(build));
        assertTrue(filterFor("SSRB").matches(build));
        assertFalse(filterFor("SSRB").matches(other));
    }

    @Test
    void searchesByInitiatorNameAndId(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        User.getById("liam", true).setFullName("Liam Example");
        FreeStyleBuild build = buildWith(r, job, new Cause.UserIdCause("liam"));
        FreeStyleBuild other = buildWith(r, job, new Cause.UserIdCause("bob"));

        assertTrue(filterFor("liam").matches(build), "free text matches the initiator");
        assertTrue(filterFor("Liam Example").matches(build));
        assertFalse(filterFor("liam").matches(other));
    }

    @Test
    void userPrefixSearchesInitiatorOnly(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        User.getById("alex", true).setFullName("Alex Example");
        FreeStyleBuild byAlex = buildWith(r, job, new Cause.UserIdCause("alex"));
        // A build whose description mentions alex but which alex did not start.
        FreeStyleBuild mentioningAlex = buildWith(r, job, new Cause.UserIdCause("bob"));
        mentioningAlex.setDescription("requested by alex");

        BuildSearchFilter filter = filterFor("user:alex");

        assertTrue(filter.matches(byAlex));
        assertFalse(filter.matches(mentioningAlex), "user: is a targeted search, not free text");
    }

    @Test
    void combinesSearchAndOnlyMineWithAnd(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        User.getById("alice", true);
        FreeStyleBuild mineSigned = buildWith(r, job, new Cause.UserIdCause("alice"));
        mineSigned.setDisplayName("1.0-QA-signed");
        FreeStyleBuild mineDev = buildWith(r, job, new Cause.UserIdCause("alice"));
        mineDev.setDisplayName("1.1-dev");

        BuildSearchFilter filter = new BuildSearchFilter(SearchQuery.parse("QA-signed"), "alice", true);

        assertTrue(filter.matches(mineSigned));
        assertFalse(filter.matches(mineDev));
    }

    @Test
    void searchingForAnotherUserWhileOnlyMineIsOnMatchesNothing(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        User.getById("alice", true);
        User.getById("bob", true);
        FreeStyleBuild byBob = buildWith(r, job, new Cause.UserIdCause("bob"));
        FreeStyleBuild byAlice = buildWith(r, job, new Cause.UserIdCause("alice"));

        // Asked for bob's builds while restricted to alice's own: the correct answer is nothing,
        // rather than silently turning the checkbox off.
        BuildSearchFilter filter = new BuildSearchFilter(SearchQuery.parse("user:bob"), "alice", true);

        assertFalse(filter.matches(byBob));
        assertFalse(filter.matches(byAlice));
    }

    @Test
    void doesNotMatchOnParameterValues(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        job.addProperty(new hudson.model.ParametersDefinitionProperty(
                new hudson.model.StringParameterDefinition("TOKEN", "default")));
        FreeStyleBuild build = r.assertBuildStatusSuccess(job.scheduleBuild2(
                0,
                new hudson.model.ParametersAction(new hudson.model.StringParameterValue("TOKEN", "sensitive-value"))));
        r.waitUntilNoActivity();

        // Native Jenkins search does match parameter values. This plugin's API deliberately does
        // not, so that parameter contents are not searchable or returned through it.
        assertFalse(filterFor("sensitive-value").matches(build));
    }

    private static BuildSearchFilter filterFor(String search) {
        return new BuildSearchFilter(SearchQuery.parse(search), "alice", false);
    }
}
