package com.example.urgeprocessor.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.example.urgeprocessor.audio.AndroidSpeechInput
import com.example.urgeprocessor.audio.DictationController
import com.example.urgeprocessor.data.JournalDraftStore
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun DictationDialog(
    drafts: JournalDraftStore,
    onDismiss: () -> Unit,
    // Injection allows exercising speech callbacks without relying on a vendor's model or servers.
    suppliedController: DictationController? = null
) {
    val context = LocalContext.current
    val controller = suppliedController ?: remember(drafts, context) {
        DictationController(AndroidSpeechInput(context), { drafts.dictation.value.text },
            { text, durable -> drafts.updateDictationText(text, durable); Unit })
    }
    val state by controller.state.collectAsState()
    val spoken by drafts.dictation.collectAsState()
    val journal by drafts.state.collectAsState()
    val availability = remember(controller) { controller.availability }
    val scope = rememberCoroutineScope()
    var useSystemSpeech by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var permissionError by remember { mutableStateOf<String?>(null) }
    val attached = remember { AtomicBoolean(true) }
    val lifecycle = (context as? LifecycleOwner)?.lifecycle

    DisposableEffect(controller, lifecycle) {
        attached.set(true)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller.interrupt()
        }
        lifecycle?.addObserver(observer)
        onDispose {
            attached.set(false)
            lifecycle?.removeObserver(observer)
            controller.close()
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (attached.get()) {
            if (granted && lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != false) {
                permissionError = null
                controller.start(onDevice = !useSystemSpeech)
            } else if (!granted) {
                permissionError = "Microphone access is needed to dictate. You can allow it in the app’s Android settings."
            }
        }
    }
    fun close() {
        if (adding) return
        controller.interrupt()
        onDismiss()
    }
    val modeAvailable = if (useSystemSpeech) availability.system else availability.onDevice
    Dialog(onDismissRequest = ::close) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Speak to write", style = MaterialTheme.typography.headlineSmall)
                Text("Speak, review the words, and add them to your journal. Reset saves text only.")
                Text(if (availability.onDevice && !useSystemSpeech) "On-device speech recognition"
                    else if (!availability.onDevice && !useSystemSpeech) "On-device speech recognition isn’t available on this phone."
                    else "System speech recognition", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                if (availability.system) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = useSystemSpeech,
                        enabled = !state.busy && !adding, role = Role.Checkbox,
                        onValueChange = { useSystemSpeech = it; permissionError = null }),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = useSystemSpeech, onCheckedChange = null,
                            enabled = !state.busy && !adding)
                        Spacer(Modifier.width(8.dp))
                        Text("Use system speech", modifier = Modifier.weight(1f))
                    }
                    if (useSystemSpeech || !availability.onDevice) {
                        Text("Your phone’s speech service may send audio online. Reset does not create or keep an audio recording.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                } else if (!availability.onDevice) {
                    Text("Enable a speech recognition service in Android settings to dictate. You can still type your entry.",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (state.busy) {
                    Text(when {
                        state.finishing -> "Finishing speech…"
                        state.ready -> "Listening…"
                        else -> "Starting microphone…"
                    }, color = MaterialTheme.colorScheme.primary)
                    Button(onClick = { controller.stop() }, enabled = !state.finishing,
                        modifier = Modifier.fillMaxWidth()) {
                        if (state.finishing) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (state.finishing) "Finishing…" else "Stop speaking")
                    }
                } else {
                    OutlinedButton(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            permissionError = null
                            controller.start(onDevice = !useSystemSpeech)
                        } else permission.launch(Manifest.permission.RECORD_AUDIO)
                    }, enabled = modeAvailable && !adding && !journal.saving, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Mic, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (spoken.text.isBlank()) "Start speaking" else "Speak more")
                    }
                }
                OutlinedTextField(value = spoken.text,
                    onValueChange = { drafts.updateDictationText(it) },
                    label = { Text("Spoken entry") },
                    placeholder = { Text("Your words appear here…") },
                    minLines = 3, maxLines = 7,
                    readOnly = state.busy || adding || journal.saving,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth())
                Text("Speech may stop after a pause. Tap Speak more to continue. Adding text appends it to your current journal draft.",
                    style = MaterialTheme.typography.bodySmall)
                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                (permissionError ?: spoken.error ?: state.error)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = {
                    adding = true
                    scope.launch {
                        try { if (drafts.useDictationText()) onDismiss() }
                        finally { adding = false }
                    }
                }, enabled = spoken.text.isNotBlank() && !state.busy && !adding && !journal.saving,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (adding) "Adding…" else "Add to journal")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (spoken.text.isNotBlank()) {
                        TextButton(onClick = { confirmDiscard = true }, enabled = !state.busy && !adding) { Text("Discard text") }
                    } else Spacer(Modifier.weight(1f))
                    TextButton(onClick = ::close, enabled = !adding) {
                        Text(if (spoken.text.isBlank()) "Close" else "Keep for later")
                    }
                }
            }
        }
    }
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("Discard spoken text?") },
        text = { Text("Your existing journal draft will be kept.") },
        confirmButton = { TextButton(onClick = {
            if (drafts.updateDictationText("", durable = true)) {
                confirmDiscard = false
                onDismiss()
            }
        }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Cancel") } }
    )
}
