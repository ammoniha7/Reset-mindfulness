package com.example.urgeprocessor.util

import com.example.urgeprocessor.data.StandardJournalEntry
import com.example.urgeprocessor.data.UrgeEntry
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class OnThisDayTest {
    private val zone = ZoneId.of("America/Denver")
    private fun timestamp(date: String) = dayStart(LocalDate.parse(date), zone)

    @Test fun groupsBothTypesAndExcludesOtherDatesAndCurrentYear() {
        val journals = listOf("2024-09-19", "2025-09-19", "2026-09-19", "2027-09-19", "2025-09-18", "2025-08-19")
            .map { StandardJournalEntry(timestamp = timestamp(it), content = it) }
        val urges = listOf(UrgeEntry(timestamp = timestamp("2023-09-19")), UrgeEntry(timestamp = timestamp("2025-09-19")))
        val result = onThisDayEntries(journals, urges, LocalDate.of(2026, 9, 19), zone)
        assertEquals(listOf(2025, 2024, 2023), result.map { it.year })
        assertEquals(2, result.sumOf { it.journals.size })
        assertEquals(2, result.sumOf { it.urges.size })
        assertEquals(1, result.first().journals.size)
        assertEquals(1, result.first().urges.size)
    }
    @Test fun leapDayKeepsAllExactMatches() {
        val journals = listOf("2024-02-29", "2024-02-29", "2023-02-28", "2023-03-01")
            .map { StandardJournalEntry(timestamp = timestamp(it), content = it) }
        val result = onThisDayEntries(journals, emptyList(), LocalDate.of(2028, 2, 29), zone)
        assertEquals(listOf(2024), result.map { it.year })
        assertEquals(2, result.single().journals.size)
    }
    @Test fun usesLocalDateWithoutApplyingDraftCutoffAgain() {
        val journals = listOf("2025-09-20T05:30:00Z", "2025-09-19T08:00:00Z").map {
            StandardJournalEntry(timestamp = java.time.Instant.parse(it).toEpochMilli(), content = "Entry")
        }
        assertEquals(2, onThisDayEntries(journals, emptyList(), LocalDate.of(2026, 9, 19), zone).single().journals.size)
    }
    @Test fun emptyHistoryHasNoGroups() {
        assertTrue(onThisDayEntries(emptyList(), emptyList(), LocalDate.of(2026, 1, 1), zone).isEmpty())
    }
}
