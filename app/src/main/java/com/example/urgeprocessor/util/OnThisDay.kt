package com.example.urgeprocessor.util

import com.example.urgeprocessor.data.StandardJournalEntry
import com.example.urgeprocessor.data.UrgeEntry
import java.time.LocalDate
import java.time.ZoneId

data class PastDayEntries(
    val year: Int,
    val journals: List<StandardJournalEntry>,
    val urges: List<UrgeEntry>
)

fun onThisDayEntries(
    journals: List<StandardJournalEntry>,
    urges: List<UrgeEntry>,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault()
): List<PastDayEntries> {
    fun matchingYear(timestamp: Long): Int? {
        val date = calendarDate(timestamp, zone)
        return date.year.takeIf {
            it < today.year && date.month == today.month && date.dayOfMonth == today.dayOfMonth
        }
    }
    val pastJournals = journals.filter { matchingYear(it.timestamp) != null }
        .groupBy { calendarDate(it.timestamp, zone).year }
    val pastUrges = urges.filter { matchingYear(it.timestamp) != null }
        .groupBy { calendarDate(it.timestamp, zone).year }
    return (pastJournals.keys + pastUrges.keys).sortedDescending().map { year ->
        PastDayEntries(year, pastJournals[year].orEmpty().sortedBy { it.timestamp },
            pastUrges[year].orEmpty().sortedBy { it.timestamp })
    }
}
