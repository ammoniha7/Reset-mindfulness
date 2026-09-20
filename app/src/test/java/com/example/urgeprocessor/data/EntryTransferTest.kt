package com.example.urgeprocessor.data

import com.example.urgeprocessor.util.calendarDate
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EntryTransferTest {
    private val zone = ZoneId.of("America/Denver")
    private val archive = EntryArchive(
        listOf(StandardJournalEntry(timestamp = 1234567890123L, content = " Unicode 🌱 \"quoted\"\n09-17-2026:\n[Urge Flow]\n ")),
        listOf(UrgeEntry(timestamp = 1234567890124L, category = "Sad (mixed)", specificEmotion = "Unsure", emotionColor = "#FF123456", feltLoved = "yes\nno", stressReason = "a: b", excitementWeek = "weekend")),
        listOf(Quote(text = "A quote | with separators\n"))
    )
    @Test fun backupPreservesEveryFieldAndWhitespace() {
        assertEquals(archive, decodeArchive(encodeArchive(archive)))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsTrailingContent() {
        decodeArchive(encodeArchive(archive) + " junk")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsStringVersion() {
        decodeArchive(JSONObject(encodeArchive(archive)).put("version", "1").toString())
    }
    @Test fun emptyBackupRoundTrips() {
        val empty = EntryArchive(emptyList(), emptyList())
        assertEquals(empty, decodeArchive(encodeArchive(empty)))
    }
    @Test fun importedIdsAreRegenerated() {
        val source = archive.copy(journals = listOf(archive.journals.first().copy(id = 999)))
        assertEquals(0, decodeArchive(encodeArchive(source)).journals.first().id)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownBackupVersion() {
        decodeArchive(JSONObject(encodeArchive(archive)).put("version", 2).toString())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownFormat() {
        decodeArchive(JSONObject(encodeArchive(archive)).put("format", "other").toString())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsNullContent() {
        val json = JSONObject(encodeArchive(archive))
        json.getJSONArray("journals").getJSONObject(0).put("content", JSONObject.NULL)
        decodeArchive(json.toString())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsFractionalTimestamp() {
        val json = JSONObject(encodeArchive(archive))
        json.getJSONArray("journals").getJSONObject(0).put("timestamp", 1.5)
        decodeArchive(json.toString())
    }
    @Test fun parsesBothTextDateFormatsAndInlineContent() {
        val parsed = parseJournalText("09-17-2026: First\n9/18/2026:\nSecond", zone)
        assertEquals(listOf("First", "Second"), parsed.journals.map { it.content })
        assertEquals(LocalDate.of(2026,9,18), calendarDate(parsed.journals[1].timestamp, zone))
    }
    @Test fun datesInsideProseDoNotSplitEntries() {
        val parsed = parseJournalText("09-17-2026:\nI remember 01-02-2025: a good day.", zone)
        assertEquals(1, parsed.journals.size)
        assertTrue(parsed.journals.first().content.contains("01-02-2025"))
    }
    @Test fun removesLegacyJournalLabelAndHandlesWindowsNewlines() {
        assertEquals("Hello", parseJournalText("\uFEFF09-17-2026:\r\n[Journal]\r\nHello\r\n", zone).journals.first().content)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsImpossibleDates() {
        parseJournalText("02-30-2026: Invalid", zone)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsPartiallyInvalidInput() {
        parseJournalText("09-17-2026: Good\n13-18-2026: Bad", zone)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUndatedText() {
        parseJournalText("No dates", zone)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnparsedPreamble() {
        parseJournalText("Do not lose this\n09-17-2026: Hello", zone)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsEmptyEntry() {
        parseJournalText("09-17-2026:\n09-18-2026: Content", zone)
    }
    @Test(expected = IllegalArgumentException::class) fun legacyUrgesCannotSilentlyBecomeJournals() {
        parseJournalText("09-17-2026:\n[Urge Flow]\nCategory: Sad", zone)
    }
    @Test fun restoresMixedLegacyExportWithMultilineAnswers() {
        val parsed = parseJournalText("""
            09-17-2026:
            [Journal]
            A journal entry

            09-17-2026:
            [Urge Flow]
            Category: Sad (Lonely)
            Loved: A friend called
            and listened.
            Stress: Work: a difficult day
            Another line
            Excited: A walk 🌱

            09-18-2026:
            [Urge Flow]
            Category: Happy (Hopeful)
            Excited: Tomorrow
        """.trimIndent().replace("\n", "\r\n"), zone)
        assertEquals("A journal entry", parsed.journals.single().content)
        assertEquals(2, parsed.urges.size)
        val urge = parsed.urges.first()
        assertEquals(LocalDate.of(2026, 9, 17), calendarDate(urge.timestamp, zone))
        assertEquals("Sad", urge.category)
        assertEquals("Lonely", urge.specificEmotion)
        assertEquals("A friend called\nand listened.", urge.feltLoved)
        assertEquals("Work: a difficult day\nAnother line", urge.stressReason)
        assertEquals("A walk 🌱", urge.excitementWeek)
        assertEquals("", urge.emotionColor)
        assertEquals("", parsed.urges.last().feltLoved)
        assertEquals("Tomorrow", parsed.urges.last().excitementWeek)
    }
    @Test fun restoresLegacyUrgeWithoutOptionalAnswers() {
        val urge = parseJournalText("09-17-2026:\n[Urge Flow]\nCategory: Sad ()", zone).urges.single()
        assertEquals("Sad", urge.category)
        assertEquals("", urge.specificEmotion)
        assertEquals("", urge.stressReason)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsRepeatedLegacyAnswerLabels() {
        parseJournalText("09-17-2026:\n[Urge Flow]\nCategory: Sad (Lonely)\nLoved: one\nLoved: two", zone)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnrecognizedLegacyContent() {
        parseJournalText("09-17-2026:\n[Urge Flow]\nCategory: Sad (Lonely)\nDo not discard me", zone)
    }

}
