package com.example.urgeprocessor.ui.components

import com.example.urgeprocessor.util.nextDayStart
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.urgeprocessor.audio.RecordingPlayer
import com.example.urgeprocessor.data.RecordingEntry
import com.example.urgeprocessor.util.calendarDate
import com.example.urgeprocessor.data.AppDatabase
import com.example.urgeprocessor.data.StandardJournalEntry
import com.example.urgeprocessor.data.UrgeEntry
import com.example.urgeprocessor.util.getHighlightedText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.compose.ui.text.font.FontWeight as ComposeFontWeight

private enum class DraftExitTarget {
    EDITOR,
    DIALOG
}

@Composable
fun DayEntriesDialog(
    db: AppDatabase,
    date: Date,
    journalEntries: List<StandardJournalEntry>,
    urgeEntries: List<UrgeEntry>,
    journalColor: Color,
    urgeColor: Color,
    searchQuery: String = "",
    onDismiss: () -> Unit,
    recordings: List<RecordingEntry> = emptyList(),
    recordingPlayer: RecordingPlayer? = null
) {
    val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault())
    val startTime = Calendar.getInstance().apply {
        time = date
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val endTime = nextDayStart(startTime)

    val dayJournals = journalEntries.filter { it.timestamp in startTime until endTime }
    val dayRecordings = recordings.filter { it.journalDate == calendarDate(startTime).toString() }.sortedBy { it.timestamp }
    val dayUrges = urgeEntries.filter { it.timestamp in startTime until endTime }

    val context = LocalContext.current
    val journalPrefs = remember(context) {
        context.getSharedPreferences("journal_prefs", Context.MODE_PRIVATE)
    }
    val draftKey = "calendar_draft_$startTime"
    val savedDraft = remember(draftKey) {
        journalPrefs.getString(draftKey, "").orEmpty()
    }

    var showAddJournal by remember(draftKey) { mutableStateOf(savedDraft.isNotBlank()) }
    var newJournalText by remember(draftKey) { mutableStateOf(savedDraft) }
    var pendingDraftExit by remember { mutableStateOf<DraftExitTarget?>(null) }
    var journalToEdit by remember { mutableStateOf<StandardJournalEntry?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(draftKey, newJournalText) {
        delay(400)
        if (newJournalText.isBlank()) {
            journalPrefs.edit().remove(draftKey).apply()
        } else {
            journalPrefs.edit().putString(draftKey, newJournalText).apply()
        }
    }

    fun completeDraftExit(keepDraft: Boolean) {
        val target = pendingDraftExit ?: return
        if (keepDraft) {
            journalPrefs.edit().putString(draftKey, newJournalText).apply()
        } else {
            journalPrefs.edit().remove(draftKey).apply()
            newJournalText = ""
        }
        pendingDraftExit = null

        when (target) {
            DraftExitTarget.EDITOR -> showAddJournal = false
            DraftExitTarget.DIALOG -> onDismiss()
        }
    }

    fun requestDraftExit(target: DraftExitTarget) {
        if (showAddJournal && newJournalText.isNotBlank()) {
            pendingDraftExit = target
        } else {
            if (target == DraftExitTarget.EDITOR) {
                showAddJournal = false
            } else {
                onDismiss()
            }
        }
    }

    AlertDialog(
        onDismissRequest = { requestDraftExit(DraftExitTarget.DIALOG) },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(dateFormat.format(date), modifier = Modifier.weight(1f))
                IconButton(onClick = { showAddJournal = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add journal entry")
                }
            }
        },
        text = {
            Column {
                if (showAddJournal) {
                    OutlinedTextField(
                        value = newJournalText,
                        onValueChange = { newJournalText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .padding(bottom = 16.dp),
                        placeholder = { Text("Add a thought for this day...") },
                        shape = RoundedCornerShape(8.dp),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { requestDraftExit(DraftExitTarget.EDITOR) }
                        ) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = {
                                scope.launch {
                                    db.urgeDao().insertStandardJournal(
                                        StandardJournalEntry(
                                            timestamp = startTime,
                                            content = newJournalText
                                        )
                                    )
                                    journalPrefs.edit().remove(draftKey).apply()
                                    newJournalText = ""
                                    showAddJournal = false
                                }
                            },
                            enabled = newJournalText.isNotBlank()
                        ) {
                            Text("Save")
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    if (dayJournals.isNotEmpty()) {
                        item {
                            Text(
                                "Journal Entries",
                                fontWeight = FontWeight.Bold,
                                color = journalColor,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        items(dayJournals, key = { "journal-${it.id}" }) { entry ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = journalColor.copy(alpha = 0.1f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "Journal Entry",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = journalColor
                                        )
                                        IconButton(
                                            onClick = { journalToEdit = entry },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Edit,
                                                contentDescription = "Edit journal entry",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    SelectionContainer {
                                        Text(
                                            text = if (searchQuery.isNotBlank()) {
                                                getHighlightedText(entry.content, searchQuery)
                                            } else {
                                                buildAnnotatedString { append(entry.content) }
                                            },
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (recordingPlayer != null) {
                        items(dayRecordings, key = { "recording-${it.id}" }) { entry ->
                            RecordingCard(entry, recordingPlayer, db, Modifier.padding(bottom = 8.dp))
                        }
                    }

                    if (dayUrges.isNotEmpty()) {
                        item {
                            Text(
                                "Urge Flow Entries",
                                fontWeight = FontWeight.Bold,
                                color = urgeColor,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        items(dayUrges, key = { "urge-${it.id}" }) { entry ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = urgeColor.copy(alpha = 0.1f)
                                )
                            ) {
                                SelectionContainer {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Text(
                                            text = if (searchQuery.isNotBlank()) {
                                                getHighlightedText(
                                                    "${entry.category}: ${entry.specificEmotion}",
                                                    searchQuery
                                                )
                                            } else {
                                                buildAnnotatedString {
                                                    append("${entry.category}: ${entry.specificEmotion}")
                                                }
                                            },
                                            fontWeight = FontWeight.Bold
                                        )

                                        val labels = listOf(
                                            "Loved: " to entry.feltLoved,
                                            "Stress: " to entry.stressReason,
                                            "Excited: " to entry.excitementWeek
                                        )
                                        labels.forEach { (label, value) ->
                                            if (value.isNotBlank()) {
                                                Text(
                                                    text = buildAnnotatedString {
                                                        withStyle(
                                                            SpanStyle(
                                                                fontWeight = ComposeFontWeight.Bold
                                                            )
                                                        ) {
                                                            append(label)
                                                        }
                                                        if (searchQuery.isNotBlank()) {
                                                            append(getHighlightedText(value, searchQuery))
                                                        } else {
                                                            append(value)
                                                        }
                                                    },
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (dayJournals.isEmpty() && dayUrges.isEmpty() && dayRecordings.isEmpty()) {
                        item { Text("No entries for this day.") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { requestDraftExit(DraftExitTarget.DIALOG) }) {
                Text("Close")
            }
        }
    )

    if (pendingDraftExit != null) {
        AlertDialog(
            onDismissRequest = { pendingDraftExit = null },
            title = { Text("Unsaved Journal Entry") },
            text = { Text("Would you like to keep this entry as a draft or discard it?") },
            confirmButton = {
                TextButton(onClick = { completeDraftExit(keepDraft = true) }) {
                    Text("Save Draft")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingDraftExit = null }) {
                        Text("Keep Editing")
                    }
                    TextButton(onClick = { completeDraftExit(keepDraft = false) }) {
                        Text("Discard", color = Color.Red)
                    }
                }
            }
        )
    }

    journalToEdit?.let { entry ->
        EditJournalDialog(
            entry = entry,
            onDismiss = { journalToEdit = null },
            onSave = { newContent ->
                scope.launch {
                    db.urgeDao().updateStandardJournal(entry.copy(content = newContent))
                    journalToEdit = null
                }
            },
            onDelete = {
                scope.launch {
                    db.urgeDao().deleteStandardJournal(entry)
                    journalToEdit = null
                }
            }
        )
    }
}
