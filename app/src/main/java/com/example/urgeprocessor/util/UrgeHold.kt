package com.example.urgeprocessor.util

const val DEFAULT_URGE_HOLD_SECONDS = 90
const val URGE_HOLD_SECONDS_KEY = "urge_hold_seconds"

fun validUrgeHoldSeconds(text: String): Int? = text.toIntOrNull()?.takeIf { it in 1..500 }

/** Monotonic timestamps; a completed hold cannot award again until a new press. */
class UrgeHold(seconds: Int) {
    val durationMs = seconds.coerceIn(1, 500) * 1_000L
    private var startedAt: Long? = null
    private var awarded = false
    var elapsedMs = 0L
        private set

    fun start(now: Long) {
        startedAt = now
        awarded = false
        elapsedMs = 0
    }

    fun advance(now: Long): Boolean {
        val start = startedAt ?: return false
        elapsedMs = (now - start).coerceIn(0, durationMs)
        if (elapsedMs < durationMs || awarded) return false
        awarded = true
        return true
    }

    fun cancel() {
        startedAt = null
        elapsedMs = 0
    }
}
