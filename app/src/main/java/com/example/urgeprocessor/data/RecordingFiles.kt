package com.example.urgeprocessor.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class RecordingFiles(val directory: File) {
    constructor(context: Context) : this(File(context.filesDir, "recordings"))

    fun file(name: String): File {
        require(Regex("[0-9a-fA-F-]{36}\\.m4a").matches(name)) { "Invalid recording file name." }
        UUID.fromString(name.removeSuffix(".m4a"))
        check(directory.isDirectory || directory.mkdirs()) { "Could not open recording storage." }
        return File(directory, name)
    }
}

fun RecordingEntry.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("timestamp", timestamp)
    put("journalDate", journalDate)
    put("zoneId", zoneId)
    put("durationMs", durationMs)
    put("title", title)
    put("fileName", fileName)
}

fun recordingFromJson(json: JSONObject): RecordingEntry {
    fun string(key: String): String = (json.get(key) as? String)
        ?: throw IllegalArgumentException("Invalid recording $key.")
    fun long(key: String): Long {
        val value = json.get(key)
        require(value is Long || value is Int) { "Invalid recording $key." }
        return (value as Number).toLong()
    }
    val id = string("id").also { UUID.fromString(it) }
    val date = string("journalDate").also { LocalDate.parse(it) }
    val zone = string("zoneId").also { ZoneId.of(it) }
    val name = string("fileName")
    require(name == "$id.m4a") { "Invalid recording file name." }
    val duration = long("durationMs")
    require(duration >= 0) { "Invalid recording duration." }
    return RecordingEntry(id, long("timestamp"), date, zone, duration, string("title"), name)
}
