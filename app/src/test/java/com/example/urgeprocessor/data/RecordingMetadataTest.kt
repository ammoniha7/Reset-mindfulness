package com.example.urgeprocessor.data

import com.example.urgeprocessor.util.entryDate
import com.example.urgeprocessor.util.onThisDayEntries
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class RecordingMetadataTest {
    private val id = "5345a8d6-b2f2-4ec8-830a-000000000001"
    private val recorded = Instant.parse("2026-11-14T08:30:00Z")
    private val zone = ZoneId.of("America/Denver")
    private val entry = RecordingEntry(id, recorded.toEpochMilli(), entryDate(recorded, zone).toString(),
        zone.id, 10_000, "A memory 🌱", "$id.m4a")

    @Test fun exactTimeAndAssignedDaySurviveSerializationAndTravel() {
        assertEquals("2026-11-13", entry.journalDate)
        val restored = recordingFromJson(entry.toJson())
        assertEquals(entry, restored)
        assertEquals(recorded, Instant.ofEpochMilli(restored.timestamp))
        assertEquals(LocalDate.parse("2026-11-14"), recorded.atZone(ZoneId.of("Asia/Tokyo")).toLocalDate())
        assertEquals("2026-11-13", restored.journalDate)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsPathTraversal() {
        RecordingFiles(File("/tmp/unused-recording-test")).file("../outside.m4a")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsMismatchedFileNames() {
        recordingFromJson(entry.toJson().put("fileName", "../outside.m4a"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsNegativeDuration() {
        recordingFromJson(entry.toJson().put("durationMs", -1))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsFractionalCaptureTime() {
        recordingFromJson(entry.toJson().put("timestamp", 1.5))
    }
    @Test fun memoriesUseTheAssignedJournalDayAcrossTimeZones() {
        val result = onThisDayEntries(emptyList(), emptyList(), LocalDate.of(2027, 11, 13),
            ZoneId.of("Asia/Tokyo"), listOf(entry))
        assertEquals(entry, result.single().recordings.single())
        assertTrue(onThisDayEntries(emptyList(), emptyList(), LocalDate.of(2027, 11, 14),
            ZoneId.of("Asia/Tokyo"), listOf(entry)).isEmpty())
    }
}
