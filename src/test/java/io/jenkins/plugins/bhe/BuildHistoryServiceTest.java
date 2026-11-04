package io.jenkins.plugins.bhe;

import static io.jenkins.plugins.bhe.BuildInitiatorResolverTest.buildWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Cause;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Run;
import hudson.model.User;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Paging and scan bounds. */
@WithJenkins
class BuildHistoryServiceTest {

    private static final int PAGE = 5;
    private static final int SCAN = 1000;

    @Test
    void returnsNewestFirst(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        for (int i = 0; i < 3; i++) {
            r.buildAndAssertSuccess(job);
        }

        BuildHistoryService.Page page = search(job, null, null, false, null);

        assertEquals(List.of(3, 2, 1), numbersOf(page));
    }

    @Test
    void pagesBeyondTheFirstPage(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        for (int i = 0; i < 12; i++) {
            r.buildAndAssertSuccess(job);
        }

        BuildHistoryService.Page first = search(job, null, null, false, null);
        assertEquals(List.of(12, 11, 10, 9, 8), numbersOf(first));
        assertTrue(first.isHasMore());
        assertNotNull(first.getNextCursor());

        BuildHistoryService.Page second = search(job, null, null, false, first.getNextCursor());
        assertEquals(List.of(7, 6, 5, 4, 3), numbersOf(second));
        assertTrue(second.isHasMore());

        BuildHistoryService.Page third = search(job, null, null, false, second.getNextCursor());
        assertEquals(List.of(2, 1), numbersOf(third));
        assertFalse(third.isHasMore(), "history exhausted");
        assertNull(third.getNextCursor());
    }

    @Test
    void findsMatchesOlderThanTheFirstPage(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        User.getById("alice", true);
        // The only build by alice is #1, far older than one page of history.
        buildWith(r, job, new Cause.UserIdCause("alice"));
        for (int i = 0; i < 20; i++) {
            buildWith(r, job, new Cause.UserIdCause("bob"));
        }

        BuildHistoryService.Page page = search(job, null, "alice", true, null);

        // This is the point of server-side filtering: a DOM-hiding approach would miss #1 entirely.
        assertEquals(List.of(1), numbersOf(page));
        assertFalse(page.isHasMore());
    }

    @Test
    void respectsTheScanBudgetAndReportsTruncation(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        User.getById("alice", true);
        buildWith(r, job, new Cause.UserIdCause("alice"));
        for (int i = 0; i < 15; i++) {
            buildWith(r, job, new Cause.UserIdCause("bob"));
        }

        // Budget of 5 cannot reach #1, which is 16 builds back.
        BuildHistoryService.Page page = BuildHistoryService.search(job, filter(null, "alice", true), null, PAGE, 5);

        assertTrue(numbersOf(page).isEmpty());
        assertTrue(page.isTruncated(), "must report that it gave up rather than claim no matches");
        assertTrue(page.isHasMore());
        assertEquals(5, page.getScanned());

        // Continuing from the cursor eventually reaches the match, so nothing is unreachable.
        Integer cursor = page.getNextCursor();
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < 10 && cursor != null; i++) {
            BuildHistoryService.Page next =
                    BuildHistoryService.search(job, filter(null, "alice", true), cursor, PAGE, 5);
            found.addAll(numbersOf(next));
            cursor = next.getNextCursor();
        }
        assertEquals(List.of(1), found);
    }

    @Test
    void returnsEmptyPageWhenNothingMatches(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        for (int i = 0; i < 3; i++) {
            r.buildAndAssertSuccess(job);
        }

        BuildHistoryService.Page page = search(job, "no-such-build", null, false, null);

        assertTrue(numbersOf(page).isEmpty());
        assertFalse(page.isHasMore());
        assertFalse(page.isTruncated());
    }

    @Test
    void handlesJobWithNoBuilds(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("empty");

        BuildHistoryService.Page page = search(job, null, null, false, null);

        assertTrue(numbersOf(page).isEmpty());
        assertEquals(0, page.getScanned());
    }

    @Test
    void newBuildsAppearOnTheFirstPageWhileFiltering(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        User.getById("alice", true);
        buildWith(r, job, new Cause.UserIdCause("alice"));

        BuildHistoryService.Page before = search(job, null, "alice", true, null);
        assertEquals(List.of(1), numbersOf(before));

        // A new matching build arrives while the filter is on.
        buildWith(r, job, new Cause.UserIdCause("alice"));

        BuildHistoryService.Page after = search(job, null, "alice", true, null);
        assertEquals(List.of(2, 1), numbersOf(after));
    }

    @Test
    void cursorPagingIsStableWhenNewBuildsArrive(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        for (int i = 0; i < 10; i++) {
            r.buildAndAssertSuccess(job);
        }

        BuildHistoryService.Page first = search(job, null, null, false, null);
        assertEquals(List.of(10, 9, 8, 7, 6), numbersOf(first));

        // Builds appearing between pages must not shift the second page's contents, which is what
        // an offset-based pager would do.
        r.buildAndAssertSuccess(job);
        r.buildAndAssertSuccess(job);

        BuildHistoryService.Page second = search(job, null, null, false, first.getNextCursor());
        assertEquals(List.of(5, 4, 3, 2, 1), numbersOf(second));
    }

    @Test
    void skipsDeletedBuildsWithoutBreakingPaging(JenkinsRule r) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject("p");
        List<FreeStyleBuild> builds = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            builds.add(r.buildAndAssertSuccess(job));
        }
        builds.get(4).delete(); // #5

        BuildHistoryService.Page first = search(job, null, null, false, null);
        assertEquals(List.of(8, 7, 6, 4, 3), numbersOf(first));

        BuildHistoryService.Page second = search(job, null, null, false, first.getNextCursor());
        assertEquals(List.of(2, 1), numbersOf(second));
    }

    private static BuildHistoryService.Page search(
            FreeStyleProject job, String search, String userId, boolean mine, Integer before) {
        return BuildHistoryService.search(job, filter(search, userId, mine), before, PAGE, SCAN);
    }

    private static BuildSearchFilter filter(String search, String userId, boolean mine) {
        return new BuildSearchFilter(SearchQuery.parse(search), userId, mine);
    }

    private static List<Integer> numbersOf(BuildHistoryService.Page page) {
        List<Integer> numbers = new ArrayList<>();
        for (Run<?, ?> run : page.getRuns()) {
            numbers.add(run.getNumber());
        }
        return numbers;
    }
}
