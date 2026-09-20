package com.example.urgeprocessor.data

import com.example.urgeprocessor.util.dayStart
import com.example.urgeprocessor.util.entryDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** A draft keeps its original journal date even if the app is closed for several days. */
data class JournalDraft(
    val id: String,
    val content: String,
    val date: LocalDate,
    val zone: ZoneId
) {
    val deadline: Instant get() = date.plusDays(1).atTime(4, 0).atZone(zone).toInstant()
    fun isDue(now: Instant): Boolean = !now.isBefore(deadline)
    fun toEntry() = StandardJournalEntry(
        timestamp = dayStart(date, zone), content = content, sourceDraftId = id
    )

    companion object {
        fun create(content: String, now: Instant, zone: ZoneId) =
            JournalDraft(UUID.randomUUID().toString(), content, entryDate(now, zone), zone)
    }
}
