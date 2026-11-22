package com.example.urgeprocessor.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class DictationControllerTest {
    private class Input(override val availability: SpeechAvailability = SpeechAvailability(true, true)) : SpeechInput {
        val listeners = mutableListOf<SpeechListener>()
        val modes = mutableListOf<Boolean>()
        var cancelled = 0
        var stopped = 0
        var closed = 0
        override fun start(onDevice: Boolean, listener: SpeechListener) { modes += onDevice; listeners += listener }
        override fun stop() { stopped++ }
        override fun cancel() { cancelled++ }
        override fun close() { closed++ }
        val listener get() = listeners.last()
    }
    private class Fixture(availability: SpeechAvailability = SpeechAvailability(true, true), timeout: Long = 5_000) {
        val input = Input(availability)
        var text = ""
        val writes = mutableListOf<Pair<String, Boolean>>()
        val controller = DictationController(input, { text }, { words, durable ->
            text = words; writes += words to durable
        }, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), timeout)
    }

    @Test fun interimHypothesesAreReplacedAndFinalResultIsAcceptedOnce() {
        val f = Fixture()
        try {
            f.text = "Earlier thoughts."
            f.controller.start(true)
            f.input.listener.ready()
            f.input.listener.partial("today")
            f.input.listener.partial("today we walked")
            assertEquals("Earlier thoughts. today we walked", f.text)
            f.input.listener.result("Today we walked by the river.")
            f.input.listener.result("Today we walked by the river.")
            assertEquals("Earlier thoughts. Today we walked by the river.", f.text)
            assertEquals(1, f.writes.count { it.second })
            assertFalse(f.controller.state.value.busy)
        } finally { f.controller.close() }
    }
    @Test fun speakingMoreAppendsAnotherPhraseAndBusyStartIsIgnored() {
        val f = Fixture()
        try {
            f.controller.start(true)
            f.controller.start(true)
            assertEquals(1, f.input.listeners.size)
            f.input.listener.result("First sentence.")
            f.controller.start(true)
            f.input.listener.result("Second sentence.")
            assertEquals("First sentence. Second sentence.", f.text)
        } finally { f.controller.close() }
    }
    @Test fun stopWaitsForTheFinalWordsBeforeUnlockingReview() {
        val f = Fixture()
        try {
            f.controller.start(true)
            f.input.listener.partial("I had")
            f.controller.stop()
            assertTrue(f.controller.state.value.finishing)
            assertEquals(1, f.input.stopped)
            f.input.listener.result("I had a good day.")
            assertEquals("I had a good day.", f.text)
            assertFalse(f.controller.state.value.busy)
        } finally { f.controller.close() }
    }
    @Test fun interruptionKeepsPartialWordsAndIgnoresOldCallbacksAfterRestart() {
        val f = Fixture()
        try {
            f.controller.start(true)
            val old = f.input.listener
            old.partial("A thought")
            f.controller.interrupt()
            assertEquals("A thought", f.text)
            assertTrue(f.writes.last().second)
            f.controller.start(true)
            old.result("Old result must not replace anything")
            old.error("Old error")
            f.input.listener.result("and another.")
            assertEquals("A thought and another.", f.text)
            assertNull(f.controller.state.value.error)
        } finally { f.controller.close() }
    }
    @Test fun recognitionFailureKeepsCapturedWordsForEditing() {
        val f = Fixture()
        try {
            f.controller.start(true)
            f.input.listener.partial("Do not lose this")
            f.input.listener.error("Microphone interrupted.")
            assertEquals("Do not lose this", f.text)
            assertFalse(f.controller.state.value.busy)
            assertTrue(f.controller.state.value.error!!.contains("partial"))
        } finally { f.controller.close() }
    }
    @Test fun unavailableOfflineRecognitionNeverSilentlyUsesSystemSpeech() {
        val f = Fixture(SpeechAvailability(false, true))
        try {
            f.controller.start(true)
            assertTrue(f.input.modes.isEmpty())
            assertNotNull(f.controller.state.value.error)
            f.controller.start(false)
            assertEquals(listOf(false), f.input.modes)
        } finally { f.controller.close() }
    }
    @Test fun emptyFinalResultKeepsThePartialHypothesis() {
        val f = Fixture()
        try {
            f.controller.start(true)
            f.input.listener.partial("Only these words")
            f.input.listener.result("")
            assertEquals("Only these words", f.text)
            assertTrue(f.controller.state.value.message!!.contains("partial"))
        } finally { f.controller.close() }
    }
    @Test fun serviceTimeoutUnlocksTheEditorWithoutLosingText() = runBlocking {
        val f = Fixture(timeout = 10)
        try {
            f.controller.start(true)
            f.input.listener.partial("Words before timeout")
            f.input.listener.endOfSpeech()
            withTimeout(2_000) { f.controller.state.first { !it.busy } }
            assertEquals("Words before timeout", f.text)
            assertNotNull(f.controller.state.value.error)
            assertTrue(f.input.cancelled > 0)
        } finally { f.controller.close() }
    }
    @Test fun closingReleasesSpeechServiceAndIgnoresLateResults() {
        val f = Fixture()
        f.controller.start(true)
        val listener = f.input.listener
        listener.partial("Keep")
        f.controller.close()
        listener.result("Late")
        f.controller.start(true)
        assertEquals("Keep", f.text)
        assertEquals(1, f.input.closed)
        assertEquals(1, f.input.listeners.size)
    }
}
