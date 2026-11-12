package io.jenkins.plugins.bhe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.bhe.AdvancedFilter.Status;
import io.jenkins.plugins.bhe.AdvancedFilter.Trigger;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Parsing of the advanced filter's request parameters. Plain unit tests: no Jenkins needed. */
class AdvancedFilterTest {

    @Test
    void absentParametersMeanNoFilter() {
        AdvancedFilter filter = AdvancedFilter.parse(null, null, null, null);
        assertTrue(filter.isEmpty());
        assertEquals(EnumSet.allOf(Status.class), filter.getStatuses());
        assertEquals(EnumSet.allOf(Trigger.class), filter.getTriggers());
        assertNull(filter.getSinceTimestamp());
        assertFalse(filter.isKeepForeverOnly());
    }

    @Test
    void readsStatusesAndTriggersCaseInsensitively() {
        AdvancedFilter filter = AdvancedFilter.parse(" failure,Unstable ", "timer", null, null);
        assertFalse(filter.isEmpty());
        assertEquals(Set.of(Status.FAILURE, Status.UNSTABLE), filter.getStatuses());
        assertEquals(Set.of(Trigger.TIMER), filter.getTriggers());
    }

    @Test
    void unknownNamesSelectNothingRatherThanEverything() {
        // The client sends NONE when every box in a group is unchecked. A narrowing request must
        // never widen into "all builds".
        AdvancedFilter filter = AdvancedFilter.parse("NONE", "bogus", null, null);
        assertTrue(filter.getStatuses().isEmpty());
        assertTrue(filter.getTriggers().isEmpty());
        assertFalse(filter.isEmpty());
    }

    @Test
    void unknownNamesAreDroppedAlongsideKnownOnes() {
        AdvancedFilter filter = AdvancedFilter.parse("SUCCESS,<script>,FAILURE", null, null, null);
        assertEquals(Set.of(Status.SUCCESS, Status.FAILURE), filter.getStatuses());
    }

    @Test
    void periodIsMeasuredFromTheServerClock() {
        long before = System.currentTimeMillis();
        AdvancedFilter filter = AdvancedFilter.parse(null, null, "DAY", null);
        long after = System.currentTimeMillis();

        Long since = filter.getSinceTimestamp();
        assertNotNull(since);
        long day = AdvancedFilter.Period.DAY.getMilliseconds();
        assertTrue(since >= before - day && since <= after - day, "since should be now minus 24 hours");
        assertFalse(filter.isEmpty());
    }

    @Test
    void unknownPeriodMeansAllTime() {
        // A client cannot pass a raw timestamp: only the named periods are accepted.
        assertNull(AdvancedFilter.parse(null, null, "1700000000000", null).getSinceTimestamp());
        assertNull(AdvancedFilter.parse(null, null, "YEAR", null).getSinceTimestamp());
    }

    @Test
    void readsKeepForever() {
        assertTrue(AdvancedFilter.parse(null, null, null, "true").isKeepForeverOnly());
        assertFalse(AdvancedFilter.parse(null, null, null, "yes").isKeepForeverOnly());
    }

    @Test
    void capsTheNumberOfItemsRead() {
        // A long repeated list is cut rather than parsed in full.
        String many = "SUCCESS,".repeat(10_000) + "FAILURE";
        assertEquals(
                Set.of(Status.SUCCESS),
                AdvancedFilter.parse(many, null, null, null).getStatuses());
    }

    @Test
    void readsACustomRange() {
        AdvancedFilter filter = AdvancedFilter.parse(null, null, null, "1000", "2000", null);
        assertEquals(1000L, filter.getSinceTimestamp());
        assertEquals(2000L, filter.getUntilTimestamp());
        assertFalse(filter.isEmpty());

        AdvancedFilter openEnded = AdvancedFilter.parse(null, null, null, null, "2000", null);
        assertNull(openEnded.getSinceTimestamp());
        assertEquals(2000L, openEnded.getUntilTimestamp());
        assertFalse(openEnded.isEmpty());
    }

    @Test
    void aNamedPeriodWinsOverACustomRange() {
        AdvancedFilter filter = AdvancedFilter.parse(null, null, "DAY", "1000", "2000", null);
        assertNotNull(filter.getSinceTimestamp());
        assertNull(filter.getUntilTimestamp());
    }

    @Test
    void malformedRangeBoundsAreIgnored() {
        AdvancedFilter filter = AdvancedFilter.parse(null, null, null, "abc", "-5", null);
        assertNull(filter.getSinceTimestamp());
        assertNull(filter.getUntilTimestamp());
        assertTrue(filter.isEmpty());
    }
}
