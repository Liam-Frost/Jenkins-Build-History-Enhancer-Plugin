package io.jenkins.plugins.bhe;

import static io.jenkins.plugins.bhe.BuildInitiatorResolverTest.buildWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Cause;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.triggers.SCMTrigger;
import hudson.triggers.TimerTrigger;
import java.lang.reflect.Field;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Matching of real runs against the advanced filter, alone and combined with the other filters. */
@WithJenkins
class AdvancedFilterMatchingTest {

    @Test
    void filtersByStatus(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        FreeStyleBuild ok = r.buildAndAssertSuccess(job);
        job.getBuildersList().add(new FailureBuilder());
        FreeStyleBuild failed = r.buildAndAssertStatus(Result.FAILURE, job);

        BuildSearchFilter onlyFailures = filter(AdvancedFilter.parse("FAILURE", null, null, null));
        assertFalse(onlyFailures.matches(ok));
        assertTrue(onlyFailures.matches(failed));
        assertFalse(filter(AdvancedFilter.parse("NONE", null, null, null)).matches(ok));
    }

    @Test
    void filtersByTriggerAndMatchesAnyOfSeveralCauses(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        FreeStyleBuild timer = buildWith(r, job, new TimerTrigger.TimerTriggerCause());
        FreeStyleBuild scm = buildWith(r, job, new SCMTrigger.SCMTriggerCause("poll"));
        FreeStyleBuild both = buildWith(r, job, new TimerTrigger.TimerTriggerCause(), new Cause.UserIdCause("alice"));
        FreeStyleBuild remote = buildWith(r, job, new Cause.RemoteCause("10.0.0.1", "hook"));

        BuildSearchFilter onlyTimer = filter(AdvancedFilter.parse(null, "TIMER", null, null));
        assertTrue(onlyTimer.matches(timer));
        assertFalse(onlyTimer.matches(scm));
        assertTrue(onlyTimer.matches(both), "a build with a timer cause among others still matches");
        assertTrue(filter(AdvancedFilter.parse(null, "USER", null, null)).matches(both));
        assertTrue(filter(AdvancedFilter.parse(null, "REMOTE", null, null)).matches(remote));
        assertFalse(filter(AdvancedFilter.parse(null, "OTHER", null, null)).matches(remote));
    }

    @Test
    void filtersKeptForeverBuilds(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        FreeStyleBuild kept = r.buildAndAssertSuccess(job);
        kept.keepLog(true);
        FreeStyleBuild normal = r.buildAndAssertSuccess(job);

        BuildSearchFilter keep = filter(AdvancedFilter.parse(null, null, null, "true"));
        assertTrue(keep.matches(kept));
        assertFalse(keep.matches(normal));
    }

    @Test
    void timeRangeExcludesOlderBuildsAndStopsTheWalk(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        FreeStyleBuild old1 = r.buildAndAssertSuccess(job);
        FreeStyleBuild old2 = r.buildAndAssertSuccess(job);
        FreeStyleBuild recent = r.buildAndAssertSuccess(job);
        long tenDaysAgo = System.currentTimeMillis() - 10L * 24 * 60 * 60 * 1000;
        backdate(old1, tenDaysAgo - 1000);
        backdate(old2, tenDaysAgo);

        BuildSearchFilter week = filter(AdvancedFilter.parse(null, null, "WEEK", null));
        assertTrue(week.matches(recent));
        assertFalse(week.matches(old2));

        BuildHistoryService.Page page = BuildHistoryService.search(job, week, null, 5, 1000);
        assertEquals(List.of(3), numbers(page));
        assertFalse(page.isHasMore(), "nothing older can match, so there is no older page");
        assertEquals(1, page.getScanned(), "the walk stops at the first build outside the range");
    }

    @Test
    void customRangeSkipsNewerBuildsAndStopsAtOlderOnes(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        FreeStyleBuild b1 = r.buildAndAssertSuccess(job);
        FreeStyleBuild b2 = r.buildAndAssertSuccess(job);
        FreeStyleBuild b3 = r.buildAndAssertSuccess(job);
        long day = 24L * 60 * 60 * 1000;
        long now = System.currentTimeMillis();
        backdate(b1, now - 10 * day);
        backdate(b2, now - 5 * day);
        // b3 stays "now", newer than the range

        String from = String.valueOf(now - 7 * day);
        String to = String.valueOf(now - 3 * day);
        BuildSearchFilter range = filter(AdvancedFilter.parse(null, null, null, from, to, null));
        assertFalse(range.matches(b1));
        assertTrue(range.matches(b2));
        assertFalse(range.matches(b3));

        BuildHistoryService.Page page = BuildHistoryService.search(job, range, null, 5, 1000);
        assertEquals(List.of(2), numbers(page));
        assertFalse(page.isHasMore());
    }

    @Test
    void combinesWithOnlyMineAndSearchWithAnd(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        FreeStyleBuild mineKept = buildWith(r, job, new Cause.UserIdCause("alice"));
        mineKept.keepLog(true);
        FreeStyleBuild theirsKept = buildWith(r, job, new Cause.UserIdCause("bob"));
        theirsKept.keepLog(true);
        FreeStyleBuild mineNormal = buildWith(r, job, new Cause.UserIdCause("alice"));

        AdvancedFilter keep = AdvancedFilter.parse(null, null, null, "true");
        BuildSearchFilter mineAndKept = new BuildSearchFilter(SearchQuery.parse(null), "alice", true, keep);
        assertTrue(mineAndKept.matches(mineKept));
        assertFalse(mineAndKept.matches(theirsKept));
        assertFalse(mineAndKept.matches(mineNormal));
        assertFalse(mineAndKept.isPassThrough());
    }

    private static BuildSearchFilter filter(AdvancedFilter advanced) {
        return new BuildSearchFilter(SearchQuery.parse(null), "alice", false, advanced);
    }

    /** Builds made in a test are always "now"; this moves one into the past. */
    private static void backdate(Run<?, ?> run, long timestamp) throws Exception {
        Field field = Run.class.getDeclaredField("timestamp");
        field.setAccessible(true);
        field.setLong(run, timestamp);
    }

    private static List<Integer> numbers(BuildHistoryService.Page page) {
        return page.getRuns().stream().map(Run::getNumber).collect(Collectors.toList());
    }
}
