package io.jenkins.plugins.bhe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The reuse-parameters column for Jenkins' experimental new job page.
 *
 * <p>The tests run against the plugin's baseline Jenkins, where the column's API does not exist. They
 * pin down that the plugin still loads and works there. Behaviour on Jenkins 2.589 and newer, where
 * the column appears, is checked by the CI job that runs this suite against that version.
 */
@WithJenkins
class NewBuildsListTest {

    private static final String API = "jenkins.widgets.HistoryPageEntryDecorator";
    // A string, not ReuseParametersEntryDecorator.class: the class literal would itself load the
    // class, and on the baseline Jenkins that throws the very error the first test checks for.
    private static final String DECORATOR = "io.jenkins.plugins.bhe.ReuseParametersEntryDecorator";

    @Test
    void decoratorIsSkippedQuietlyWhereTheApiDoesNotExist(JenkinsRule r) throws Exception {
        if (apiPresent()) {
            return; // A newer Jenkins: covered by the test below.
        }
        // The class must not be loadable, and the plugin's other extensions must still work.
        assertFalse(loadable(DECORATOR), "decorator should not link");
        assertTrue(ExtensionList.lookup(BuildHistoryApi.FactoryImpl.class).size() == 1, "API factory loaded");
        FreeStyleProject job = r.createFreeStyleProject("p");
        r.buildAndAssertSuccess(job);
        String page = r.createWebClient().getPage(job).getWebResponse().getContentAsString();
        assertTrue(page.contains("bhe-root"), "the classic sidebar is still enhanced");
    }

    @Test
    void decoratorIsRegisteredWhereTheApiExists(JenkinsRule r) throws Exception {
        if (!apiPresent()) {
            return; // The baseline Jenkins: covered by the test above.
        }
        Class<?> api = Class.forName(API);
        @SuppressWarnings({"unchecked", "rawtypes"})
        ExtensionList<?> all = ExtensionList.lookup((Class) api);
        long ours = all.stream()
                .filter(d -> d.getClass().getName().equals(DECORATOR))
                .count();
        assertEquals(1, ours, "the decorator is registered exactly once");
    }

    private static boolean apiPresent() {
        return loadable(API);
    }

    private static boolean loadable(String name) {
        try {
            Class.forName(name, true, NewBuildsListTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
