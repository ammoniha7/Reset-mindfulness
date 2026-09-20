package com.example.urgeprocessor.util

import com.example.urgeprocessor.data.UrgeEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

fun calendarDate(timestamp: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()

// New entries belong to the previous day until 4 a.m. local wall-clock time.
fun entryDate(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): LocalDate {
    val local = now.atZone(zone)
    return local.toLocalDate().minusDays(if (local.hour < 4) 1 else 0)
}

fun dayStart(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
    date.atStartOfDay(zone).toInstant().toEpochMilli()

fun entryTimestamp(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Long =
    dayStart(entryDate(now, zone), zone)

fun nextDayStart(timestamp: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    dayStart(calendarDate(timestamp, zone).plusDays(1), zone)

// Material date pickers encode a calendar date at UTC midnight, not local midnight.
fun pickerTimestamp(date: LocalDate): Long = dayStart(date, ZoneOffset.UTC)
fun pickerDate(timestamp: Long): LocalDate = calendarDate(timestamp, ZoneOffset.UTC)

fun calculateStreak(
    entries: List<UrgeEntry>,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault()
): Int {
    val today = entryDate(now, zone)
    val days = entries.map { calendarDate(it.timestamp, zone) }.toSet()
    var day = if (today in days) today else today.minusDays(1)
    var streak = 0
    while (day in days) {
        streak++
        day = day.minusDays(1)
    }
    return streak
}
