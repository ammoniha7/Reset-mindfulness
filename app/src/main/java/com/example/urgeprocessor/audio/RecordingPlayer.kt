package com.example.urgeprocessor.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import com.example.urgeprocessor.data.RecordingEntry
import com.example.urgeprocessor.data.RecordingFiles
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackState(
    val id: String? = null,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null
)

/** One player per surface ensures starting another recording stops the previous one. */
class RecordingPlayer(context: Context) {
    private val files = RecordingFiles(context.applicationContext)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
            if (change < 0) pause()
        }.build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(PlaybackState())
    val state = mutableState.asStateFlow()
    private var player: MediaPlayer? = null

    init {
        scope.launch {
            while (isActive) {
                if (state.value.playing) {
                    runCatching { player?.currentPosition?.toLong() }.getOrNull()?.let {
                        mutableState.value = state.value.copy(positionMs = it)
                    }
                }
                delay(200)
            }
        }
    }

    fun toggle(entry: RecordingEntry) {
        if (state.value.id == entry.id && player != null) {
            if (state.value.loading) return
            if (state.value.playing) pause() else startPrepared()
            return
        }
        stop()
        mutableState.value = PlaybackState(id = entry.id, loading = true, durationMs = entry.durationMs)
        try {
            val audio = files.file(entry.fileName)
            check(audio.isFile && audio.length() > 0)
            val media = MediaPlayer()
            player = media
            media.setAudioAttributes(attributes)
            media.setDataSource(audio.path)
            media.setOnPreparedListener {
                if (player === it) {
                    mutableState.value = state.value.copy(loading = false, durationMs = it.duration.toLong())
                    startPrepared()
                }
            }
            media.setOnCompletionListener {
                if (player === it) {
                    mutableState.value = state.value.copy(playing = false, positionMs = 0)
                    it.seekTo(0)
                    audioManager.abandonAudioFocusRequest(focus)
                }
            }
            media.setOnErrorListener { _, _, _ ->
                fail("Could not play this recording. Check that its audio was restored from backup.")
                true
            }
            media.prepareAsync()
        } catch (_: Exception) {
            fail("Could not open this recording. Check that its audio was restored from backup.")
        }
    }

    private fun startPrepared() {
        try {
            if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                mutableState.value = state.value.copy(error = "Audio is busy. Try playing again shortly.")
                return
            }
            player?.start()
            mutableState.value = state.value.copy(playing = true, error = null)
        } catch (_: Exception) { fail("Could not play this recording. Please try again.") }
    }

    fun pause() {
        if (state.value.loading) { stop(); return }
        if (state.value.playing) {
            runCatching { player?.pause() }
            mutableState.value = state.value.copy(playing = false)
        }
        audioManager.abandonAudioFocusRequest(focus)
    }

    fun seek(positionMs: Long) {
        if (player == null || state.value.loading) return
        val position = positionMs.coerceIn(0, state.value.durationMs)
        runCatching { player?.seekTo(position, MediaPlayer.SEEK_CLOSEST) }
        mutableState.value = state.value.copy(positionMs = position)
    }

    private fun fail(message: String) {
        val id = state.value.id
        stop()
        mutableState.value = PlaybackState(id = id, error = message)
    }

    fun stop() {
        player?.release()
        player = null
        audioManager.abandonAudioFocusRequest(focus)
        mutableState.value = PlaybackState()
    }

    fun release() { stop(); scope.cancel() }
}

fun audioTime(durationMs: Long): String {
    val seconds = durationMs.coerceAtLeast(0) / 1_000
    return if (seconds >= 3_600) "%d:%02d:%02d".format(seconds / 3_600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}
