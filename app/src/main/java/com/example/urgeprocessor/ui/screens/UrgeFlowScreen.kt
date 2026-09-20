package com.example.urgeprocessor.ui.screens

import com.example.urgeprocessor.util.entryTimestamp
import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.urgeprocessor.data.AppDatabase
import com.example.urgeprocessor.data.Quote
import com.example.urgeprocessor.data.UrgeEntry
import com.example.urgeprocessor.ui.components.ColorPickerDialog
import com.example.urgeprocessor.ui.components.DailyQuoteSection
import com.example.urgeprocessor.ui.components.QuestionTemplate
import com.example.urgeprocessor.util.calculateStreak
import com.example.urgeprocessor.util.getCustomColor
import com.example.urgeprocessor.util.saveCustomColor
import com.example.urgeprocessor.util.toHexString
import kotlinx.coroutines.launch
import java.util.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UrgeFlowScreen(db: AppDatabase, onNavigateToSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(FlowStep.CATEGORY) }
    var entry by remember { mutableStateOf(UrgeEntry()) }

    // Collecting entries here so we can calculate the streak
    val entries by db.urgeDao().getAllEntries().collectAsState(initial = emptyList())

    var color1 by remember { mutableStateOf(getCustomColor(context, "color1", Color(0xFFEF5350))) }
    var color2 by remember { mutableStateOf(getCustomColor(context, "color2", Color(0xFF42A5F5))) }
    var color3 by remember { mutableStateOf(getCustomColor(context, "color3", Color(0xFF66BB6A))) }
    var color4 by remember { mutableStateOf(getCustomColor(context, "color4", Color(0xFFFFEE58))) }
    var showPicker by remember { mutableStateOf(false) }
    var activeSlot by remember { mutableStateOf("") }

    val categories = listOf("Accepting", "Angry", "Sad", "Fear", "Stressed", "Joy")
    val specificEmotions = mapOf(
        "Accepting" to listOf("Calm", "Centered", "Content", "Forgiving", "Patient"),
        "Angry" to listOf("Agitated", "Frustrated", "Pissed", "Resentful", "Outraged"),
        "Sad" to listOf("Lonely", "Grief", "Disappointed", "Hopeless", "Empty"),
        "Fear" to listOf("Anxious", "Terrified", "Insecure", "Vulnerable", "Panicked"),
        "Stressed" to listOf("Overwhelmed", "Burnt Out", "Pressured", "Distracted"),
        "Joy" to listOf("Bliss", "Vibrant", "Grateful", "Inspired", "Playful")
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(40.dp))
            when (step) {
            FlowStep.CATEGORY -> {
                Text("How are you feeling?", style = MaterialTheme.typography.headlineMedium); Spacer(modifier = Modifier.height(30.dp))
                LazyVerticalGrid(columns = GridCells.Fixed(2), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.heightIn(max = 300.dp)) {
                    items(categories) { cat -> Button(onClick = { entry.category = cat; step = FlowStep.SPECIFIC }, modifier = Modifier.height(60.dp)) { Text(cat) } }
                }

                DailyQuoteSection(db)

                // Moved Streak Counter
                Spacer(modifier = Modifier.height(24.dp))
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.LocalFireDepartment, contentDescription = "Streak", tint = Color.Red, modifier = Modifier.size(28.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Current Streak: ${calculateStreak(entries)} Days", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(modifier = Modifier.height(16.dp))

                        // New Processed Successfully Counter
                        var processedCount by remember {
                            val prefs = context.getSharedPreferences("counter_prefs", Context.MODE_PRIVATE)
                            mutableIntStateOf(prefs.getInt("processed_count", 0))
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text("Processed Successfully", style = MaterialTheme.typography.bodyLarge)
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(onClick = {
                                    if (processedCount > 0) {
                                        processedCount--
                                        context.getSharedPreferences("counter_prefs", Context.MODE_PRIVATE).edit().putInt("processed_count", processedCount).apply()
                                    }
                                }) {
                                    Icon(Icons.Default.Remove, contentDescription = "Decrease", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = {
                                    processedCount++
                                    context.getSharedPreferences("counter_prefs", Context.MODE_PRIVATE).edit().putInt("processed_count", processedCount).apply()
                                }) {
                                    Icon(Icons.Default.Add, contentDescription = "Increase", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = processedCount.toString(),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = "Reset",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        processedCount = 0
                                        context.getSharedPreferences("counter_prefs", Context.MODE_PRIVATE).edit().putInt("processed_count", 0).apply()
                                    }
                                )
                            }
                        }
                    }
                }
            }
            FlowStep.SPECIFIC -> {
                Text("Be more specific:", style = MaterialTheme.typography.headlineSmall)
                val emotionsToShow = specificEmotions[entry.category] ?: listOf("General", "Unsure", "Mixed")
                emotionsToShow.forEach { emotion ->
                    OutlinedButton(onClick = { entry.specificEmotion = emotion; step = FlowStep.COLOR }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text(emotion) }
                }
            }
            FlowStep.COLOR -> {
                Text("Pick a color", style = MaterialTheme.typography.headlineSmall)
                Text("(Long press to edit)", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 32.dp)) {
                    val slots = listOf("color1" to color1, "color2" to color2, "color3" to color3, "color4" to color4)
                    slots.forEach { (key, col) ->
                        Box(modifier = Modifier.size(70.dp).clip(CircleShape).background(col).combinedClickable(
                            onClick = { entry.emotionColor = col.toHexString(); step = FlowStep.LOVED },
                            onLongClick = { activeSlot = key; showPicker = true }
                        ))
                    }
                }
                if (showPicker) {
                    ColorPickerDialog(onColorSelected = { newCol ->
                        saveCustomColor(context, activeSlot, newCol)
                        when(activeSlot) { "color1"->color1=newCol; "color2"->color2=newCol; "color3"->color3=newCol; "color4"->color4=newCol }
                        showPicker = false
                    }, onDismiss = { showPicker = false })
                }
            }
            FlowStep.LOVED -> QuestionTemplate("Have you felt loved by someone today?", entry.feltLoved, { entry = entry.copy(feltLoved = it) }, { step = FlowStep.STRESS })
            FlowStep.STRESS -> QuestionTemplate("What's causing you stress right now?", entry.stressReason, { entry = entry.copy(stressReason = it) }, { step = FlowStep.EXCITEMENT })
            FlowStep.EXCITEMENT -> QuestionTemplate("What are you excited for this week?", entry.excitementWeek, { entry = entry.copy(excitementWeek = it) }, { 
                scope.launch { 
                    val normalizedEntry = entry.copy(timestamp = entryTimestamp())
                    db.urgeDao().insert(normalizedEntry); 
                    step = FlowStep.COMPLETE 
                } 
            })
            FlowStep.COMPLETE -> {
                Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(80.dp), tint = Color(0xFF4CAF50))
                Text("Entry Saved", style = MaterialTheme.typography.headlineSmall)
                Button(onClick = { entry = UrgeEntry(); step = FlowStep.CATEGORY }, modifier = Modifier.padding(top = 16.dp)) { Text("Restart") }
            }
        }
    }

    // Settings Gear Icon at Top Right - Placed AFTER Column in Box so it's on top
    IconButton(
        onClick = onNavigateToSettings,
        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
    ) {
        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.primary)
    }
}
}

enum class FlowStep { CATEGORY, SPECIFIC, COLOR, LOVED, STRESS, EXCITEMENT, COMPLETE }
