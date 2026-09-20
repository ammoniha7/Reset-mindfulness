package com.example.urgeprocessor.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.urgeprocessor.data.AppDatabase
import com.example.urgeprocessor.data.StandardJournalEntry
import com.example.urgeprocessor.data.UrgeEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.example.urgeprocessor.data.exportArchive
import com.example.urgeprocessor.util.*
import java.time.LocalDate
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSettingsDialog(
    db: AppDatabase,
    prefs: android.content.SharedPreferences,
    onDismiss: () -> Unit,
    onExportStart: (String, Long, Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    val lastExport = prefs.getLong("last_export_timestamp", 0L)
    val lastExportStr = if (lastExport == 0L) "Never" else SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(lastExport))
    
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var allDates by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = pickerTimestamp(selectedDate))
    var showDatePicker by remember { mutableStateOf(false) }

    fun startExport(backup: Boolean) {
        busy = true
        error = null
        scope.launch {
            try {
                val text = if (backup) exportArchive(db) else {
                    val start = dayStart(selectedDate)
                    val journals = db.urgeDao().getAllStandardJournals().first().filter { allDates || it.timestamp >= start }
                    val urges = db.urgeDao().getAllEntries().first().filter { allDates || it.timestamp >= start }
                    generateExportText(journals, urges)
                }
                onExportStart(text, System.currentTimeMillis(), backup)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Could not prepare export. Please try again."
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Export Entries") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Last export date: $lastExportStr", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("Text exports are for reading. Entry backups restore all journals, urge records, and quotes; settings and drafts are not included.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allDates, onCheckedChange = { allDates = it }, enabled = !busy)
                    Text("All dates for text export")
                }
                Text("Text export start date (inclusive):", style = MaterialTheme.typography.bodyMedium)
                
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !allDates && !busy
                ) {
                    Icon(Icons.Default.CalendarMonth, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(selectedDate.toString())
                }
            }
        },
        confirmButton = {
            Column {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = { startExport(true) }, enabled = !busy) { Text("Save entry backup") }
                TextButton(onClick = { startExport(false) }, enabled = !busy) { Text("Export text") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        }
    )

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { selectedDate = pickerDate(it) }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

fun generateExportText(journals: List<StandardJournalEntry>, urges: List<UrgeEntry>): String {
    val dateFormat = SimpleDateFormat("MM-dd-yyyy", Locale.US)
    
    data class ExportItem(val timestamp: Long, val text: String)
    
    val items = mutableListOf<ExportItem>()
    
    journals.forEach { j ->
        val dateHeader = dateFormat.format(Date(j.timestamp))
        items.add(ExportItem(j.timestamp, "$dateHeader:\n[Journal]\n${j.content}\n"))
    }
    
    urges.forEach { u ->
        val dateHeader = dateFormat.format(Date(u.timestamp))
        val sb = StringBuilder()
        sb.append("$dateHeader:\n")
        sb.append("[Urge Flow]\n")
        sb.append("Category: ${u.category} (${u.specificEmotion})\n")
        if (u.feltLoved.isNotBlank()) sb.append("Loved: ${u.feltLoved}\n")
        if (u.stressReason.isNotBlank()) sb.append("Stress: ${u.stressReason}\n")
        if (u.excitementWeek.isNotBlank()) sb.append("Excited: ${u.excitementWeek}\n")
        items.add(ExportItem(u.timestamp, sb.toString()))
    }
    
    return items.sortedBy { it.timestamp }.joinToString("\n") { it.text }
}
