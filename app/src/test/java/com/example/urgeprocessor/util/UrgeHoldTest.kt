package com.example.urgeprocessor.util

import org.junit.Assert.*
import org.junit.Test

class UrgeHoldTest {
    @Test fun defaultAndSettingsBounds() {
        assertEquals(90, DEFAULT_URGE_HOLD_SECONDS)
        assertEquals(1, validUrgeHoldSeconds("1"))
        assertEquals(500, validUrgeHoldSeconds("500"))
        listOf("", "0", "501", "-1", "1.5", "999999999999").forEach {
            assertNull(validUrgeHoldSeconds(it))
        }
    }

    @Test fun requiresFullContinuousDurationAndAwardsOnlyOnce() {
        val hold = UrgeHold(90)
        hold.start(100)
        assertFalse(hold.advance(90_099))
        assertTrue(hold.advance(90_100))
        assertFalse(hold.advance(180_100))
        hold.cancel()
        assertFalse(hold.advance(270_100))
        hold.start(300_000)
        assertFalse(hold.advance(389_999))
        assertTrue(hold.advance(390_000))
    }

    @Test fun interruptionDiscardsProgress() {
        val hold = UrgeHold(1)
        hold.start(0)
        assertFalse(hold.advance(900))
        hold.cancel()
        assertEquals(0L, hold.elapsedMs)
        assertFalse(hold.advance(10_000))
        hold.start(10_000)
        assertFalse(hold.advance(10_100))
        assertTrue(hold.advance(11_000))
    }

    @Test fun durationDefensivelyStaysWithinBounds() {
        assertEquals(1_000L, UrgeHold(0).durationMs)
        assertEquals(500_000L, UrgeHold(501).durationMs)
    }
}
