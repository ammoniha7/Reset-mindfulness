package com.example.urgeprocessor.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class JournalDraftTest {
    private val zone = ZoneId.of("America/Denver")
    @Test fun eveningDraftEndsAt4amNextDay() {
        val draft = JournalDraft.create("Evening", Instant.parse("2026-09-18T03:00:00Z"), zone)
        assertEquals(LocalDate.of(2026, 9, 17), draft.date)
        assertEquals(Instant.parse("2026-09-18T10:00:00Z"), draft.deadline)
        assertFalse(draft.isDue(Instant.parse("2026-09-18T09:59:59Z")))
        assertTrue(draft.isDue(draft.deadline))
    }
    @Test fun writingAfterMidnightStillBelongsToPreviousDay() {
        val draft = JournalDraft.create("Late night", Instant.parse("2026-09-18T09:00:00Z"), zone)
        assertEquals(LocalDate.of(2026, 9, 17), draft.date)
    }
    @Test fun textStartedAt4amBelongsToNewDay() {
        val draft = JournalDraft.create("Morning", Instant.parse("2026-09-18T10:00:00Z"), zone)
        assertEquals(LocalDate.of(2026, 9, 18), draft.date)
        assertEquals(Instant.parse("2026-09-19T10:00:00Z"), draft.deadline)
    }
    @Test fun springAndFallDeadlinesUse4amWallClock() {
        val spring = JournalDraft("spring", "text", LocalDate.of(2026,3,7), zone)
        val fall = JournalDraft("fall", "text", LocalDate.of(2026,10,31), zone)
        assertEquals(Instant.parse("2026-03-08T10:00:00Z"), spring.deadline)
        assertEquals(Instant.parse("2026-11-01T11:00:00Z"), fall.deadline)
    }
    @Test fun delayedSaveRetainsOriginalDateAndWhitespace() {
        val draft = JournalDraft("id", "  Thoughts\n", LocalDate.of(2026,9,17), zone)
        assertTrue(draft.isDue(Instant.parse("2026-09-22T18:00:00Z")))
        assertEquals("  Thoughts\n", draft.toEntry().content)
        assertEquals("id", draft.toEntry().sourceDraftId)
        assertEquals(Instant.parse("2026-09-17T06:00:00Z").toEpochMilli(), draft.toEntry().timestamp)
    }
}
