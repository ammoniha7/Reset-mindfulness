package com.example.urgeprocessor.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(32_768)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** ZIP streams audio directly rather than holding recordings in memory or preferences. */
suspend fun writeRecordingBackup(db: AppDatabase, files: RecordingFiles, output: OutputStream) = withContext(Dispatchers.IO) {
    val (entries, recordings) = db.withTransaction {
        val dao = db.urgeDao()
        EntryArchive(dao.getAllStandardJournals().first(), dao.getAllEntries().first(), dao.getAllQuotes().first()) to
            dao.getAllRecordings().first()
    }
    val manifest = JSONObject().apply {
        put("format", "reset-media-backup")
        put("version", 1)
        put("entries", JSONObject(encodeArchive(entries)))
        put("recordings", JSONArray().apply {
            recordings.forEach { recording ->
                val audio = files.file(recording.fileName)
                check(audio.isFile && audio.length() > 0) { "A recording's audio is missing. Backup was not completed." }
                put(recording.toJson().put("audioSize", audio.length()).put("sha256", audio.sha256()))
            }
        })
    }
    ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry("entries.json"))
        zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        recordings.forEach { recording ->
            val file = files.file(recording.fileName)
            check(file.isFile && file.length() > 0) { "A recording's audio is missing. Backup was not completed." }
            zip.putNextEntry(ZipEntry("audio/${recording.fileName}"))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}

suspend fun importRecordingBackup(db: AppDatabase, files: RecordingFiles, archiveFile: File): Int =
    // Once file placement starts, finishing or rolling back must outlive navigation cancellation.
    withContext(Dispatchers.IO + NonCancellable) {
        val placed = mutableListOf<File>()
        var committed = false
        try {
            ZipFile(archiveFile).use { zip ->
                val entries = zip.entries().toList()
                require(entries.size <= 10_001 && entries.map { it.name }.distinct().size == entries.size) {
                    "Invalid or duplicate backup files. No records were imported."
                }
                val manifestEntry = zip.getEntry("entries.json") ?: error("Missing backup manifest.")
                require(manifestEntry.size in 1..8_388_608) { "Invalid backup manifest size." }
                val manifest = JSONObject(zip.getInputStream(manifestEntry).bufferedReader(Charsets.UTF_8).use { it.readText() })
                require(manifest.get("format") == "reset-media-backup" && manifest.get("version") == 1) {
                    "This backup format or version is not supported."
                }
                val archive = decodeArchive(manifest.getJSONObject("entries").toString())
                val metadata = manifest.getJSONArray("recordings")
                val recordings = (0 until metadata.length()).map { recordingFromJson(metadata.getJSONObject(it)) }
                require(recordings.map { it.id }.distinct().size == recordings.size && recordings.all { it.durationMs > 0 }) {
                    "Invalid recording metadata. No records were imported."
                }
                val expected = recordings.map { "audio/${it.fileName}" }.toSet() + "entries.json"
                require(entries.map { it.name }.toSet() == expected) { "Backup audio files do not match the manifest." }
                require(entries.all { !it.isDirectory && it.size in 0..2_147_483_648L } && entries.sumOf { it.size } <= 2_147_483_648L) {
                    "Backup is too large or invalid. No records were imported."
                }
                val restored = recordings.mapIndexed { index, source ->
                    val metadataItem = metadata.getJSONObject(index)
                    val size = metadataItem.get("audioSize")
                    require(size is Int || size is Long) { "Invalid audio size in backup." }
                    val expectedSize = (size as Number).toLong()
                    val audioEntry = zip.getEntry("audio/${source.fileName}")
                    require(expectedSize > 0 && expectedSize == audioEntry.size) { "Recording audio size does not match the manifest." }
                    val checksum = metadataItem.get("sha256")
                    require(checksum is String && checksum.matches(Regex("[0-9a-f]{64}"))) { "Invalid audio checksum in backup." }
                    val id = UUID.randomUUID().toString()
                    val target = files.file("$id.m4a")
                    placed += target
                    zip.getInputStream(audioEntry).use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(32_768)
                            var copied = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                copied += count
                                require(copied <= expectedSize) { "Recording audio exceeds its declared size." }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    require(target.length() == expectedSize && target.sha256() == checksum) {
                        "Recording audio is damaged. No records were imported."
                    }
                    source.copy(id = id, fileName = target.name)
                }
                db.withTransaction {
                    archive.journals.forEach { db.urgeDao().insertStandardJournal(it) }
                    archive.urges.forEach { db.urgeDao().insert(it) }
                    archive.quotes.forEach { db.urgeDao().insertQuote(it) }
                    restored.forEach { db.urgeDao().insertRecording(it) }
                }
                committed = true
                archive.journals.size + archive.urges.size + archive.quotes.size + restored.size
            }
        } catch (failure: Throwable) {
            if (!committed) placed.forEach { it.delete() }
            throw failure
        }
    }
