package io.jenkins.plugins.bhe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Search box parsing. Plain unit tests: no Jenkins instance needed. */
class SearchQueryTest {

    @Test
    void treatsNullAndBlankAsEmpty() {
        assertTrue(SearchQuery.parse(null).isEmpty());
        assertTrue(SearchQuery.parse("").isEmpty());
        assertTrue(SearchQuery.parse("   ").isEmpty());
    }

    @Test
    void readsFreeText() {
        SearchQuery query = SearchQuery.parse("  2.0.1.67 ");
        assertFalse(query.isEmpty());
        assertFalse(query.isUserSearch());
        assertEquals("2.0.1.67", query.getText());
    }

    @Test
    void readsUserPrefix() {
        SearchQuery query = SearchQuery.parse("user:alex");
        assertTrue(query.isUserSearch());
        assertEquals("alex", query.getUserTerm());
        assertNull(query.getText());
    }

    @Test
    void userPrefixIsCaseInsensitive() {
        assertTrue(SearchQuery.parse("User:alex").isUserSearch());
        assertTrue(SearchQuery.parse("USER:alex").isUserSearch());
    }

    @Test
    void bareUserPrefixIsTreatedAsText() {
        // Mid-typing "user:" should not match every build.
        SearchQuery query = SearchQuery.parse("user:");
        assertFalse(query.isUserSearch());
        assertEquals("user:", query.getText());
    }

    @Test
    void keepsNonAsciiText() {
        SearchQuery query = SearchQuery.parse("跑 SSRB");
        assertEquals("跑 SSRB", query.getText());
    }

    @Test
    void capsOverlongInput() {
        String raw = "x".repeat(SearchQuery.MAX_LENGTH + 500);
        assertEquals(SearchQuery.MAX_LENGTH, SearchQuery.parse(raw).getText().length());
    }

    @Test
    void capsOverlongUserTerm() {
        String raw = "user:" + "y".repeat(SearchQuery.MAX_LENGTH + 500);
        SearchQuery query = SearchQuery.parse(raw);
        assertTrue(query.isUserSearch());
        assertTrue(query.getUserTerm().length() <= SearchQuery.MAX_LENGTH);
    }
}
