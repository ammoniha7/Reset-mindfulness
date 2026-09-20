package com.example.urgeprocessor.data

import androidx.room.withTransaction
import com.example.urgeprocessor.util.dayStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

data class EntryArchive(
    val journals: List<StandardJournalEntry>,
    val urges: List<UrgeEntry>,
    val quotes: List<Quote> = emptyList()
)

// IDs are intentionally omitted: imports append records and must not overwrite local IDs.
fun encodeArchive(archive: EntryArchive): String = JSONObject().apply {
    put("format", "reset-entry-backup")
    put("version", 1)
    put("journals", JSONArray().apply {
        archive.journals.forEach { entry -> put(JSONObject().apply {
            put("timestamp", entry.timestamp)
            put("content", entry.content)
        }) }
    })
    put("urges", JSONArray().apply {
        archive.urges.forEach { entry -> put(JSONObject().apply {
            put("timestamp", entry.timestamp)
            put("category", entry.category)
            put("specificEmotion", entry.specificEmotion)
            put("emotionColor", entry.emotionColor)
            put("feltLoved", entry.feltLoved)
            put("stressReason", entry.stressReason)
            put("excitementWeek", entry.excitementWeek)
        }) }
    })
    put("quotes", JSONArray().apply { archive.quotes.forEach { put(it.text) } })
}.toString(2)

fun decodeArchive(text: String): EntryArchive {
    val tokens = JSONTokener(text)
    val root = tokens.nextValue()
    require(root is JSONObject && tokens.nextClean() == '\u0000') { "Invalid backup document." }
    require(root.get("format") == "reset-entry-backup") { "This is not a Reset entry backup." }
    require(root.get("version") == 1) { "This backup version is not supported." }
    fun JSONObject.string(key: String): String {
        val value = get(key)
        require(value is String) { "Invalid $key in backup." }
        return value
    }
    fun JSONObject.timestamp(): Long {
        val value = get("timestamp")
        require(value is Long || value is Int) { "Invalid timestamp in backup." }
        return (value as Number).toLong()
    }
    val journals = root.getJSONArray("journals")
    val urges = root.getJSONArray("urges")
    val quotes = root.getJSONArray("quotes")
    return EntryArchive(
        (0 until journals.length()).map { index -> journals.getJSONObject(index).let {
            StandardJournalEntry(timestamp = it.timestamp(), content = it.string("content"))
        } },
        (0 until urges.length()).map { index -> urges.getJSONObject(index).let {
            UrgeEntry(timestamp = it.timestamp(), category = it.string("category"),
                specificEmotion = it.string("specificEmotion"), emotionColor = it.string("emotionColor"),
                feltLoved = it.string("feltLoved"), stressReason = it.string("stressReason"),
                excitementWeek = it.string("excitementWeek"))
        } },
        (0 until quotes.length()).map { index ->
            val value = quotes.get(index)
            require(value is String) { "Invalid quote in backup." }
            Quote(text = value)
        }
    )
}

// A date header must begin a line. Dates embedded in prose do not split entries.
fun parseJournalText(text: String, zone: ZoneId = ZoneId.systemDefault()): EntryArchive {
    val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").trim()
    val header = Regex("""(?m)^[ \t]*(\d{1,2}[-/]\d{1,2}[-/]\d{4}):?[ \t]*""")
    val matches = header.findAll(normalized).toList()
    require(matches.isNotEmpty()) { "No dated entries found. Start each entry with MM-DD-YYYY:." }
    require(normalized.substring(0, matches.first().range.first).isBlank()) { "Text before the first date header cannot be imported." }
    val formatter = DateTimeFormatter.ofPattern("M-d-uuuu", Locale.US).withResolverStyle(ResolverStyle.STRICT)
    val journals = mutableListOf<StandardJournalEntry>()
    val urges = mutableListOf<UrgeEntry>()
    matches.forEachIndexed { index, match ->
        val date = try {
            LocalDate.parse(match.groupValues[1].replace('/', '-'), formatter)
        } catch (e: java.time.DateTimeException) {
            throw IllegalArgumentException("Invalid date: ${match.groupValues[1]}.", e)
        }
        var content = normalized.substring(match.range.last + 1,
            matches.getOrNull(index + 1)?.range?.first ?: normalized.length).trim()
        if (content == "[Urge Flow]" || content.startsWith("[Urge Flow]\n")) {
            urges += parseLegacyUrge(content.removePrefix("[Urge Flow]\n"), dayStart(date, zone))
            return@forEachIndexed
        }
        if (content == "[Journal]" || content.startsWith("[Journal]\n")) {
            content = content.removePrefix("[Journal]").trimStart('\n')
        }
        require(content.isNotBlank()) { "Entry ${match.groupValues[1]} is empty." }
        journals += StandardJournalEntry(timestamp = dayStart(date, zone), content = content)
    }
    return EntryArchive(journals, urges)
}

// The old report omitted colors and times, and only wrote nonblank answers.
private fun parseLegacyUrge(content: String, timestamp: Long): UrgeEntry {
    val categoryLine = content.substringBefore('\n')
    val category = Regex("""^Category: (.*?) \((.*)\)$""").matchEntire(categoryLine)
    require(category != null) { "Invalid legacy urge category. No records were imported." }
    val answers = linkedMapOf<String, StringBuilder>()
    val labels = listOf("Loved", "Stress", "Excited")
    var current: String? = null
    var lastIndex = -1
    content.substringAfter('\n', "").lineSequence().forEach { line ->
        val label = labels.firstOrNull { line == "$it:" || line.startsWith("$it: ") }
        if (label != null) {
            val index = labels.indexOf(label)
            require(index > lastIndex) {
                "Ambiguous legacy urge answers. Repeated or out-of-order answer labels cannot be imported."
            }
            lastIndex = index
            current = label
            answers[label] = StringBuilder(line.removePrefix("$label:").removePrefix(" "))
        } else if (current != null) {
            answers.getValue(current!!).append('\n').append(line)
        } else {
            require(line.isBlank()) { "Unrecognized text after legacy urge category. No records were imported." }
        }
    }
    return UrgeEntry(timestamp = timestamp, category = category.groupValues[1],
        specificEmotion = category.groupValues[2],
        feltLoved = answers["Loved"]?.toString().orEmpty(),
        stressReason = answers["Stress"]?.toString().orEmpty(),
        excitementWeek = answers["Excited"]?.toString().orEmpty())
}

suspend fun exportArchive(db: AppDatabase): String = withContext(Dispatchers.IO) {
    val archive = db.withTransaction {
        val dao = db.urgeDao()
        EntryArchive(dao.getAllStandardJournals().first(), dao.getAllEntries().first(), dao.getAllQuotes().first())
    }
    encodeArchive(archive)
}

suspend fun importEntries(db: AppDatabase, text: String): Int = withContext(Dispatchers.IO) {
    val input = text.removePrefix("\uFEFF").trim()
    // Parse and validate every record before the transaction writes anything.
    val archive = if (input.startsWith("{")) decodeArchive(input) else parseJournalText(input)
    db.withTransaction {
        archive.journals.forEach { db.urgeDao().insertStandardJournal(it) }
        archive.urges.forEach { db.urgeDao().insert(it) }
        archive.quotes.forEach { db.urgeDao().insertQuote(it) }
    }
    archive.journals.size + archive.urges.size + archive.quotes.size
}
