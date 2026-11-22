package com.example.urgeprocessor.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.urgeprocessor.audio.RecordingPlayer
import com.example.urgeprocessor.data.AppDatabase
import com.example.urgeprocessor.data.RecordingEntry
import com.example.urgeprocessor.data.StandardJournalEntry
import com.example.urgeprocessor.data.UrgeEntry
import com.example.urgeprocessor.util.onThisDayEntries
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun OnThisDayDialog(
    date: LocalDate,
    journals: List<StandardJournalEntry>,
    urges: List<UrgeEntry>,
    journalColor: Color,
    urgeColor: Color,
    onDismiss: () -> Unit,
    recordings: List<RecordingEntry> = emptyList(),
    player: RecordingPlayer? = null,
    db: AppDatabase? = null
) {
    val zone = ZoneId.systemDefault()
    val years = remember(date, journals, urges, zone, recordings) { onThisDayEntries(journals, urges, date, zone, recordings) }
    val dateLabel = date.format(DateTimeFormatter.ofPattern("MMMM d", Locale.getDefault()))
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("On This Day", style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Text("$dateLabel · Previous years", style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 16.dp))
                if (years.isEmpty()) {
                    Text("No entries from $dateLabel in previous years yet.")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                        years.forEach { year ->
                            item(key = "year-${year.year}") {
                                Text(year.year.toString(), style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold)
                            }
                            items(year.journals, key = { "journal-${it.id}" }) { entry ->
                                Card(Modifier.fillMaxWidth()) {
                                    SelectionContainer {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("Journal Entry", color = journalColor, fontWeight = FontWeight.Bold)
                                            Text(entry.content)
                                        }
                                    }
                                }
                            }
                            if (player != null && db != null) {
                                items(year.recordings, key = { "recording-${it.id}" }) { entry ->
                                    RecordingCard(entry, player, db)
                                }
                            }
                            items(year.urges, key = { "urge-${it.id}" }) { entry ->
                                Card(Modifier.fillMaxWidth()) {
                                    SelectionContainer {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("Urge Flow", color = urgeColor, fontWeight = FontWeight.Bold)
                                            Text("${entry.category}: ${entry.specificEmotion}", fontWeight = FontWeight.Bold)
                                            listOf("Loved" to entry.feltLoved, "Stress" to entry.stressReason,
                                                "Excited" to entry.excitementWeek).forEach { (label, answer) ->
                                                if (answer.isNotBlank()) Text("$label: $answer")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
