package com.example.urgeprocessor.util

import com.example.urgeprocessor.data.UrgeEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class EntryDatesTest {
    private val zone = ZoneId.of("America/Denver")
    private fun date(value: String) = LocalDate.parse(value)
    private fun entry(value: String) = UrgeEntry(timestamp = dayStart(date(value), zone))

    @Test fun springDayHas23Hours() {
        val start = dayStart(date("2026-03-08"), zone)
        assertEquals(23 * 60 * 60 * 1000L, nextDayStart(start, zone) - start)
    }
    @Test fun fallDayHas25Hours() {
        val start = dayStart(date("2026-11-01"), zone)
        assertEquals(25 * 60 * 60 * 1000L, nextDayStart(start, zone) - start)
    }
    @Test fun cutoffUsesWallClockEvenAfterSpringTransition() {
        assertEquals(date("2026-03-07"), entryDate(Instant.parse("2026-03-08T09:59:59Z"), zone))
        assertEquals(date("2026-03-08"), entryDate(Instant.parse("2026-03-08T10:00:00Z"), zone))
    }
    @Test fun newEntriesUsePriorDayBefore4am() {
        assertEquals(dayStart(date("2026-09-16"), zone), entryTimestamp(Instant.parse("2026-09-17T09:00:00Z"), zone))
    }
    @Test fun springStreakRemainsContinuous() {
        assertEquals(3, calculateStreak(listOf(entry("2026-03-07"), entry("2026-03-08"), entry("2026-03-09")), Instant.parse("2026-03-09T18:00:00Z"), zone))
    }
    @Test fun fallStreakRemainsContinuous() {
        assertEquals(3, calculateStreak(listOf(entry("2026-10-31"), entry("2026-11-01"), entry("2026-11-02")), Instant.parse("2026-11-02T18:00:00Z"), zone))
    }
    @Test fun streakIgnoresDuplicatesAndFutureRecords() {
        assertEquals(2, calculateStreak(listOf(entry("2026-09-17"), entry("2026-09-17"), entry("2026-09-16"), entry("2026-09-20")), Instant.parse("2026-09-17T18:00:00Z"), zone))
    }
    @Test fun yesterdayKeepsStreakAlive() {
        assertEquals(1, calculateStreak(listOf(entry("2026-09-16")), Instant.parse("2026-09-17T18:00:00Z"), zone))
        assertEquals(0, calculateStreak(listOf(entry("2026-09-15")), Instant.parse("2026-09-17T18:00:00Z"), zone))
    }
    @Test fun streakBefore4amUsesPreviousDay() {
        assertEquals(1, calculateStreak(listOf(entry("2026-09-16")), Instant.parse("2026-09-17T09:00:00Z"), zone))
    }
    @Test fun pickerRoundTripPreservesDateInWesternAndEasternZones() {
        val day = date("2026-09-17")
        assertEquals(day, pickerDate(pickerTimestamp(day)))
        listOf(zone, ZoneId.of("Pacific/Auckland")).forEach {
            assertEquals(day, calendarDate(dayStart(pickerDate(pickerTimestamp(day)), it), it))
        }
    }
}
