package com.example.urgeprocessor.ui

import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.example.urgeprocessor.ui.components.UrgeHoldButton
import com.example.urgeprocessor.ui.theme.ResetTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class UrgeHoldButtonTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun earlyReleaseExitCompletionAndBackgroundCancellation() {
        val awards = AtomicInteger()
        compose.setContent { ResetTheme { Column { UrgeHoldButton(1) { awards.incrementAndGet() } } } }
        val bounds = compose.onNodeWithContentDescription("Hold to process an urge").fetchSemanticsNode().boundsInWindow
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var downAt = 0L
        fun touch(action: Int, x: Float = bounds.center.x, y: Float = bounds.center.y) {
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downAt = now
            val event = MotionEvent.obtain(downAt, now, action, x, y, 0)
            try { automation.injectInputEvent(event, true) } finally { event.recycle() }
        }
        touch(MotionEvent.ACTION_DOWN)
        Thread.sleep(200)
        touch(MotionEvent.ACTION_UP)
        Thread.sleep(1_100)
        assertEquals(0, awards.get())

        touch(MotionEvent.ACTION_DOWN)
        Thread.sleep(200)
        touch(MotionEvent.ACTION_MOVE, bounds.center.x, bounds.bottom + 30)
        Thread.sleep(1_100)
        touch(MotionEvent.ACTION_UP, bounds.center.x, bounds.bottom + 30)
        assertEquals(0, awards.get())

        touch(MotionEvent.ACTION_DOWN)
        compose.waitUntil(3_000) { awards.get() == 1 }
        Thread.sleep(1_100)
        assertEquals(1, awards.get())
        touch(MotionEvent.ACTION_UP)
        touch(MotionEvent.ACTION_DOWN)
        compose.waitUntil(3_000) { awards.get() == 2 }
        touch(MotionEvent.ACTION_UP)

        touch(MotionEvent.ACTION_DOWN)
        Thread.sleep(200)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        Thread.sleep(1_100)
        assertEquals(2, awards.get())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        touch(MotionEvent.ACTION_UP)
        Thread.sleep(200)
        assertEquals(2, awards.get())
    }
}
