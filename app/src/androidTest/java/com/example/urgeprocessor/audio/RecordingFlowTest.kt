package com.example.urgeprocessor.audio

import android.Manifest
import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.urgeprocessor.data.*
import com.example.urgeprocessor.ui.components.RecordingDialog
import com.example.urgeprocessor.ui.screens.JournalCalendarView
import com.example.urgeprocessor.ui.theme.ResetTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class RecordingFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun recordPauseSavePlayFromCalendarDayAndDelete() {
        val context: Context = ApplicationProvider.getApplicationContext()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val id = UUID.randomUUID().toString()
        val name = "recording-flow-$id.db"
        val db = AppDatabase.buildDatabase(context, name)
        // Playback uses app-private recording storage; only this test's UUID files are removed.
        val files = RecordingFiles(context)
        val prefs = context.getSharedPreferences("recording-test-$id", Context.MODE_PRIVATE)
        lateinit var controller: RecordingController
        compose.runOnUiThread { controller = RecordingController(context, files, prefs, db) }
        var saved by mutableStateOf(false)
        var captured: RecordingEntry? = null
        try {
            compose.setContent {
                ResetTheme {
                    if (!saved) RecordingDialog(db, controller, onDismiss = { saved = true })
                    else {
                        val recordings by db.urgeDao().getAllRecordings().collectAsState(emptyList())
                        JournalCalendarView(db, emptyList(), emptyList(), Color.Blue, Color.Green, Color.Magenta,
                            onBack = {}, recordingEntries = recordings)
                    }
                }
            }
            compose.onNodeWithText("Start recording").performClick()
            compose.onNodeWithText("Recording…").assertIsDisplayed()
            Thread.sleep(1_200)
            compose.onNodeWithText("Pause", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Paused").assertIsDisplayed()
            val pausedAt = controller.elapsedMs()
            Thread.sleep(400)
            assertEquals(pausedAt, controller.elapsedMs())
            compose.onNodeWithText("Resume").performClick()
            Thread.sleep(700)
            compose.onNodeWithText("Stop").performClick()
            compose.onNodeWithText("Ready to save").assertIsDisplayed()
            compose.onNodeWithText("Title (optional)").performTextInput("Test memory")
            compose.onNodeWithText("Save recording").performClick()
            compose.waitUntil(10_000) { saved }
            captured = runBlocking { db.urgeDao().getAllRecordings().first().single() }
            assertTrue(captured.durationMs >= 1_500)
            assertEquals("Test memory", captured.title)
            assertTrue(files.file(captured.fileName).length() > 0)
            assertTrue(kotlin.math.abs(System.currentTimeMillis() - captured.timestamp) < 30_000)
            compose.onNodeWithText("Test memory").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("Play recording").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Pause recording").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Pause recording").performClick()
            // Opening the day must also show the audio card and playback controls.
            compose.onNodeWithText(java.time.LocalDate.parse(captured.journalDate).dayOfMonth.toString()).performScrollTo().performClick()
            compose.onNode(isDialog()).assertExists()
            compose.onNode(hasText("Test memory") and hasAnyAncestor(isDialog())).assertIsDisplayed()
            compose.onNode(hasContentDescription("Delete recording") and hasAnyAncestor(isDialog())).performClick()
            compose.onNodeWithText("Delete", useUnmergedTree = true).performClick()
            compose.waitUntil(5_000) { runBlocking { db.urgeDao().getAllRecordings().first().isEmpty() } && !files.file(captured.fileName).exists() }
            assertFalse(files.file(captured.fileName).exists())
        } finally {
            compose.runOnUiThread { controller.release() }
            captured?.let { files.file(it.fileName).delete() }
            controller.state.value.draft?.let { files.file(it.fileName).delete() }
            prefs.edit().clear().commit()
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun stoppedDraftRecoversAndSavingAgainCannotDuplicateIt() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val id = UUID.randomUUID().toString()
        val name = "recording-recovery-$id.db"
        val db = AppDatabase.buildDatabase(context, name)
        val files = RecordingFiles(File(context.cacheDir, "recording-recovery-$id"))
        val prefs = context.getSharedPreferences("recording-test-$id", Context.MODE_PRIVATE)
        // Generate a valid AAC/M4A using the same recording implementation.
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        var first: RecordingController? = null
        var restored: RecordingController? = null
        try {
            compose.runOnUiThread { first = RecordingController(context, files, prefs, db); first!!.start() }
            Thread.sleep(1_200)
            compose.runOnUiThread { first!!.stop(); first!!.updateTitle("Recovered"); first!!.release() }
            val original = first!!.state.value.draft!!
            compose.runOnUiThread { restored = RecordingController(context, files, prefs, db) }
            compose.waitUntil(5_000) { !restored!!.state.value.saving }
            assertEquals("Recovered", restored!!.state.value.draft!!.title)
            assertTrue(restored!!.state.value.draft!!.durationMs > 0)
            // Simulate a save committed just before process death, leaving pending metadata.
            db.urgeDao().insertRecording(original)
            compose.runOnUiThread { restored!!.save(db) {} }
            compose.waitUntil(5_000) { restored!!.state.value.draft == null }
            assertEquals(1, db.urgeDao().getAllRecordings().first().size)
            assertTrue(files.file(original.fileName).exists())
        } finally {
            compose.runOnUiThread { first?.release(); restored?.release() }
            prefs.edit().clear().commit()
            db.close(); context.deleteDatabase(name); files.directory.deleteRecursively()
        }
    }
}
