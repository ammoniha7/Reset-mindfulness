package com.example.urgeprocessor.data

import android.content.Context
import android.content.SharedPreferences
import com.example.urgeprocessor.work.JournalDraftScheduler
import com.example.urgeprocessor.work.WorkJournalDraftScheduler
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class JournalDraftState(
    val text: String = "",
    val draft: JournalDraft? = null,
    val saving: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

enum class DraftSaveResult { SAVED, EMPTY, NOT_DUE, BUSY, FAILED }

data class DictationDraftState(val text: String = "", val error: String? = null)

/** One process-wide owner keeps the editor, background worker, and manual save in sync. */
class JournalDraftStore internal constructor(
    private val db: AppDatabase,
    private val prefs: SharedPreferences,
    private val scheduler: JournalDraftScheduler,
    private val clock: Clock = Clock.systemUTC(),
    private val zoneProvider: () -> ZoneId = ZoneId::systemDefault
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(load())
    val state = mutableState.asStateFlow()
    private val mutableDictation = MutableStateFlow(
        DictationDraftState(prefs.getString(DICTATION_KEY, "").orEmpty())
    )
    val dictation = mutableDictation.asStateFlow()
    private var transferringDictation = false

    init { mutableState.value.draft?.let { schedule(it) } }

    private fun load(): JournalDraftState {
        val saved = prefs.getString(KEY, null)
        if (saved != null) {
            return try {
                val json = JSONObject(saved)
                val draft = JournalDraft(json.getString("id"), json.getString("content"),
                    LocalDate.parse(json.getString("date")), ZoneId.of(json.getString("zone")))
                JournalDraftState(text = draft.content, draft = draft)
            } catch (e: Exception) {
                // Do not overwrite an unreadable draft.
                JournalDraftState(error = "Your saved draft could not be read. It has been kept on this device.")
            }
        }
        val legacy = prefs.getString("journal_draft", "").orEmpty()
        if (legacy.isBlank()) return JournalDraftState()
        // Older drafts have no date metadata. Preserve their text and start tracking today.
        val draft = JournalDraft.create(legacy, clock.instant(), zoneProvider())
        prefs.edit().putString(KEY, encode(draft)).remove("journal_draft").apply()
        return JournalDraftState(text = legacy, draft = draft)
    }

    /** Returns false while saving or when the old day has ended; the caller then checks rollover. */
    fun updateText(text: String): Boolean = synchronized(lock) {
        val current = mutableState.value
        if (current.saving || (current.draft == null && prefs.contains(KEY)) ||
            current.draft?.isDue(clock.instant()) == true) return false
        val draft = if (text.isBlank()) null else current.draft?.copy(content = text)
            ?: JournalDraft.create(text, clock.instant(), zoneProvider())
        val editor = prefs.edit()
        if (draft == null) editor.remove(KEY) else editor.putString(KEY, encode(draft))
        editor.apply()
        mutableState.value = JournalDraftState(text = text, draft = draft)
        if (current.draft?.id != draft?.id) {
            current.draft?.let { runCatching { scheduler.cancel(it.id) } }
            draft?.let { schedule(it) }
        }
        true
    }

    /** Only words are persisted; speech capture never creates a recording file. */
    fun updateDictationText(text: String, durable: Boolean = false): Boolean = synchronized(lock) {
        if (transferringDictation) return false
        val previous = mutableDictation.value.text
        val editor = prefs.edit()
        if (text.isEmpty()) editor.remove(DICTATION_KEY) else editor.putString(DICTATION_KEY, text)
        val saved = try {
            if (durable) editor.commit() else { editor.apply(); true }
        } catch (_: Exception) { false }
        // commit() changes the preference cache even when its disk write fails.
        // A failed discard must leave the words visible and available for retry.
        val keptText = if (!saved && text.isEmpty()) previous else text
        if (!saved && text.isEmpty()) runCatching {
            prefs.edit().putString(DICTATION_KEY, previous).apply()
        }
        mutableDictation.value = DictationDraftState(keptText,
            if (saved) null else "Could not preserve your spoken draft. Keep this screen open and try adding it again.")
        saved
    }

    /** One preference commit moves reviewed words into the main draft without duplicate appends. */
    suspend fun useDictationText(): Boolean {
        val rollover = saveIfDue()
        if (rollover == DraftSaveResult.FAILED || rollover == DraftSaveResult.BUSY) {
            mutableDictation.value = mutableDictation.value.copy(
                error = "Your journal could not finish saving. Your spoken text is kept; try adding it again."
            )
            return false
        }
        val transfer = synchronized(lock) {
            val current = mutableState.value
            val spoken = mutableDictation.value.text.trim()
            if (transferringDictation || spoken.isBlank()) return false
            if (current.saving || (current.draft == null && prefs.contains(KEY)) ||
                current.draft?.isDue(clock.instant()) == true) {
                mutableDictation.value = mutableDictation.value.copy(
                    error = "Your journal is changing days or saving. Your spoken text is kept; please retry."
                )
                return false
            }
            val content = if (current.text.isBlank()) spoken else current.text.trimEnd() + "\n\n" + spoken
            val draft = current.draft?.copy(content = content)
                ?: JournalDraft.create(content, clock.instant(), zoneProvider())
            transferringDictation = true
            mutableState.value = current.copy(saving = true)
            current to draft
        }
        val (previous, draft) = transfer
        try {
            withContext(Dispatchers.IO + NonCancellable) {
                check(prefs.edit().putString(KEY, encode(draft)).remove(DICTATION_KEY).commit())
                synchronized(lock) {
                    mutableState.value = JournalDraftState(text = draft.content, draft = draft,
                        message = "Spoken text added. Review your entry, then save.")
                    mutableDictation.value = DictationDraftState()
                }
                if (previous.draft?.id != draft.id) schedule(draft)
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            synchronized(lock) {
                // Restore both cached keys after a failed commit, so a retry neither
                // mistakes the new draft for unreadable data nor appends words twice.
                runCatching {
                    val rollback = prefs.edit().putString(DICTATION_KEY, mutableDictation.value.text)
                    if (previous.draft == null) rollback.remove(KEY)
                    else rollback.putString(KEY, encode(previous.draft))
                    rollback.apply()
                }
                mutableState.value = previous
                mutableDictation.value = mutableDictation.value.copy(
                    error = "Could not add the spoken text. It is kept here; please retry."
                )
            }
            return false
        } finally {
            synchronized(lock) { transferringDictation = false }
        }
    }

    private fun schedule(draft: JournalDraft) {
        try {
            scheduler.schedule(draft, clock.instant())
        } catch (e: Exception) {
            mutableState.value = mutableState.value.copy(
                error = "Background saving is unavailable. Your draft is kept and will be checked while Reset is open."
            )
        }
    }

    suspend fun saveManually(): DraftSaveResult = save(automatic = false)
    suspend fun saveIfDue(expectedId: String? = null): DraftSaveResult = save(true, expectedId)

    private suspend fun save(automatic: Boolean, expectedId: String? = null): DraftSaveResult {
        val draft = synchronized(lock) {
            val current = mutableState.value
            if (current.saving) return DraftSaveResult.BUSY
            val pending = current.draft ?: return DraftSaveResult.EMPTY
            if (expectedId != null && expectedId != pending.id) return DraftSaveResult.EMPTY
            if (automatic && !pending.isDue(clock.instant())) return DraftSaveResult.NOT_DUE
            mutableState.value = current.copy(saving = true, error = null)
            pending
        }
        try {
            // A screen closing must not interrupt the insert/cleanup sequence and leave
            // the editor pointing at a draft that was already submitted.
            withContext(Dispatchers.IO + NonCancellable) {
                // Commit the draft before inserting. If the process dies between the database
                // insert and preference cleanup, sourceDraftId makes the retry idempotent.
                check(prefs.edit().putString(KEY, encode(draft)).commit()) { "Could not persist draft" }
                db.urgeDao().insertJournalDraft(draft.toEntry())
                if (!prefs.edit().remove(KEY).commit()) throw IOException("Could not clear saved draft")
                synchronized(lock) {
                    mutableState.value = JournalDraftState(
                        message = if (automatic) "Automatically saved for ${draft.date}." else "Entry saved."
                    )
                }
            }
            if (!automatic) runCatching { scheduler.cancel(draft.id) }
            return DraftSaveResult.SAVED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(lock) {
                mutableState.value = mutableState.value.copy(error = "Could not save your entry. Your draft is kept; please try again.")
            }
            return DraftSaveResult.FAILED
        } finally {
            synchronized(lock) {
                if (mutableState.value.draft?.id == draft.id) {
                    mutableState.value = mutableState.value.copy(saving = false)
                }
            }
        }
    }

    companion object {
        internal const val KEY = "daily_journal_draft_v1"
        internal const val DICTATION_KEY = "dictation_text_draft_v1"
        @Volatile private var instance: JournalDraftStore? = null
        fun get(context: Context): JournalDraftStore = instance ?: synchronized(this) {
            instance ?: JournalDraftStore(
                AppDatabase.getDatabase(context),
                context.applicationContext.getSharedPreferences("journal_prefs", Context.MODE_PRIVATE),
                WorkJournalDraftScheduler(context.applicationContext)
            ).also { instance = it }
        }
        private fun encode(draft: JournalDraft): String = JSONObject().apply {
            put("id", draft.id)
            put("content", draft.content)
            put("date", draft.date.toString())
            put("zone", draft.zone.id)
        }.toString()
    }
}
