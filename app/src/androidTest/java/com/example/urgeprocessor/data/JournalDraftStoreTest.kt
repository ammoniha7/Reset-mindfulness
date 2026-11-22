package com.example.urgeprocessor.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.withTransaction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.urgeprocessor.work.JournalDraftScheduler
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class JournalDraftStoreTest {
    private class MutableClock(var now: Instant) : Clock() {
        override fun instant() = now
        override fun getZone() = ZoneId.of("America/Denver")
        override fun withZone(zone: ZoneId): Clock = fixed(now, zone)
    }
    private class Scheduler : JournalDraftScheduler {
        val scheduled = mutableListOf<JournalDraft>()
        val cancelled = mutableListOf<String>()
        override fun schedule(draft: JournalDraft, now: Instant) { scheduled.add(draft) }
        override fun cancel(id: String) { cancelled.add(id) }
    }
    private class FailNextCommit(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        var failNext = false
        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { editor.putString(key, value) }
                override fun remove(key: String?): SharedPreferences.Editor = apply { editor.remove(key) }
                override fun commit(): Boolean {
                    val saved = editor.commit()
                    return if (failNext) { failNext = false; false } else saved
                }
            }
        }
    }
    private class Fixture(context: Context) {
        val name = "draft-${UUID.randomUUID()}"
        val db = AppDatabase.buildDatabase(context, "$name.db")
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val clock = MutableClock(Instant.parse("2026-09-18T03:00:00Z"))
        val scheduler = Scheduler()
        fun newStore(preferences: SharedPreferences = prefs) = JournalDraftStore(db, preferences, scheduler, clock) { clock.zone }
        val store = newStore()
        fun cutoff() { clock.now = Instant.parse("2026-09-18T10:00:00Z") }
        suspend fun entries() = db.urgeDao().getAllStandardJournals().first()
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val fixture = Fixture(context)
        try { fixture.block() } finally {
            fixture.db.close()
            context.deleteDatabase("${fixture.name}.db")
            context.deleteSharedPreferences(fixture.name)
        }
    }

    @Test fun savesAtCutoffAndClearsEditor() = fixture {
        store.updateText("  My evening\n")
        assertEquals(DraftSaveResult.NOT_DUE, store.saveIfDue())
        assertTrue(entries().isEmpty())
        cutoff()
        assertEquals(DraftSaveResult.SAVED, store.saveIfDue())
        assertEquals("  My evening\n", entries().single().content)
        assertEquals(Instant.parse("2026-09-17T06:00:00Z").toEpochMilli(), entries().single().timestamp)
        assertEquals("", store.state.value.text)
        assertFalse(prefs.contains(JournalDraftStore.KEY))
    }
    @Test fun reopenedSeveralDaysLaterKeepsOriginalDate() = fixture {
        store.updateText("Forgotten")
        clock.now = Instant.parse("2026-09-22T18:00:00Z")
        val restarted = newStore()
        assertEquals(DraftSaveResult.SAVED, restarted.saveIfDue())
        assertEquals(Instant.parse("2026-09-17T06:00:00Z").toEpochMilli(), entries().single().timestamp)
    }
    @Test fun manualSaveThenAutosaveDoesNotDuplicate() = fixture {
        store.updateText("Once")
        assertEquals(DraftSaveResult.SAVED, store.saveManually())
        cutoff()
        assertEquals(DraftSaveResult.EMPTY, store.saveIfDue())
        assertEquals(1, entries().size)
    }
    @Test fun processDeathAfterInsertBeforeCleanupDoesNotDuplicate() = fixture {
        store.updateText("Recover")
        db.urgeDao().insertJournalDraft(store.state.value.draft!!.toEntry())
        cutoff()
        assertEquals(DraftSaveResult.SAVED, newStore().saveIfDue())
        assertEquals(1, entries().size)
    }
    @Test fun blankAndClearedDraftsAreNeverSubmitted() = fixture {
        store.updateText(" \n ")
        cutoff()
        assertEquals(DraftSaveResult.EMPTY, store.saveIfDue())
        store.updateText("Delete me")
        store.updateText("")
        clock.now = Instant.parse("2026-09-20T18:00:00Z")
        assertEquals(DraftSaveResult.EMPTY, store.saveIfDue())
        assertTrue(entries().isEmpty())
    }
    @Test fun failedInsertKeepsDraftAndCanRetry() = fixture {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_draft BEFORE INSERT ON standard_journal BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        store.updateText("Keep on failure")
        cutoff()
        assertEquals(DraftSaveResult.FAILED, store.saveIfDue())
        assertEquals("Keep on failure", store.state.value.text)
        assertTrue(prefs.contains(JournalDraftStore.KEY))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_draft")
        assertEquals(DraftSaveResult.SAVED, store.saveIfDue())
        assertEquals(1, entries().size)
    }
    @Test fun simultaneousManualAndAutomaticSaveAreSafe() = fixture {
        store.updateText("Concurrent")
        cutoff()
        coroutineScope { listOf(async { store.saveManually() }, async { store.saveIfDue() }).awaitAll() }
        assertEquals(1, entries().size)
        assertEquals("", store.state.value.text)
    }
    @Test fun closingTheScreenDuringSaveDoesNotLeaveAStaleDraft() = fixture {
        store.updateText("Save while leaving")
        coroutineScope {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val lockDatabase = launch {
                db.withTransaction { entered.complete(Unit); release.await() }
            }
            entered.await()
            val saving = launch(start = CoroutineStart.UNDISPATCHED) { store.saveManually() }
            assertTrue(store.state.value.saving)
            saving.cancel()
            release.complete(Unit)
            saving.join()
            lockDatabase.join()
        }
        assertEquals(1, entries().size)
        assertEquals("", store.state.value.text)
        store.updateText("New draft")
        store.saveManually()
        assertEquals(2, entries().size)
    }

    @Test fun oldWorkersCannotSaveANewDraft() = fixture {
        store.updateText("First")
        val oldId = store.state.value.draft!!.id
        store.saveManually()
        cutoff()
        store.updateText("Second")
        clock.now = Instant.parse("2026-09-20T18:00:00Z")
        assertEquals(DraftSaveResult.EMPTY, store.saveIfDue(oldId))
        assertEquals("Second", store.state.value.text)
        assertEquals(1, entries().size)
    }
    @Test fun preservesLegacyDraftAndCalendarDrafts() = fixture {
        prefs.edit().putString("journal_draft", "Legacy").putString("calendar_draft_123", "Calendar").commit()
        val migrated = newStore()
        assertEquals("Legacy", migrated.state.value.text)
        assertFalse(prefs.contains("journal_draft"))
        cutoff()
        migrated.saveIfDue()
        assertEquals("Legacy", entries().single().content)
        assertEquals("Calendar", prefs.getString("calendar_draft_123", ""))
    }
    @Test fun editsKeepDeadlineAndLatestText() = fixture {
        store.updateText("First")
        val deadline = store.state.value.draft!!.deadline
        clock.now = Instant.parse("2026-09-18T09:59:59Z")
        store.updateText("Latest")
        assertEquals(deadline, store.state.value.draft!!.deadline)
        assertEquals(1, scheduler.scheduled.size)
        cutoff()
        store.saveIfDue()
        assertEquals("Latest", entries().single().content)
    }
    @Test fun overdueDraftCannotBeOverwrittenBeforeRollover() = fixture {
        store.updateText("Yesterday")
        cutoff()
        assertFalse(store.updateText("Today"))
        store.saveIfDue()
        assertTrue(store.updateText("Today"))
        assertEquals("Yesterday", entries().single().content)
        assertEquals("2026-09-18", store.state.value.draft!!.date.toString())
    }
    @Test fun reviewedDictationAppendsOnceAndSavesOnlyText() = fixture {
        store.updateText("Already written.")
        store.updateDictationText("Spoken thoughts.", durable = true)
        assertTrue(store.useDictationText())
        assertEquals("Already written.\n\nSpoken thoughts.", store.state.value.text)
        assertEquals("", store.dictation.value.text)
        assertFalse(prefs.contains(JournalDraftStore.DICTATION_KEY))
        assertFalse(store.useDictationText())
        val reopened = newStore()
        assertEquals("Already written.\n\nSpoken thoughts.", reopened.state.value.text)
        assertEquals("", reopened.dictation.value.text)
        assertEquals(DraftSaveResult.SAVED, reopened.saveManually())
        assertEquals("Already written.\n\nSpoken thoughts.", entries().single().content)
        assertTrue(db.urgeDao().getAllRecordings().first().isEmpty())
    }
    @Test fun unfinishedDictationSurvivesJournalSavingAndReopening() = fixture {
        store.updateText("Written entry")
        store.updateDictationText("Unfinished spoken entry", durable = true)
        store.saveManually()
        val reopened = newStore()
        assertEquals("Unfinished spoken entry", reopened.dictation.value.text)
        assertTrue(reopened.useDictationText())
        assertEquals("Unfinished spoken entry", reopened.state.value.text)
        assertEquals(1, entries().size)
    }
    @Test fun dictationAtRolloverPreservesBothDays() = fixture {
        store.updateText("Yesterday's writing")
        store.updateDictationText("Today's spoken entry", durable = true)
        cutoff()
        assertTrue(store.useDictationText())
        assertEquals("Yesterday's writing", entries().single().content)
        assertEquals("2026-09-18", store.state.value.draft!!.date.toString())
        assertEquals("Today's spoken entry", store.state.value.text)
        assertEquals("", store.dictation.value.text)
    }
    @Test fun failedRolloverKeepsDictationAndOriginalDraftForRetry() = fixture {
        store.updateText("Keep yesterday")
        store.updateDictationText("Keep spoken text", durable = true)
        cutoff()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_dictation_rollover BEFORE INSERT ON standard_journal BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        assertFalse(store.useDictationText())
        assertEquals("Keep yesterday", store.state.value.text)
        assertEquals("Keep spoken text", newStore().dictation.value.text)
        assertTrue(entries().isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_dictation_rollover")
        assertTrue(store.useDictationText())
        assertEquals("Keep yesterday", entries().single().content)
        assertEquals("Keep spoken text", store.state.value.text)
    }
    @Test fun discardingDictationLeavesWrittenDraftUntouched() = fixture {
        store.updateText("Written")
        store.updateDictationText("Spoken", durable = true)
        store.updateDictationText("", durable = true)
        val reopened = newStore()
        assertEquals("Written", reopened.state.value.text)
        assertEquals("", reopened.dictation.value.text)
    }

    @Test fun failedDictationTransferCanRetryWithoutDuplicatingOrLosingWords() = fixture {
        store.updateText("Existing")
        store.updateDictationText("Spoken", durable = true)
        val failing = FailNextCommit(prefs)
        val retrying = newStore(failing)
        failing.failNext = true
        assertFalse(retrying.useDictationText())
        assertEquals("Existing", retrying.state.value.text)
        assertEquals("Spoken", retrying.dictation.value.text)
        assertEquals("Existing", newStore().state.value.text)
        assertEquals("Spoken", newStore().dictation.value.text)
        assertTrue(retrying.useDictationText())
        assertEquals("Existing\n\nSpoken", retrying.state.value.text)
        assertEquals("", retrying.dictation.value.text)
        assertFalse(retrying.useDictationText())
    }
    @Test fun failedDictationDiscardKeepsWordsVisible() = fixture {
        store.updateText("Existing")
        store.updateDictationText("Keep spoken words", durable = true)
        val failing = FailNextCommit(prefs)
        val retrying = newStore(failing)
        failing.failNext = true
        assertFalse(retrying.updateDictationText("", durable = true))
        assertEquals("Keep spoken words", retrying.dictation.value.text)
        assertEquals("Keep spoken words", newStore().dictation.value.text)
        assertEquals("Existing", newStore().state.value.text)
        assertTrue(retrying.updateDictationText("", durable = true))
        assertEquals("", retrying.dictation.value.text)
    }

}
