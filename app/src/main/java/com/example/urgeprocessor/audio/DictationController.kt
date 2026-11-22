package com.example.urgeprocessor.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The system recognizer streams audio; neither this interface nor its owner stores audio. */
interface SpeechInput {
    val availability: SpeechAvailability
    fun start(onDevice: Boolean, listener: SpeechListener)
    fun stop()
    fun cancel()
    fun close()
}

data class SpeechAvailability(val onDevice: Boolean, val system: Boolean)

interface SpeechListener {
    fun ready()
    fun partial(text: String)
    fun endOfSpeech()
    fun result(text: String)
    fun error(message: String)
}

data class DictationState(
    val listening: Boolean = false,
    val finishing: Boolean = false,
    val ready: Boolean = false,
    val partial: Boolean = false,
    val message: String? = null,
    val error: String? = null
) {
    val busy: Boolean get() = listening || finishing
}

/** Replaces interim hypotheses, accepts a final result once, and rejects callbacks after closing. */
class DictationController(
    private val input: SpeechInput,
    private val readText: () -> String,
    private val writeText: (String, Boolean) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val finalResultTimeoutMs: Long = 5_000
) {
    val availability: SpeechAvailability get() = input.availability
    private val mutableState = MutableStateFlow(DictationState())
    val state = mutableState.asStateFlow()
    private var requestNumber = 0L
    private var activeRequest: Long? = null
    private var timeout: Job? = null
    private var closed = false

    fun start(onDevice: Boolean) {
        if (closed || state.value.busy) return
        if ((onDevice && !availability.onDevice) || (!onDevice && !availability.system)) {
            mutableState.value = DictationState(error = if (onDevice)
                "On-device speech recognition is unavailable. You can choose system speech below."
                else "No system speech recognition service is available. Enable one in Android settings, or type your entry.")
            return
        }
        val baseText = readText()
        val request = ++requestNumber
        activeRequest = request
        mutableState.value = DictationState(listening = true)
        val listener = object : SpeechListener {
            override fun ready() {
                if (activeRequest == request) mutableState.value = state.value.copy(ready = true)
            }
            override fun partial(text: String) {
                if (activeRequest != request || text.isBlank()) return
                // Each partial is a replacement for the current phrase, not another sentence.
                writeText(joinWords(baseText, text), false)
                mutableState.value = state.value.copy(partial = true)
            }
            override fun endOfSpeech() {
                if (activeRequest != request) return
                mutableState.value = state.value.copy(listening = false, finishing = true)
                waitForFinalResult(request)
            }
            override fun result(text: String) {
                if (activeRequest != request) return
                val hadPartial = state.value.partial
                activeRequest = null
                timeout?.cancel()
                if (text.isNotBlank()) writeText(joinWords(baseText, text), true)
                else if (hadPartial) writeText(readText(), true)
                mutableState.value = DictationState(message = when {
                    text.isNotBlank() -> "Review the text, or tap Speak more to continue."
                    hadPartial -> "Only partial words were returned. Review them before adding to your journal."
                    else -> "No words were heard. Tap Start speaking to try again."
                })
            }
            override fun error(message: String) {
                if (activeRequest != request) return
                finishWithError(message)
            }
        }
        try { input.start(onDevice, listener) }
        catch (_: SecurityException) { listener.error("Microphone access is needed to dictate. Allow it in Android settings and try again.") }
        catch (_: Exception) { listener.error("Could not start speech recognition. Your text is kept; please try again.") }
    }

    fun stop() {
        val request = activeRequest ?: return
        if (state.value.finishing) return
        mutableState.value = state.value.copy(listening = false, finishing = true)
        try {
            input.stop()
            if (activeRequest == request) waitForFinalResult(request)
        } catch (_: Exception) { finishWithError("Speech recognition stopped unexpectedly. Review the words captured so far.") }
    }

    private fun waitForFinalResult(request: Long) {
        // A service that stops responding must not leave the editor permanently locked.
        if (timeout?.isActive == true) return
        timeout = scope.launch {
            delay(finalResultTimeoutMs)
            if (activeRequest == request) finishWithError("Speech recognition took too long. Review the words captured so far, then try again.")
        }
    }

    private fun finishWithError(message: String) {
        val hadPartial = state.value.partial
        activeRequest = null
        timeout?.cancel()
        runCatching { input.cancel() }
        if (hadPartial) writeText(readText(), true)
        mutableState.value = DictationState(error = message +
            if (hadPartial) " The last phrase is partial; check it before saving." else "")
    }

    fun interrupt(message: String = "Dictation stopped. Your captured words are kept for review.") {
        if (activeRequest == null) return
        activeRequest = null // cancel() may deliver a synchronous error or a late result.
        timeout?.cancel()
        runCatching { input.cancel() }
        writeText(readText(), true)
        mutableState.value = DictationState(message = message)
    }

    fun close() {
        if (closed) return
        interrupt()
        closed = true
        runCatching { input.close() }
        scope.cancel()
    }
}

private fun joinWords(existing: String, phrase: String): String =
    if (existing.isBlank()) phrase.trim() else existing.trimEnd() + " " + phrase.trim()
