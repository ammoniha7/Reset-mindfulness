package com.example.urgeprocessor.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class RecordingBackupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun withStorage(block: suspend (AppDatabase, RecordingFiles, File) -> Unit) = runBlocking {
        val id = UUID.randomUUID().toString()
        val name = "recording-backup-$id.db"
        val root = File(context.cacheDir, "recording-backup-$id").apply { mkdirs() }
        val db = AppDatabase.buildDatabase(context, name)
        try { block(db, RecordingFiles(File(root, "audio")), root) }
        finally { db.close(); context.deleteDatabase(name); root.deleteRecursively() }
    }
    private fun recording(): RecordingEntry {
        val id = UUID.randomUUID().toString()
        return RecordingEntry(id, 1_793_495_400_000L, "2026-11-01", "America/Denver", 1234, "Memory 🌱", "$id.m4a")
    }

    @Test fun zipRoundTripPreservesAudioMetadataAndExistingEntries() = withStorage { db, files, root ->
        val source = recording()
        val bytes = byteArrayOf(0, 1, 2, 3, -1)
        files.file(source.fileName).writeBytes(bytes)
        db.urgeDao().insertRecording(source)
        db.urgeDao().insertStandardJournal(StandardJournalEntry(timestamp = 12, content = "Journal"))
        val backup = File(root, "backup.zip")
        writeRecordingBackup(db, files, backup.outputStream())
        assertEquals(2, importRecordingBackup(db, files, backup))
        val restored = db.urgeDao().getAllRecordings().first().first { it.id != source.id }
        assertEquals(source.copy(id = restored.id, fileName = restored.fileName), restored)
        assertArrayEquals(bytes, files.file(restored.fileName).readBytes())
        assertArrayEquals(bytes, files.file(source.fileName).readBytes())
        assertEquals(2, db.urgeDao().getAllStandardJournals().first().size)
    }

    @Test fun missingAudioRejectsEntireImport() = withStorage { db, files, root ->
        val source = recording()
        files.file(source.fileName).writeBytes(byteArrayOf(1, 2, 3))
        db.urgeDao().insertRecording(source)
        val complete = File(root, "complete.zip")
        writeRecordingBackup(db, files, complete.outputStream())
        val incomplete = File(root, "incomplete.zip")
        java.util.zip.ZipFile(complete).use { zip ->
            ZipOutputStream(incomplete.outputStream()).use { output ->
                output.putNextEntry(ZipEntry("entries.json"))
                zip.getInputStream(zip.getEntry("entries.json")).use { it.copyTo(output) }
                output.closeEntry()
            }
        }
        try { importRecordingBackup(db, files, incomplete); fail("Missing audio must fail") }
        catch (_: IllegalArgumentException) { }
        assertEquals(listOf(source), db.urgeDao().getAllRecordings().first())
        assertEquals(1, files.directory.listFiles()!!.size)
    }

    @Test fun damagedAudioRejectsImportAndCleansCopiedFiles() = withStorage { db, files, root ->
        val source = recording()
        files.file(source.fileName).writeBytes(byteArrayOf(1, 2, 3))
        db.urgeDao().insertRecording(source)
        val complete = File(root, "complete.zip")
        writeRecordingBackup(db, files, complete.outputStream())
        val damaged = File(root, "damaged.zip")
        java.util.zip.ZipFile(complete).use { zip ->
            ZipOutputStream(damaged.outputStream()).use { output ->
                zip.entries().toList().forEach { entry ->
                    output.putNextEntry(ZipEntry(entry.name))
                    if (entry.name.startsWith("audio/")) output.write(byteArrayOf(4, 5, 6))
                    else zip.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
        }
        try { importRecordingBackup(db, files, damaged); fail("Damaged audio must fail") }
        catch (_: IllegalArgumentException) { }
        assertEquals(listOf(source), db.urgeDao().getAllRecordings().first())
        assertEquals(1, files.directory.listFiles()!!.size)
    }

    @Test fun databaseFailureRollsBackRecordsAndNewAudioFiles() = withStorage { db, files, root ->
        val source = recording()
        files.file(source.fileName).writeBytes(byteArrayOf(1, 2, 3))
        db.urgeDao().insertRecording(source)
        db.urgeDao().insertStandardJournal(StandardJournalEntry(content = "Keep me"))
        val backup = File(root, "backup.zip")
        writeRecordingBackup(db, files, backup.outputStream())
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_recording BEFORE INSERT ON recordings BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        try { importRecordingBackup(db, files, backup); fail("Import must fail") }
        catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(listOf(source), db.urgeDao().getAllRecordings().first())
        assertEquals(1, db.urgeDao().getAllStandardJournals().first().size)
        assertEquals(1, files.directory.listFiles()!!.size)
    }

    @Test fun version6UpgradeKeepsJournalsAndSupportsMultipleRecordings() = runBlocking {
        val name = "recording-migration-${UUID.randomUUID()}.db"
        val assets = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
        val schema = JSONObject(assets.open("com.example.urgeprocessor.data.AppDatabase/6.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                    val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                    for (j in 0 until indices.length()) {
                        old.execSQL(indices.getJSONObject(j).getString("createSql")
                            .replace("\${TABLE_NAME}", entity.getString("tableName")))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
                old.execSQL("INSERT INTO standard_journal (timestamp, content, sourceDraftId) VALUES (123, 'Existing', 'draft-id')")
                old.version = 6
            }
            val db = AppDatabase.buildDatabase(context, name)
            try {
                assertEquals("Existing", db.urgeDao().getAllStandardJournals().first().single().content)
                assertEquals("draft-id", db.urgeDao().getAllStandardJournals().first().single().sourceDraftId)
                val first = recording()
                val second = recording()
                db.urgeDao().insertRecording(first)
                db.urgeDao().insertRecording(first)
                db.urgeDao().insertRecording(second)
                assertEquals(2, db.urgeDao().getAllRecordings().first().size)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
