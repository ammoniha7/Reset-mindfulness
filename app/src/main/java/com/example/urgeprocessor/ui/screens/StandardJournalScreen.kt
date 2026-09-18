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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.urgeprocessor.data.AppDatabase
import com.example.urgeprocessor.data.StandardJournalEntry
import com.example.urgeprocessor.ui.components.ExportSettingsDialog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun StandardJournalScreen(
    db: AppDatabase,
    onNavigateToCalendar: () -> Unit,
    onNavigateToImport: () -> Unit
) {
    val entries by db.urgeDao().getAllStandardJournals().collectAsState(initial = emptyList())
    
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("export_prefs", Context.MODE_PRIVATE) }
    val journalPrefs = remember { context.getSharedPreferences("journal_prefs", Context.MODE_PRIVATE) }
    var text by rememberSaveable { mutableStateOf(journalPrefs.getString("journal_draft", "") ?: "") }
    val scope = rememberCoroutineScope()
    var showExportDialog by remember { mutableStateOf(false) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
        onResult = { uri: Uri? ->
            uri?.let {
                scope.launch {
                    val exportText = prefs.getString("pending_export_text", "") ?: ""
                    try {
                        context.contentResolver.openOutputStream(it)?.use { stream ->
                            stream.write(exportText.toByteArray())
                        }
                        val lastDate = prefs.getLong("pending_export_timestamp", 0L)
                        if (lastDate != 0L) {
                            prefs.edit().putLong("last_export_timestamp", lastDate).apply()
                        }
                        Toast.makeText(context, "Export successful", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Error saving file", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
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
            value = text,
            onValueChange = { 
                text = it
                journalPrefs.edit().putString("journal_draft", it).apply()
            },
            modifier = Modifier.fillMaxWidth().height(250.dp),
            placeholder = { Text("Write your thoughts here...") },
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
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
                    if (text.isNotBlank()) {
                        scope.launch {
                            val normalizedTimestamp = Calendar.getInstance().apply {
                                if (get(Calendar.HOUR_OF_DAY) < 4) {
                                    add(Calendar.DAY_OF_YEAR, -1)
                                }
                                set(Calendar.HOUR_OF_DAY, 0)
                                set(Calendar.MINUTE, 0)
                                set(Calendar.SECOND, 0)
                                set(Calendar.MILLISECOND, 0)
                            }.timeInMillis
                            db.urgeDao().insertStandardJournal(StandardJournalEntry(timestamp = normalizedTimestamp, content = text))
                            text = ""
                            journalPrefs.edit().remove("journal_draft").apply()
                        }
                    }
                }
            ) { Text("Save Entry") }
        }
    }

    if (showExportDialog) {
        ExportSettingsDialog(
            db = db,
            prefs = prefs,
            onDismiss = { showExportDialog = false },
            onExportStart = { exportText, timestamp ->
                prefs.edit()
                    .putString("pending_export_text", exportText)
                    .putLong("pending_export_timestamp", timestamp)
                    .apply()
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestamp))
                createDocumentLauncher.launch("Journal_Export_$dateStr.txt")
                showExportDialog = false
            }
        )
    }
}
