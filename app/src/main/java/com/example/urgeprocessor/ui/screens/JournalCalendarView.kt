package com.example.urgeprocessor.ui.screens

import com.example.urgeprocessor.util.nextDayStart
import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.urgeprocessor.data.RecordingEntry
import com.example.urgeprocessor.ui.components.RecordingCard
import com.example.urgeprocessor.ui.components.rememberRecordingPlayer
import com.example.urgeprocessor.util.calendarDate
import com.example.urgeprocessor.util.dayStart
import com.example.urgeprocessor.data.AppDatabase
import com.example.urgeprocessor.data.StandardJournalEntry
import com.example.urgeprocessor.data.UrgeEntry
import java.time.LocalDate
import com.example.urgeprocessor.ui.components.OnThisDayDialog
import com.example.urgeprocessor.ui.components.DayEntriesDialog
import com.example.urgeprocessor.ui.components.EditJournalDialog
import com.example.urgeprocessor.ui.components.MonthYearPickerDialog
import com.example.urgeprocessor.util.getHighlightedText
import com.example.urgeprocessor.util.performSearch
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.ui.text.font.FontWeight as ComposeFontWeight

@Composable
fun JournalCalendarView(
    db: AppDatabase,
    journalEntries: List<StandardJournalEntry>,
    urgeEntries: List<UrgeEntry>,
    journalColor: Color,
    urgeColor: Color,
    bothColor: Color,
    onBack: () -> Unit,
    resetTrigger: Long = 0L,
    recordingEntries: List<RecordingEntry> = emptyList()
) {
    val recordingPlayer = rememberRecordingPlayer()
    var calendar by remember { mutableStateOf(Calendar.getInstance().apply { 
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }) }

    LaunchedEffect(resetTrigger) {
        if (resetTrigger > 0L) {
            calendar = Calendar.getInstance().apply { 
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        }
    }
    val monthFormat = SimpleDateFormat("MMMM", Locale.getDefault())
    val yearFormat = SimpleDateFormat("yyyy", Locale.getDefault())
    var swipeOffsetX by remember { mutableFloatStateOf(0f) }
    
    var pastDay by remember { mutableStateOf<LocalDate?>(null) }
    var showMonthPicker by remember { mutableStateOf(false) }
    var showYearPicker by remember { mutableStateOf(false) }
    var selectedDate by remember { mutableStateOf<Date?>(null) }
    var draftRefreshTrigger by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val journalPrefs = remember(context) {
        context.getSharedPreferences("journal_prefs", Context.MODE_PRIVATE)
    }
    
    // Search State
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var activeSearchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            focusRequester.requestFocus()
        }
    }

    // Prepare chronological month list
    val startOfMonth = calendar.timeInMillis
    val endOfMonth = (calendar.clone() as Calendar).apply { 
        add(Calendar.MONTH, 1)
    }.timeInMillis

    val monthJournalItems = journalEntries.filter { it.timestamp in startOfMonth until endOfMonth }
    val monthUrgeItems = urgeEntries.filter { it.timestamp in startOfMonth until endOfMonth }
    
    val monthKey = java.time.YearMonth.of(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1).toString()
    val monthRecordingItems = recordingEntries.filter { it.journalDate.startsWith("$monthKey-") }

    // Unified item type for the combined list
    data class CombinedItem(
        val timestamp: Long,
        val id: Int,
        val journal: StandardJournalEntry? = null,
        val urge: UrgeEntry? = null,
        val recording: RecordingEntry? = null,
        val orderTime: Long = timestamp
    )

    val combinedList = (monthJournalItems.map { CombinedItem(it.timestamp, it.id, journal = it) } +
            monthUrgeItems.map { CombinedItem(it.timestamp, it.id, urge = it) } +
            monthRecordingItems.map {
                CombinedItem(dayStart(LocalDate.parse(it.journalDate)), 0, recording = it, orderTime = it.timestamp)
            })
        .sortedWith(compareBy({ calendarDate(it.timestamp) }, { it.orderTime }, { it.id })) // Oldest first, entry order for same day

    val scope = rememberCoroutineScope()
    var journalToEdit by remember { mutableStateOf<StandardJournalEntry?>(null) }
    var urgeToDelete by remember { mutableStateOf<UrgeEntry?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isSearchActive) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f).heightIn(min = 40.dp).focusRequester(focusRequester),
                        placeholder = { Text("Search entries...") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            activeSearchQuery = searchQuery
                            keyboardController?.hide()
                        }),
                        trailingIcon = {
                            IconButton(onClick = { 
                                searchQuery = ""
                                activeSearchQuery = ""
                                isSearchActive = false 
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Close Search")
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    // Month Box
                    Surface(
                        onClick = { showMonthPicker = true },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        color = Color.Transparent
                    ) {
                        Text(
                            text = monthFormat.format(calendar.time),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    // Year Box
                    Surface(
                        onClick = { showYearPicker = true },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        color = Color.Transparent
                    ) {
                        Text(
                            text = yearFormat.format(calendar.time),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Row {
                if (!isSearchActive) {
                    IconButton(onClick = { isSearchActive = true }) {
                        Icon(Icons.Default.Search, contentDescription = "Open Search")
                    }
                }
                IconButton(onClick = {
                    val newCal = calendar.clone() as Calendar
                    newCal.add(Calendar.MONTH, -1)
                    calendar = newCal
                }) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Month")
                }
                IconButton(onClick = {
                    val newCal = calendar.clone() as Calendar
                    newCal.add(Calendar.MONTH, 1)
                    calendar = newCal
                }) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Next Month")
                }
            }
        }

        if (showMonthPicker) {
            MonthYearPickerDialog(
                title = "Select Month",
                items = (0..11).map { m -> 
                    val cal = Calendar.getInstance().apply { set(Calendar.MONTH, m) }
                    monthFormat.format(cal.time)
                },
                initialIndex = calendar.get(Calendar.MONTH),
                onItemSelected = { index ->
                    val newCal = calendar.clone() as Calendar
                    newCal.set(Calendar.MONTH, index)
                    calendar = newCal
                    showMonthPicker = false
                },
                onDismiss = { showMonthPicker = false }
            )
        }

        if (showYearPicker) {
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            val years = ((currentYear - 50)..(currentYear + 50)).toList()
            MonthYearPickerDialog(
                title = "Select Year",
                items = years.map { it.toString() },
                initialIndex = years.indexOf(calendar.get(Calendar.YEAR)),
                onItemSelected = { index ->
                    val newCal = calendar.clone() as Calendar
                    newCal.set(Calendar.YEAR, years[index])
                    calendar = newCal
                    showYearPicker = false
                },
                onDismiss = { showYearPicker = false }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Wrap Calendar area in Box for swipe gestures
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(calendar) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (swipeOffsetX > 150) {
                                // Swipe Right -> Previous Month
                                val newCal = calendar.clone() as Calendar
                                newCal.add(Calendar.MONTH, -1)
                                calendar = newCal
                            } else if (swipeOffsetX < -150) {
                                // Swipe Left -> Next Month
                                val newCal = calendar.clone() as Calendar
                                newCal.add(Calendar.MONTH, 1)
                                calendar = newCal
                            }
                            swipeOffsetX = 0f
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            swipeOffsetX += dragAmount
                        }
                    )
                }
        ) {
            Column {
                // Days of week header
                Row(modifier = Modifier.fillMaxWidth()) {
                    val days = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
                    days.forEach { day ->
                        Text(
                            text = day,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.Gray
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Calendar Grid
                val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
                val firstDayOfMonth = (calendar.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
                val startOffset = firstDayOfMonth.get(Calendar.DAY_OF_WEEK) - 1

                val totalSlots = 42
                // Since we are inside a vertical scroll, we can't use LazyVerticalGrid directly with height expansion easily.
                // We'll use manual Rows or a fixed height. A fixed column approach is better for vertical scroll compatibility.
                val rows = (totalSlots + 6) / 7
                for (row in 0 until rows) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        for (col in 0 until 7) {
                            val index = row * 7 + col
                            val dayNum = index - startOffset + 1
                            Box(modifier = Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                                if (dayNum in 1..daysInMonth) {
                                    val currentDayCal = (calendar.clone() as Calendar).apply {
                                        set(Calendar.DAY_OF_MONTH, dayNum)
                                        set(Calendar.HOUR_OF_DAY, 0)
                                        set(Calendar.MINUTE, 0)
                                        set(Calendar.SECOND, 0)
                                        set(Calendar.MILLISECOND, 0)
                                    }
                                    val startTime = currentDayCal.timeInMillis
                                    val endTime = nextDayStart(startTime)

                                    val hasRecording = recordingEntries.any { it.journalDate == calendarDate(startTime).toString() }
                                    val hasJournal = hasRecording || journalEntries.any { it.timestamp in startTime until endTime }
                                    val hasUrge = urgeEntries.any { it.timestamp in startTime until endTime }
                                    val hasDraft = remember(startTime, draftRefreshTrigger) {
                                        journalPrefs.getString("calendar_draft_$startTime", "")
                                            .orEmpty()
                                            .isNotBlank()
                                    }

                                    val isToday = startTime == Calendar.getInstance().apply {
                                        set(Calendar.HOUR_OF_DAY, 0)
                                        set(Calendar.MINUTE, 0)
                                        set(Calendar.SECOND, 0)
                                        set(Calendar.MILLISECOND, 0)
                                    }.timeInMillis

                                    val bgColor = when {
                                        hasJournal && hasUrge -> bothColor
                                        hasJournal -> journalColor
                                        hasUrge -> urgeColor
                                        else -> Color.Transparent
                                    }

                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(4.dp)
                                            .clip(CircleShape)
                                            .then(
                                                if (isToday && bgColor == Color.Transparent) {
                                                    Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                                } else Modifier
                                            )
                                            .background(bgColor)
                                            .clickable { selectedDate = currentDayCal.time },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = dayNum.toString(),
                                            fontWeight = if (bgColor != Color.Transparent || isToday || hasDraft) FontWeight.Bold else FontWeight.Normal,
                                            color = if (bgColor != Color.Transparent) Color.White else if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            textDecoration = if (isToday) androidx.compose.ui.text.style.TextDecoration.Underline else null
                                        )

                                        if (hasRecording) {
                                            Icon(Icons.Default.Mic, contentDescription = "Voice recording",
                                                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(10.dp),
                                                tint = Color.White)
                                        }
                                        if (hasDraft) {
                                            Icon(
                                                imageVector = Icons.Default.Description,
                                                contentDescription = "Saved draft",
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(2.dp)
                                                    .size(12.dp),
                                                tint = MaterialTheme.colorScheme.tertiary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Results Section (Search vs Month Records)
        if (isSearchActive && activeSearchQuery.isNotBlank()) {
            Text(
                text = "Search Results",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            val searchResults = remember(activeSearchQuery, journalEntries, urgeEntries) {
                performSearch(activeSearchQuery, journalEntries, urgeEntries)
            }

            val matchingRecordings = recordingEntries.filter {
                it.title.ifBlank { "Voice recording" }.contains(activeSearchQuery, ignoreCase = true)
            }
            matchingRecordings.forEach { entry ->
                key("recording-search-${entry.id}") {
                    RecordingCard(entry, recordingPlayer, db, Modifier.padding(bottom = 12.dp))
                }
            }
            if (searchResults.isEmpty() && matchingRecordings.isEmpty()) {
                Text("No matches found for \"$searchQuery\"", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            } else {
                searchResults.forEach { result ->
                    SearchResultCard(result, journalColor, urgeColor) {
                        selectedDate = Date(result.timestamp)
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "${monthFormat.format(calendar.time)} Records",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { pastDay = LocalDate.now() }) { Text("On This Day") }
            }

            if (combinedList.isEmpty()) {
                Text("No entries for this month.", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            } else {
                combinedList.forEach { item ->
                    if (item.recording != null) {
                        key("recording-${item.recording.id}") {
                            RecordingCard(item.recording, recordingPlayer, db, Modifier.padding(bottom = 12.dp))
                        }
                    } else if (item.urge != null) {
                        val entry = item.urge
                        val date = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(entry.timestamp))
                        val col = try { Color(android.graphics.Color.parseColor(entry.emotionColor)) } catch(e: Exception) { Color.Gray }
                        
                        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
                                SelectionContainer(modifier = Modifier.weight(1f)) {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(col))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("${entry.category}: ${entry.specificEmotion}", fontWeight = ComposeFontWeight.Bold)
                                        }
                                        Text(date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)

                                        Spacer(modifier = Modifier.height(8.dp))
                                        val labels = listOf("Loved: " to entry.feltLoved, "Stress: " to entry.stressReason, "Excited: " to entry.excitementWeek)
                                        labels.forEach { (label, value) ->
                                            if (value.isNotBlank()) {
                                                Text(
                                                    buildAnnotatedString {
                                                        withStyle(style = SpanStyle(fontWeight = ComposeFontWeight.Bold)) { append(label) }
                                                        append(value)
                                                    },
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                        }
                                    }
                                }
                                IconButton(onClick = { urgeToDelete = entry }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red.copy(alpha = 0.5f))
                                }
                            }
                        }
                    } else if (item.journal != null) {
                        val entry = item.journal
                        val date = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(entry.timestamp))
                        
                        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Journal Entry", fontWeight = FontWeight.Bold, color = journalColor)
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
                                    Column {
                                        Text(date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(entry.content, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
    }

    LaunchedEffect(selectedDate, pastDay) { recordingPlayer.pause() }

    pastDay?.let { date ->
        OnThisDayDialog(date, journalEntries, urgeEntries, journalColor, urgeColor,
            recordings = recordingEntries, player = recordingPlayer, db = db,
            onDismiss = { pastDay = null })
    }

    if (selectedDate != null) {
        DayEntriesDialog(
            db = db,
            date = selectedDate!!,
            recordings = recordingEntries,
            recordingPlayer = recordingPlayer,
            journalEntries = journalEntries,
            urgeEntries = urgeEntries,
            journalColor = journalColor,
            urgeColor = urgeColor,
            searchQuery = if (isSearchActive) activeSearchQuery else "",
            onDismiss = {
                selectedDate = null
                draftRefreshTrigger++
            }
        )
    }

    if (urgeToDelete != null) {
        AlertDialog(
            onDismissRequest = { urgeToDelete = null },
            title = { Text("Delete Urge Record?") },
            text = { Text("This will permanently remove this record.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        urgeToDelete?.let { db.urgeDao().deleteEntry(it) }
                        urgeToDelete = null
                    }
                }) { Text("Delete", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { urgeToDelete = null }) { Text("Cancel") }
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

@Composable
fun SearchResultCard(result: com.example.urgeprocessor.util.SearchResult, journalColor: Color, urgeColor: Color, onClick: () -> Unit) {
    val date = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(result.timestamp))
    val typeColor = if (result.type == "Journal") journalColor else urgeColor

    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        SelectionContainer {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(result.type, style = MaterialTheme.typography.labelMedium, color = typeColor, fontWeight = FontWeight.Bold)
                    Text(date, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = result.snippet,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
