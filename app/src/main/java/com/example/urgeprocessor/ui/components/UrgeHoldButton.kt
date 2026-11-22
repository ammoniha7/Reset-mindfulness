package com.example.urgeprocessor.ui.components

import android.os.SystemClock
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.example.urgeprocessor.util.UrgeHold
import com.example.urgeprocessor.util.triggerVibration

@Composable
fun UrgeHoldButton(seconds: Int, onComplete: () -> Unit) {
    val view = LocalView.current
    val lifecycle = view.findViewTreeLifecycleOwner()?.lifecycle
    val hold = remember(seconds) { UrgeHold(seconds) }
    val complete by rememberUpdatedState(onComplete)
    var holding by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0) }
    var finished by remember { mutableStateOf(false) }
    var resumed by remember { mutableStateOf(lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) }

    DisposableEffect(lifecycle, hold) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
            if (!resumed) {
                hold.cancel()
                holding = false
                elapsed = 0
                finished = false
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer); hold.cancel() }
    }
    DisposableEffect(holding, view) {
        val previous = view.keepScreenOn
        if (holding) view.keepScreenOn = true
        onDispose { if (holding) view.keepScreenOn = previous }
    }

    val remaining = ((hold.durationMs - elapsed + 999) / 1_000).coerceAtLeast(0)
    val label = when {
        finished -> "Urge processed! Lift your finger."
        holding -> "$remaining seconds remaining"
        else -> "Hold for $seconds seconds"
    }
    Box(
        modifier = Modifier.size(180.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (finished) 0.25f else 0.12f))
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "Hold to process an urge"
                stateDescription = label
            }
            .pointerInput(hold, resumed) {
                if (!resumed) return@pointerInput
                fun insideCircle(position: Offset): Boolean {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    return (position - center).getDistance() <= minOf(size.width, size.height) / 2f
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!insideCircle(down.position)) return@awaitEachGesture
                    down.consume()
                    hold.start(down.uptimeMillis)
                    holding = true
                    finished = false
                    elapsed = 0
                    try {
                        while (true) {
                            // Wake even when the finger is stationary; never transfer the
                            // hold to another finger or resume it after leaving the bounds.
                            val event = withTimeoutOrNull(40L) { awaitPointerEvent() }
                            if (lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true || !view.hasWindowFocus()) break
                            if (event != null) {
                                val finger = event.changes.firstOrNull { it.id == down.id }
                                val invalid = finger == null || finger.isConsumed ||
                                    !insideCircle(finger.position) ||
                                    event.changes.count { it.pressed } > 1
                                event.changes.forEach { it.consume() }
                                if (invalid) break
                                if (!finger.pressed) {
                                    // Honor a release exactly at the deadline, even between ticks.
                                    if (hold.advance(finger.uptimeMillis)) {
                                        complete()
                                        triggerVibration(view.context, "Done")
                                    }
                                    break
                                }
                            }
                            if (hold.advance(SystemClock.uptimeMillis())) {
                                finished = true
                                complete()
                                triggerVibration(view.context, "Done")
                            }
                            elapsed = hold.elapsedMs
                        }
                    } finally {
                        hold.cancel()
                        holding = false
                        elapsed = 0
                        finished = false
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(progress = { elapsed.toFloat() / hold.durationMs },
            modifier = Modifier.size(154.dp), strokeWidth = 6.dp,
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (finished) "✓ Processed" else "Hold here", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(if (finished) "Lift your finger" else "${remaining}s", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
