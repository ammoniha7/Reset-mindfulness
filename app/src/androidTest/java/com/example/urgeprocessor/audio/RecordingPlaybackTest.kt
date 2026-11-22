package com.example.urgeprocessor.audio

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.urgeprocessor.data.RecordingEntry
import com.example.urgeprocessor.data.RecordingFiles
import com.example.urgeprocessor.ui.components.RecordingPlayback
import com.example.urgeprocessor.ui.theme.ResetTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class RecordingPlaybackTest {
    @get:Rule val compose = createComposeRule()

    @Test fun speedsPreservePauseSeekAndSwitchingRecordings() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val id = UUID.randomUUID().toString()
        val file = RecordingFiles(context).file("$id.m4a")
        // MediaPlayer detects the WAV header; deterministic audio avoids microphone access.
        val sampleRate = 8000
        val dataSize = sampleRate * 20 * 2
        val audio = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        audio.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
        audio.putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2)
        audio.putShort(2).putShort(16).put("data".toByteArray()).putInt(dataSize)
        file.writeBytes(audio.array())
        val entry = RecordingEntry(id, 0, "2026-11-21", "UTC", 20_000, fileName = file.name)
        lateinit var player: RecordingPlayer
        compose.runOnUiThread { player = RecordingPlayer(context) }
        try {
            compose.setContent { ResetTheme { Column { RecordingPlayback(entry, player) } } }
            for (label in listOf("1×", "1.5×", "2×")) {
                compose.onNodeWithText(label).performClick()
                assertFalse(player.state.value.playing)
            }
            compose.onNodeWithText("2.5×").assertExists()
            compose.onNodeWithContentDescription("Play recording").performClick()
            compose.waitUntil(5_000) { player.state.value.playing }
            val before = player.state.value.positionMs
            Thread.sleep(1_000)
            assertTrue("Playback should advance faster than real time", player.state.value.positionMs - before > 1_600)
            compose.onNodeWithContentDescription("Pause recording").performClick()
            val paused = player.state.value.positionMs
            compose.onNodeWithText("2.5×").performClick()
            Thread.sleep(350)
            assertFalse(player.state.value.playing)
            assertEquals(paused, player.state.value.positionMs)
            compose.onNodeWithText("1×").performClick()
            compose.runOnUiThread { player.seek(5_000) }
            assertEquals(5_000L, player.state.value.positionMs)
            assertEquals(1.5f, player.state.value.speed)
            compose.onNodeWithContentDescription("Play recording").performClick()
            compose.waitUntil(5_000) { player.state.value.playing && player.state.value.positionMs > 5_000 }
            compose.onNodeWithText("1.5×").performClick()
            assertTrue(player.state.value.playing)
            assertEquals(2f, player.state.value.speed)
            compose.runOnUiThread { player.toggle(entry.copy(id = UUID.randomUUID().toString())) }
            compose.waitUntil(5_000) { player.state.value.playing }
            assertEquals(2f, player.state.value.speed)
            compose.runOnUiThread { player.stop() }
            assertEquals(2f, player.state.value.speed)
        } finally {
            compose.runOnUiThread { player.release() }
            file.delete()
        }
    }
}
