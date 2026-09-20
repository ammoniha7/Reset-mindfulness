package com.example.urgeprocessor.ui.screens

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.urgeprocessor.data.AppDatabase
import com.example.urgeprocessor.data.DraftSaveResult
import com.example.urgeprocessor.data.JournalDraftStore
import com.example.urgeprocessor.ui.components.ExportSettingsDialog
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun StandardJournalScreen(
    db: AppDatabase,
    onNavigateToCalendar: () -> Unit,
    onNavigateToImport: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val prefs = remember { context.getSharedPreferences("export_prefs", Context.MODE_PRIVATE) }
    val drafts = remember { JournalDraftStore.get(context) }
    val draftState by drafts.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showExportDialog by remember { mutableStateOf(false) }

    fun finishExport(uri: Uri?) {
        if (uri == null) {
            prefs.edit().remove("pending_export_text").remove("pending_export_timestamp").apply()
            return
        }
        scope.launch {
            try {
                val exportText = prefs.getString("pending_export_text", null)
                    ?: error("No pending export")
                withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openOutputStream(uri, "wt")
                        ?: error("Could not open destination")
                    stream.use { it.write(exportText.toByteArray(Charsets.UTF_8)) }
                }
                prefs.edit().putLong("last_export_timestamp", System.currentTimeMillis()).apply()
                Toast.makeText(context, "Export successful", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed. Please try again.", Toast.LENGTH_LONG).show()
            } finally {
                prefs.edit().remove("pending_export_text").remove("pending_export_timestamp").apply()
            }
        }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"), ::finishExport
    )
    val createBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"), ::finishExport
    )

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Daily Journal", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onNavigateToCalendar) {
                    Icon(Icons.Default.CalendarMonth, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Calendar")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = draftState.text,
            onValueChange = {
                if (!drafts.updateText(it)) scope.launch { drafts.saveIfDue() }
            },
            enabled = !draftState.saving,
            modifier = Modifier.fillMaxWidth().height(250.dp),
            placeholder = { Text("Write your thoughts here...") },
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
        )

        Text(
            text = draftState.error ?: draftState.message ?: "Unsent entries save automatically at 4 a.m. for the previous day.",
            style = MaterialTheme.typography.bodySmall,
            color = if (draftState.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row {
                TextButton(onClick = onNavigateToImport) {
                    Icon(Icons.Default.FileUpload, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Import")
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = { showExportDialog = true }) {
                    Icon(Icons.Default.FileDownload, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Export")
                }
            }
            
            Button(
                onClick = {
                    scope.launch {
                        if (drafts.saveManually() == DraftSaveResult.SAVED) {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }
                    }
                },
                enabled = draftState.text.isNotBlank() && !draftState.saving
            ) { Text(if (draftState.saving) "Saving…" else "Save Entry") }
        }
    }

    if (showExportDialog) {
        ExportSettingsDialog(
            db = db,
            prefs = prefs,
            onDismiss = { showExportDialog = false },
            onExportStart = { exportText, timestamp, backup ->
                prefs.edit()
                    .putString("pending_export_text", exportText)
                    .putLong("pending_export_timestamp", timestamp)
                    .apply()
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestamp))
                if (backup) createBackupLauncher.launch("Reset_Backup_$dateStr.json")
                else createDocumentLauncher.launch("Journal_Export_$dateStr.txt")
                showExportDialog = false
            }
        )
    }
}
