package com.example.urgeprocessor.audio

import android.Manifest
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.urgeprocessor.data.*
import com.example.urgeprocessor.ui.components.DictationDialog
import com.example.urgeprocessor.ui.theme.ResetTheme
import com.example.urgeprocessor.work.JournalDraftScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class DictationFlowTest {
    @get:Rule val compose = createComposeRule()
    private class Input(override val availability: SpeechAvailability) : SpeechInput {
        var listener: SpeechListener? = null
        val modes = mutableListOf<Boolean>()
        var closed = false
        override fun start(onDevice: Boolean, listener: SpeechListener) {
            modes += onDevice
            this.listener = listener
            listener.ready()
        }
        override fun stop() { listener?.result("I walked by the river.") }
        override fun cancel() = Unit
        override fun close() { closed = true }
    }
    private val scheduler = object : JournalDraftScheduler {
        override fun schedule(draft: JournalDraft, now: Instant) = Unit
        override fun cancel(id: String) = Unit
    }
    private fun withStore(block: (JournalDraftStore, AppDatabase, Context) -> Unit) {
        val context: Context = ApplicationProvider.getApplicationContext()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val id = "dictation-flow-${UUID.randomUUID()}"
        val db = AppDatabase.buildDatabase(context, "$id.db")
        val prefs = context.getSharedPreferences(id, Context.MODE_PRIVATE)
        val store = JournalDraftStore(db, prefs, scheduler)
        try { block(store, db, context) }
        finally { db.close(); context.deleteDatabase("$id.db"); context.deleteSharedPreferences(id) }
    }

    @Test fun dictatedWordsCanBeReviewedAppendedAndSavedWithoutAudio() = withStore { store, db, context ->
        val audioDirectory = File(context.filesDir, "recordings")
        val before = audioDirectory.list()?.toSet().orEmpty()
        store.updateText("Earlier writing.")
        val input = Input(SpeechAvailability(true, true))
        lateinit var controller: DictationController
        compose.runOnUiThread {
            controller = DictationController(input, { store.dictation.value.text },
                { text, durable -> store.updateDictationText(text, durable); Unit })
        }
        var show by mutableStateOf(true)
        compose.setContent {
            ResetTheme {
                if (show) DictationDialog(store, onDismiss = { show = false }, suppliedController = controller)
                else {
                    val journal by store.state.collectAsState()
                    val scope = rememberCoroutineScope()
                    Column {
                        OutlinedTextField(value = journal.text, onValueChange = { store.updateText(it) }, label = { Text("Journal draft") })
                        Button(onClick = { scope.launch { store.saveManually() } }) { Text("Save Entry") }
                    }
                }
            }
        }
        compose.onNodeWithText("Start speaking").performScrollTo().performClick()
        compose.onNodeWithText("Listening…").assertExists()
        compose.runOnIdle {
            input.listener!!.partial("I walked")
            input.listener!!.partial("I walked by the")
        }
        compose.onNodeWithText("Spoken entry").assertTextContains("I walked by the")
        compose.onNodeWithText("Stop speaking").performScrollTo().performClick()
        compose.onNodeWithText("Spoken entry").assertTextContains("I walked by the river.")
        compose.onNodeWithText("Spoken entry").performTextReplacement("I walked by the river. It felt peaceful.")
        compose.onNodeWithText("Add to journal").performScrollTo().performClick()
        compose.waitUntil(5_000) { !show }
        val expected = "Earlier writing.\n\nI walked by the river. It felt peaceful."
        compose.onNodeWithText("Journal draft").assertTextContains(expected)
        assertEquals("", store.dictation.value.text)
        compose.onNodeWithText("Save Entry").performClick()
        compose.waitUntil(5_000) { runBlocking { db.urgeDao().getAllStandardJournals().first().isNotEmpty() } }
        runBlocking {
            assertEquals(expected, db.urgeDao().getAllStandardJournals().first().single().content)
            assertTrue(db.urgeDao().getAllRecordings().first().isEmpty())
        }
        assertEquals(before, audioDirectory.list()?.toSet().orEmpty())
        assertEquals(listOf(true), input.modes)
        assertTrue(input.closed)
    }

    @Test fun systemSpeechRequiresAnExplicitChoiceAndClosingKeepsWords() = withStore { store, _, _ ->
        val input = Input(SpeechAvailability(false, true))
        lateinit var controller: DictationController
        compose.runOnUiThread {
            controller = DictationController(input, { store.dictation.value.text },
                { text, durable -> store.updateDictationText(text, durable); Unit })
        }
        var show by mutableStateOf(true)
        compose.setContent {
            ResetTheme { if (show) DictationDialog(store, { show = false }, controller) }
        }
        compose.onNodeWithText("Start speaking").assertIsNotEnabled()
        assertTrue(input.modes.isEmpty())
        compose.onNodeWithText("Use system speech").performClick()
        compose.onNodeWithText("Start speaking").performScrollTo().performClick()
        compose.runOnIdle { input.listener!!.partial("Keep this thought") }
        compose.onNodeWithText("Keep for later").performScrollTo().performClick()
        compose.waitUntil(5_000) { !show }
        assertEquals(listOf(false), input.modes)
        assertEquals("Keep this thought", store.dictation.value.text)
        compose.runOnIdle { input.listener!!.result("Late words must not be added") }
        assertEquals("Keep this thought", store.dictation.value.text)
    }
}
