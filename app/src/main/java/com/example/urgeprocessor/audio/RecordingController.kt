package com.example.urgeprocessor.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import com.example.urgeprocessor.data.*
import com.example.urgeprocessor.util.entryDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

data class RecorderState(
    val draft: RecordingEntry? = null,
    val recording: Boolean = false,
    val paused: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val message: String? = null
)

/** Application-owned session: configuration changes cannot lose a stopped recording. */
class RecordingController internal constructor(
    private val context: Context,
    val files: RecordingFiles = RecordingFiles(context),
    private val prefs: android.content.SharedPreferences = context.getSharedPreferences("recording_draft", Context.MODE_PRIVATE),
    private val database: AppDatabase = AppDatabase.getDatabase(context)
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(RecorderState())
    val state = mutableState.asStateFlow()
    private var recorder: MediaRecorder? = null
    private var segmentStarted = 0L

    init {
        val pending = prefs.getString("pending", null)
        if (pending != null) {
            val draft = runCatching { recordingFromJson(JSONObject(pending)) }.getOrNull()
            if (draft != null) {
                mutableState.value = RecorderState(draft = draft, saving = true)
                scope.launch {
                    try {
                        val alreadySaved = database.urgeDao().getRecording(draft.id) != null
                        if (alreadySaved) {
                            clearPending()
                            mutableState.value = RecorderState()
                        } else {
                            val duration = withContext(Dispatchers.IO) { audioDuration(draft) }
                            val recovered = draft.copy(durationMs = duration)
                            persist(recovered)
                            mutableState.value = RecorderState(draft = recovered,
                                message = "Your unfinished recording is ready to review.")
                        }
                    } catch (_: Exception) {
                        mutableState.value = RecorderState(draft = draft.copy(durationMs = 0),
                            error = "This interrupted recording could not be recovered. Discard it to record again.")
                    }
                }
            } else {
                mutableState.value = RecorderState(error = "The unfinished recording could not be recovered.")
            }
        }
    }

    private fun persist(draft: RecordingEntry) {
        check(prefs.edit().putString("pending", draft.toJson().toString()).commit()) {
            "Could not preserve recording draft."
        }
    }

    private fun clearPending() {
        check(prefs.edit().remove("pending").commit()) { "Could not clear recording draft." }
    }

    private fun audioDuration(draft: RecordingEntry): Long {
        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(files.file(draft.fileName).path)
            return metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.takeIf { it > 0 } ?: error("No audio was captured")
        } finally { metadata.release() }
    }

    @Suppress("DEPRECATION")
    fun start() {
        if (state.value.draft != null || state.value.saving) return
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val id = UUID.randomUUID().toString()
        val draft = RecordingEntry(id, now.toEpochMilli(), entryDate(now, zone).toString(),
            zone.id, 0, fileName = "$id.m4a")
        var device: MediaRecorder? = null
        try {
            persist(draft)
            device = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            device.setAudioSource(MediaRecorder.AudioSource.MIC)
            device.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            device.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            device.setAudioSamplingRate(44_100)
            device.setAudioEncodingBitRate(96_000)
            device.setOutputFile(files.file(draft.fileName).path)
            device.setOnErrorListener { _, _, _ -> stop("Recording was interrupted. Review the captured audio before saving.") }
            device.prepare()
            device.start()
            recorder = device
            segmentStarted = SystemClock.elapsedRealtime()
            mutableState.value = RecorderState(draft = draft, recording = true)
        } catch (_: Exception) {
            device?.release()
            runCatching { files.file(draft.fileName).delete() }
            runCatching { clearPending() }
            mutableState.value = RecorderState(error = "Could not start recording. Check microphone access and available storage, then try again.")
        }
    }

    fun elapsedMs(): Long {
        val current = state.value
        return (current.draft?.durationMs ?: 0) +
            if (current.recording && !current.paused) SystemClock.elapsedRealtime() - segmentStarted else 0
    }

    fun togglePause() {
        val current = state.value
        val draft = current.draft ?: return
        if (!current.recording) return
        try {
            if (current.paused) {
                recorder?.resume()
                segmentStarted = SystemClock.elapsedRealtime()
                mutableState.value = current.copy(paused = false)
            } else {
                val elapsed = elapsedMs()
                recorder?.pause()
                val updated = draft.copy(durationMs = elapsed)
                persist(updated)
                mutableState.value = current.copy(draft = updated, paused = true)
            }
        } catch (_: Exception) { stop("Recording was interrupted. Review the captured audio before saving.") }
    }

    fun stop(message: String? = null) {
        val current = state.value
        val device = recorder ?: return
        recorder = null
        val draft = current.draft ?: return
        try {
            device.stop()
            device.release()
            val finished = draft.copy(durationMs = audioDuration(draft))
            val persistenceError = runCatching { persist(finished) }.exceptionOrNull()?.let {
                "Audio was captured, but its draft details could not be stored. Save it now or free some storage."
            }
            mutableState.value = current.copy(draft = finished, recording = false, paused = false,
                message = message, error = persistenceError)
        } catch (_: Exception) {
            runCatching { device.release() }
            mutableState.value = current.copy(draft = draft.copy(durationMs = 0), recording = false, paused = false,
                error = "No playable audio was captured. The recording may have been too short. Discard it and try again.")
        }
    }

    fun updateTitle(title: String) {
        val current = state.value
        if (current.saving) return
        val draft = current.draft?.copy(title = title) ?: return
        mutableState.value = current.copy(draft = draft)
        runCatching { persist(draft) }.onFailure {
            mutableState.value = state.value.copy(error = "Could not preserve the title. Try saving again.")
        }
    }

    fun save(db: AppDatabase, onSaved: () -> Unit) {
        val current = state.value
        val draft = current.draft ?: return
        if (current.recording || current.saving || draft.durationMs <= 0) return
        mutableState.value = current.copy(saving = true, error = null)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { audioDuration(draft) }
                // A retry after process death uses the same ID and cannot duplicate the entry.
                db.urgeDao().insertRecording(draft.copy(title = draft.title.trim()))
                clearPending()
                mutableState.value = RecorderState()
                onSaved()
            } catch (_: Exception) {
                mutableState.value = state.value.copy(saving = false,
                    error = "Could not save the recording. Your audio is kept here; please retry.")
            }
        }
    }

    fun discard(db: AppDatabase, onDiscarded: () -> Unit) {
        if (state.value.recording || state.value.saving) return
        val draft = state.value.draft
        mutableState.value = state.value.copy(saving = true)
        scope.launch {
            try {
                // Never delete audio already committed by a previous save attempt.
                if (draft != null && db.urgeDao().getRecording(draft.id) == null) {
                    withContext(Dispatchers.IO) {
                        val file = files.file(draft.fileName)
                        check(!file.exists() || file.delete())
                    }
                }
                clearPending()
                mutableState.value = RecorderState()
                onDiscarded()
            } catch (_: Exception) {
                mutableState.value = state.value.copy(saving = false, error = "Could not discard the recording. Please retry.")
            }
        }
    }

    internal fun release() { stop(); scope.cancel() }

    companion object {
        // get() retains only the application context, never an Activity.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: RecordingController? = null
        fun get(context: Context): RecordingController = instance ?: synchronized(this) {
            instance ?: RecordingController(context.applicationContext).also { instance = it }
        }
    }
}
