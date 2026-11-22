package com.example.urgeprocessor.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.example.urgeprocessor.audio.*
import com.example.urgeprocessor.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun rememberRecordingPlayer(): RecordingPlayer {
    val context = LocalContext.current
    val player = remember(context) { RecordingPlayer(context) }
    DisposableEffect(player, context) {
        val lifecycle = (context as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer); player.release() }
    }
    return player
}

@Composable
fun RecordingPlayback(entry: RecordingEntry, player: RecordingPlayer) {
    val state by player.state.collectAsState()
    val active = state.id == entry.id
    val position = if (active) state.positionMs else 0
    val duration = if (active && state.durationMs > 0) state.durationMs else entry.durationMs
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { player.toggle(entry) }, enabled = !(active && state.loading)) {
            if (active && state.loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            else Icon(if (active && state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (active && state.playing) "Pause recording" else "Play recording")
        }
        Slider(value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
            onValueChange = { player.seek(it.toLong()) },
            valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
            enabled = active && !state.loading && state.error == null,
            modifier = Modifier.weight(1f))
        Text("${audioTime(position)} / ${audioTime(duration)}", style = MaterialTheme.typography.labelSmall)
    }
    val speedLabel = "${state.speed.toString().removeSuffix(".0")}×"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = player::cycleSpeed,
            modifier = Modifier.semantics { contentDescription = "Playback speed $speedLabel. Tap to change speed" }) {
            Text(speedLabel)
        }
    }
    if (active) state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

@Composable
fun RecordingCard(entry: RecordingEntry, player: RecordingPlayer, db: AppDatabase, modifier: Modifier = Modifier) {
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val captured = Instant.ofEpochMilli(entry.timestamp).atZone(ZoneId.of(entry.zoneId))
    val time = captured.format(DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a z", Locale.getDefault()))
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(entry.title.ifBlank { "Voice recording" }, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete recording") }
            }
            Text(time, style = MaterialTheme.typography.labelMedium)
            if (captured.toLocalDate().toString() != entry.journalDate) {
                Text("Journal day: ${LocalDate.parse(entry.journalDate)}", style = MaterialTheme.typography.labelSmall)
            }
            RecordingPlayback(entry, player)
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { if (!deleting) confirmDelete = false },
        title = { Text("Delete recording?") },
        text = { Column { Text("This permanently deletes the saved audio."); error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
        confirmButton = {
            TextButton(enabled = !deleting, onClick = {
                deleting = true
                scope.launch {
                    try {
                        if (player.state.value.id == entry.id) player.stop()
                        // Removing the row disposes this card and cancels its scope. Finish file
                        // cleanup even if that recomposition happens before deletion returns.
                        withContext(Dispatchers.IO + NonCancellable) {
                            db.urgeDao().deleteRecording(entry)
                            RecordingFiles(context).file(entry.fileName).delete()
                        }
                        confirmDelete = false
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "Could not delete the recording. Please retry." }
                    finally { deleting = false }
                }
            }) { Text(if (deleting) "Deleting…" else "Delete") }
        },
        dismissButton = { TextButton(enabled = !deleting, onClick = { confirmDelete = false }) { Text("Cancel") } }
    )
}

@Composable
fun RecordingDialog(db: AppDatabase, controller: RecordingController, onDismiss: () -> Unit) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    val player = rememberRecordingPlayer()
    var elapsed by remember { mutableLongStateOf(0) }
    var permissionError by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.recording, state.paused, state.draft?.id) {
        while (true) { elapsed = controller.elapsedMs(); delay(200) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { permissionError = null; controller.start() }
        else permissionError = "Microphone access is needed to record. You can allow it in the app’s Android settings."
    }
    fun close() {
        if (state.saving) return
        if (state.recording) controller.stop()
        player.stop()
        onDismiss()
    }
    Dialog(onDismissRequest = ::close) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Voice recording", style = MaterialTheme.typography.headlineSmall)
                if (state.draft == null) {
                    Text("Capture a thought or a moment. Your recording will appear alongside journal entries in the calendar.")
                    Button(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            permissionError = null
                            controller.start()
                        } else permission.launch(Manifest.permission.RECORD_AUDIO)
                    }, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Text("Start recording")
                    }
                } else {
                    val draft = state.draft!!
                    Text(if (state.recording) (if (state.paused) "Paused" else "Recording…") else "Ready to save",
                        color = MaterialTheme.colorScheme.primary)
                    if (state.recording) {
                        Text(audioTime(elapsed), style = MaterialTheme.typography.displaySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { controller.togglePause() }) { Text(if (state.paused) "Resume" else "Pause") }
                            Button(onClick = { controller.stop() }) { Text("Stop") }
                        }
                    } else if (draft.durationMs > 0) RecordingPlayback(draft, player)
                    OutlinedTextField(value = draft.title, onValueChange = controller::updateTitle,
                        label = { Text("Title (optional)") }, singleLine = true, enabled = !state.saving,
                        modifier = Modifier.fillMaxWidth())
                    Text("Journal day: ${draft.journalDate}", style = MaterialTheme.typography.bodySmall)
                    if (!state.recording) {
                        Button(onClick = {
                            player.stop()
                            controller.save(db) {
                                Toast.makeText(context, "Recording saved to calendar", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                        }, enabled = !state.saving && draft.durationMs > 0, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.saving) "Saving…" else "Save recording")
                        }
                        TextButton(onClick = { confirmDiscard = true }, enabled = !state.saving) { Text("Discard recording") }
                    }
                }
                Text("Leaving the app or locking the phone stops recording and keeps it for review. Journal days change at 4 a.m.",
                    style = MaterialTheme.typography.bodySmall)
                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                (permissionError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = ::close, enabled = !state.saving, modifier = Modifier.align(Alignment.End)) {
                    Text(if (state.draft != null) "Keep for later" else "Close")
                }
            }
        }
    }
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { if (!state.saving) confirmDiscard = false },
        title = { Text("Discard recording?") }, text = { Text("This permanently deletes the unfinished audio.") },
        confirmButton = { TextButton(enabled = !state.saving, onClick = {
            player.stop()
            controller.discard(db) { confirmDiscard = false; onDismiss() }
        }) { Text("Discard") } },
        dismissButton = { TextButton(enabled = !state.saving, onClick = { confirmDiscard = false }) { Text("Cancel") } }
    )
}
