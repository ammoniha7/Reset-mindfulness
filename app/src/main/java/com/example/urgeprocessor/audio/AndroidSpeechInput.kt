package com.example.urgeprocessor.audio

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/** Uses an explicitly on-device service when requested; never silently falls back to a server. */
class AndroidSpeechInput(context: Context) : SpeechInput {
    private val context = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    override val availability: SpeechAvailability
        get() = SpeechAvailability(
            onDevice = Build.VERSION.SDK_INT >= 31 && runCatching {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            }.getOrDefault(false),
            system = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
        )

    override fun start(onDevice: Boolean, listener: SpeechListener) {
        close()
        val device = if (onDevice) {
            if (Build.VERSION.SDK_INT >= 31) {
                check(availability.onDevice)
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else error("On-device speech recognition requires Android 12 or later")
        } else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = device
        device.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.ready()
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit // Never retain audio buffers.
            override fun onEndOfSpeech() = listener.endOfSpeech()
            override fun onError(error: Int) = listener.error(speechErrorMessage(error))
            override fun onResults(results: Bundle?) = listener.result(results.words())
            override fun onPartialResults(partialResults: Bundle?) = listener.partial(partialResults.words())
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        device.startListening(dictationIntent())
    }

    override fun stop() { recognizer?.stopListening() }
    override fun cancel() { recognizer?.cancel() }
    override fun close() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun Bundle?.words(): String =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
}

internal fun dictationIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    if (Build.VERSION.SDK_INT >= 33) {
        putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
    }
}

internal fun speechErrorMessage(code: Int): String = when (code) {
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone access is needed to dictate. Allow it in Android settings and try again."
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The speech service could not connect. Check your connection, or use on-device recognition if available."
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No clear speech was recognized. Try speaking again."
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The microphone or speech service is busy. Wait a moment and try again."
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
        "Your speech language is unavailable. Install its offline speech pack in Android settings, or choose system speech."
    SpeechRecognizer.ERROR_AUDIO -> "The microphone could not capture speech. Check microphone access and try again."
    else -> "Speech recognition stopped unexpectedly. Your text is kept; please try again."
}
