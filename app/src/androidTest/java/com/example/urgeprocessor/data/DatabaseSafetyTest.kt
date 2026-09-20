package com.example.urgeprocessor.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DatabaseSafetyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun withDatabase(block: suspend (AppDatabase, String) -> Unit) = runBlocking {
        val name = "safety-${UUID.randomUUID()}.db"
        val db = AppDatabase.buildDatabase(context, name)
        try { block(db, name) } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }


    @Test fun version5MigrationPreservesExistingRecords() = runBlocking {
        val name = "migration-${UUID.randomUUID()}.db"
        val assets = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
        val schema = org.json.JSONObject(assets.open("com.example.urgeprocessor.data.AppDatabase/5.json")
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
                old.execSQL("INSERT INTO standard_journal (timestamp, content) VALUES (123, 'Existing journal')")
                old.execSQL("INSERT INTO quotes (text) VALUES ('Existing quote')")
                old.execSQL("INSERT INTO urge_entries (timestamp, category, specificEmotion, emotionColor, feltLoved, stressReason, excitementWeek) VALUES (124, 'Sad', '', '', '', '', '')")
                old.version = 5
            }
            val upgraded = AppDatabase.buildDatabase(context, name)
            try {
                val journal = upgraded.urgeDao().getAllStandardJournals().first().single()
                assertEquals("Existing journal", journal.content)
                assertNull(journal.sourceDraftId)
                assertEquals("Existing quote", upgraded.urgeDao().getAllQuotes().first().single().text)
                assertEquals("Sad", upgraded.urgeDao().getAllEntries().first().single().category)
                val draft = StandardJournalEntry(timestamp = 125, content = "New draft", sourceDraftId = "unique-draft")
                upgraded.urgeDao().insertJournalDraft(draft)
                upgraded.urgeDao().insertJournalDraft(draft)
                assertEquals(2, upgraded.urgeDao().getAllStandardJournals().first().size)
            } finally { upgraded.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun recordsSurviveReopeningCurrentSchema() = withDatabase { db, name ->
        db.urgeDao().insertStandardJournal(StandardJournalEntry(timestamp = 123L, content = "Keep me"))
        db.close()
        val reopened = AppDatabase.buildDatabase(context, name)
        try {
            assertEquals("Keep me", reopened.urgeDao().getAllStandardJournals().first().single().content)
        } finally { reopened.close() }
    }

    @Test fun missingMigrationDoesNotDeleteRecords() = withDatabase { db, name ->
        db.urgeDao().insertStandardJournal(StandardJournalEntry(timestamp = 123L, content = "Keep me"))
        db.close()
        // Synthetic unsupported version: tests the safety policy, not an invented historical migration.
        val file = context.getDatabasePath(name)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 4 }
        val unsupported = AppDatabase.buildDatabase(context, name)
        try {
            try {
                unsupported.urgeDao().getAllStandardJournals().first()
                fail("Opening without a migration must fail")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("migration", ignoreCase = true))
            }
        } finally { unsupported.close() }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            raw.rawQuery("SELECT content FROM standard_journal", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Keep me", cursor.getString(0))
            }
            assertEquals(4, raw.version)
        }
    }

    @Test fun failedImportRollsBackEarlierInserts() = withDatabase { db, _ ->
        db.urgeDao().insertStandardJournal(StandardJournalEntry(timestamp = 1L, content = "Existing"))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_test BEFORE INSERT ON standard_journal WHEN NEW.content = 'Reject' BEGIN SELECT RAISE(ABORT, 'test failure'); END"
        )
        val archive = EntryArchive(listOf(
            StandardJournalEntry(timestamp = 2L, content = "Would insert"),
            StandardJournalEntry(timestamp = 3L, content = "Reject")
        ), emptyList())
        try {
            importEntries(db, encodeArchive(archive))
            fail("Import should fail")
        } catch (expected: android.database.sqlite.SQLiteException) {
            assertEquals(listOf("Existing"), db.urgeDao().getAllStandardJournals().first().map { it.content })
        }
    }

    @Test fun backupRestoresAllEntryTypes() = withDatabase { db, _ ->
        val archive = EntryArchive(
            listOf(StandardJournalEntry(timestamp = 42L, content = "Journal")),
            listOf(UrgeEntry(timestamp = 43L, category = "Sad", emotionColor = "#FF123456", feltLoved = "Yes")),
            listOf(Quote(text = "Quote"))
        )
        assertEquals(3, importEntries(db, encodeArchive(archive)))
        assertEquals(archive, decodeArchive(exportArchive(db)))
    }
}
