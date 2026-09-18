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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.urgeprocessor.data.AppDatabase
import com.example.urgeprocessor.data.StandardJournalEntry
import com.example.urgeprocessor.data.UrgeEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSettingsDialog(
    db: AppDatabase,
    prefs: android.content.SharedPreferences,
    onDismiss: () -> Unit,
    onExportStart: (String, Long) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lastExport = prefs.getLong("last_export_timestamp", 0L)
    val lastExportStr = if (lastExport == 0L) "Never" else SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(lastExport))
    
    var selectedDate by remember { 
        val cal = Calendar.getInstance()
        if (lastExport != 0L) cal.timeInMillis = lastExport
        mutableStateOf(cal.timeInMillis)
    }
    
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = selectedDate)
    var showDatePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Entries") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Last export date: $lastExportStr", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("Select start date for export (inclusive):", style = MaterialTheme.typography.bodyMedium)
                
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CalendarMonth, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(selectedDate)))
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                scope.launch {
                    val journalFlow = db.urgeDao().getAllStandardJournals()
                    val urgeFlow = db.urgeDao().getAllEntries()
                    
                    val journals = journalFlow.first().filter { it.timestamp >= selectedDate }
                    val urges = urgeFlow.first().filter { it.timestamp >= selectedDate }
                    
                    val exportText = generateExportText(journals, urges)
                    onExportStart(exportText, selectedDate)
                }
            }) {
                Text("Start Export")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { selectedDate = it }
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
