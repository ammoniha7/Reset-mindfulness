package com.example.urgeprocessor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.ui.text.font.FontWeight as ComposeFontWeight // Alias to avoid conflict
import androidx.compose.ui.text.withStyle
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.*
import com.example.urgeprocessor.ui.theme.UrgeProcessorTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// --- DATABASE & ENTITIES ---

@Entity(tableName = "urge_entries")
data class UrgeEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    var category: String = "",
    var specificEmotion: String = "",
    var emotionColor: String = "",
    var feltLoved: String = "",
    var stressReason: String = "",
    var excitementWeek: String = ""
)

@Entity(tableName = "quotes")
data class Quote(@PrimaryKey(autoGenerate = true) val id: Int = 0, val text: String)

// NEW: Entity for the standard freeform journal
@Entity(tableName = "standard_journal")
data class StandardJournalEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val content: String
)

@Dao
interface UrgeDao {
    @Insert suspend fun insert(entry: UrgeEntry): Long
    @Query("SELECT * FROM urge_entries ORDER BY timestamp DESC")
    fun getAllEntries(): kotlinx.coroutines.flow.Flow<List<UrgeEntry>>
    @Delete suspend fun deleteEntry(entry: UrgeEntry): Int

    @Insert suspend fun insertQuote(quote: Quote): Long
    @Query("SELECT * FROM quotes")
    fun getAllQuotes(): kotlinx.coroutines.flow.Flow<List<Quote>>
    @Delete suspend fun deleteQuote(quote: Quote): Int

    @Insert suspend fun insertStandardJournal(entry: StandardJournalEntry): Long
    @Query("SELECT * FROM standard_journal ORDER BY timestamp DESC")
    fun getAllStandardJournals(): kotlinx.coroutines.flow.Flow<List<StandardJournalEntry>>
    // NEW: Added ability to delete standard journals
    @Delete suspend fun deleteStandardJournal(entry: StandardJournalEntry): Int
}

// Bumped version to 5 and added the new entity
@Database(entities = [UrgeEntry::class, Quote::class, StandardJournalEntry::class], version = 5)
abstract class AppDatabase : RoomDatabase() {
    abstract fun urgeDao(): UrgeDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "urge_db")
                    .fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// --- UTILS ---

fun triggerVibration(context: Context, type: String) {
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    vibrator.cancel()
    val pattern = when (type) {
        "Inhale" -> longArrayOf(0, 60)
        "Hold" -> longArrayOf(0, 30, 60, 30)
        "Exhale" -> longArrayOf(0, 250)
        "Done" -> longArrayOf(0, 50, 40, 50, 40, 150)
        else -> longArrayOf(0, 30)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val attributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_ALARM)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1), attributes)
    } else {
        @Suppress("DEPRECATION") vibrator.vibrate(pattern, -1)
    }
}

fun saveCustomColor(context: Context, key: String, color: Color) {
    val prefs = context.getSharedPreferences("color_prefs", Context.MODE_PRIVATE)
    prefs.edit().putInt(key, color.toArgb()).apply()
}

fun getCustomColor(context: Context, key: String, default: Color): Color {
    val prefs = context.getSharedPreferences("color_prefs", Context.MODE_PRIVATE)
    val colorInt = prefs.getInt(key, default.toArgb())
    return Color(colorInt)
}

fun Color.toHexString(): String = String.format("#%08X", this.toArgb())

// --- MAIN ACTIVITY ---

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.getDatabase(this)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val prefs = remember { context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE) }
            var isDarkMode by rememberSaveable { mutableStateOf(prefs.getBoolean("dark_mode", false)) }
            var customThemeColor by remember {
                val colorInt = prefs.getInt("theme_color", -1)
                mutableStateOf(if (colorInt == -1) null else Color(colorInt))
            }
            var journalColor by remember { mutableStateOf(Color(prefs.getInt("calendar_journal_color", 0xFF42A5F5.toInt()))) }
            var urgeColor by remember { mutableStateOf(Color(prefs.getInt("calendar_urge_color", 0xFF66BB6A.toInt()))) }
            var bothColor by remember { mutableStateOf(Color(prefs.getInt("calendar_both_color", 0xFFAB47BC.toInt()))) }
            
            LaunchedEffect(isDarkMode) {
                enableEdgeToEdge(
                    statusBarStyle = if (isDarkMode) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    }
                )
            }
            
            UrgeProcessorTheme(darkTheme = isDarkMode, customColor = customThemeColor) {
                var currentDest by rememberSaveable { mutableStateOf(AppDestinations.FLOW) }
                NavigationSuiteScaffold(
                    navigationSuiteItems = {
                        AppDestinations.entries.filter { it.showInNavBar }.forEach {
                            item(icon = { Icon(it.icon, null) }, label = { Text(it.label) }, selected = it == currentDest, onClick = { currentDest = it })
                        }
                    }
                ) {
                    Scaffold { innerPadding ->
                        Box(modifier = Modifier.padding(innerPadding)) {
                            when (currentDest) {
                                AppDestinations.FLOW -> UrgeFlowScreen(db, onNavigateToSettings = { currentDest = AppDestinations.SETTINGS })
                                AppDestinations.BREATHE -> BreathingScreen()
                                AppDestinations.RECORDS -> RecordsScreen(db)
                                AppDestinations.JOURNAL -> StandardJournalScreen(db, journalColor, urgeColor, bothColor)
                                AppDestinations.SETTINGS -> SettingsScreen(
                                    isDarkMode = isDarkMode,
                                    onDarkModeChange = { 
                                        isDarkMode = it
                                        prefs.edit().putBoolean("dark_mode", it).apply()
                                    },
                                    customThemeColor = customThemeColor,
                                    onThemeColorChange = { color ->
                                        customThemeColor = color
                                        if (color != null) {
                                            prefs.edit().putInt("theme_color", color.toArgb()).apply()
                                        } else {
                                            prefs.edit().remove("theme_color").apply()
                                        }
                                    },
                                    journalColor = journalColor,
                                    onJournalColorChange = { 
                                        journalColor = it
                                        prefs.edit().putInt("calendar_journal_color", it.toArgb()).apply()
                                    },
                                    urgeColor = urgeColor,
                                    onUrgeColorChange = { 
                                        urgeColor = it
                                        prefs.edit().putInt("calendar_urge_color", it.toArgb()).apply()
                                    },
                                    bothColor = bothColor,
                                    onBothColorChange = { 
                                        bothColor = it
                                        prefs.edit().putInt("calendar_both_color", it.toArgb()).apply()
                                    },
                                    onResetCalendarColors = {
                                        journalColor = Color(0xFF42A5F5)
                                        urgeColor = Color(0xFF66BB6A)
                                        bothColor = Color(0xFFAB47BC)
                                        prefs.edit().remove("calendar_journal_color")
                                            .remove("calendar_urge_color")
                                            .remove("calendar_both_color").apply()
                                    },
                                    onBack = { currentDest = AppDestinations.FLOW }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// Updated Nav Bar Enums
enum class AppDestinations(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val showInNavBar: Boolean = true) {
    FLOW("Urge Flow", Icons.Default.Psychology),
    BREATHE("Breathe", Icons.Default.Air),
    RECORDS("Records", Icons.Default.LibraryBooks),
    JOURNAL("Journal", Icons.Default.EditNote),
    SETTINGS("Settings", Icons.Default.Settings, false)
}

enum class FlowStep { CATEGORY, SPECIFIC, COLOR, LOVED, STRESS, EXCITEMENT, COMPLETE }
enum class JournalView { LIST, CALENDAR }

// --- SCREENS ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BreathingScreen() {
    val context = LocalContext.current
    var isRunning by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf("Ready?") }
    var targetScale by remember { mutableFloatStateOf(0.6f) }
    var selectedCycles by remember { mutableIntStateOf(5) }
    var cyclesLeft by remember { mutableIntStateOf(5) }
    var isCyclesExpanded by remember { mutableStateOf(false) }
    var isTechniqueExpanded by remember { mutableStateOf(false) }
    var selectedTechnique by remember { mutableStateOf("4-7-8") }
    var hapticsEnabled by rememberSaveable { mutableStateOf(true) }

    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(
            durationMillis = when {
                phase == "Inhale" && selectedTechnique == "4-7-8" -> 4000
                phase == "Exhale" && selectedTechnique == "4-7-8" -> 8000
                phase == "Inhale" && selectedTechnique == "Box" -> 4000
                phase == "Exhale" && selectedTechnique == "Box" -> 4000
                else -> 500
            },
            easing = LinearEasing
        ),
        label = ""
    )
    val color by animateColorAsState(
        targetValue = when(phase) { "Inhale" -> Color(0xFF81C784); "Hold" -> Color(0xFF64B5F6); "Exhale" -> Color(0xFFFFB74D); else -> Color.LightGray },
        label = ""
    )

    LaunchedEffect(isRunning) {
        if (isRunning) {
            cyclesLeft = selectedCycles
            while (cyclesLeft > 0) {
                if (selectedTechnique == "4-7-8") {
                    phase = "Inhale"; targetScale = 1.0f
                    if (hapticsEnabled) triggerVibration(context, "Inhale"); delay(4000)
                    phase = "Hold"
                    if (hapticsEnabled) triggerVibration(context, "Hold"); delay(7000)
                    phase = "Exhale"; targetScale = 0.6f
                    if (hapticsEnabled) triggerVibration(context, "Exhale"); delay(8000)
                    phase = "Hold"
                    if (hapticsEnabled) triggerVibration(context, "Hold"); delay(4000)
                } else {
                    // Box Breathing: 4-4-4-4
                    phase = "Inhale"; targetScale = 1.0f
                    if (hapticsEnabled) triggerVibration(context, "Inhale"); delay(4000)
                    phase = "Hold"
                    if (hapticsEnabled) triggerVibration(context, "Hold"); delay(4000)
                    phase = "Exhale"; targetScale = 0.6f
                    if (hapticsEnabled) triggerVibration(context, "Exhale"); delay(4000)
                    phase = "Hold"
                    if (hapticsEnabled) triggerVibration(context, "Hold"); delay(4000)
                }
                cyclesLeft--
            }
            isRunning = false; phase = "Done!"
            if (hapticsEnabled) triggerVibration(context, "Done")
        } else { phase = "Ready?"; targetScale = 0.6f }
    }

    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 32.dp)) {
            Text("Vibration Cues")
            Spacer(modifier = Modifier.width(12.dp))
            Switch(checked = hapticsEnabled, onCheckedChange = { hapticsEnabled = it })
        }
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(300.dp)) {
            Box(modifier = Modifier.fillMaxSize(scale).clip(CircleShape).background(color))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = phase, style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
                if (isRunning) Text("Cycles left: $cyclesLeft", color = Color.White.copy(alpha = 0.8f))
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
        if (!isRunning) {
            // Cycles Dropdown
            ExposedDropdownMenuBox(expanded = isCyclesExpanded, onExpandedChange = { isCyclesExpanded = !isCyclesExpanded }) {
                OutlinedTextField(value = "$selectedCycles Cycles", onValueChange = {}, readOnly = true, label = { Text("Set Cycles") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isCyclesExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth(0.6f))
                ExposedDropdownMenu(expanded = isCyclesExpanded, onDismissRequest = { isCyclesExpanded = false }) {
                    listOf(5, 10, 30).forEach { DropdownMenuItem(text = { Text("$it Cycles") }, onClick = { selectedCycles = it; isCyclesExpanded = false }) }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Technique Dropdown
            ExposedDropdownMenuBox(expanded = isTechniqueExpanded, onExpandedChange = { isTechniqueExpanded = !isTechniqueExpanded }) {
                OutlinedTextField(value = selectedTechnique, onValueChange = {}, readOnly = true, label = { Text("Breathing Technique") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isTechniqueExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth(0.6f))
                ExposedDropdownMenu(expanded = isTechniqueExpanded, onDismissRequest = { isTechniqueExpanded = false }) {
                    listOf("4-7-8", "Box").forEach { technique -> DropdownMenuItem(text = { Text(technique) }, onClick = { selectedTechnique = technique; isTechniqueExpanded = false }) }
                }
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = { isRunning = !isRunning }, modifier = Modifier.fillMaxWidth(0.6f), colors = if (isRunning) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()) {
            Text(if (isRunning) "Stop" else "Start")
        }
    }
}


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
            FlowStep.EXCITEMENT -> QuestionTemplate("What are you excited for this week?", entry.excitementWeek, { entry = entry.copy(excitementWeek = it) }, { scope.launch { db.urgeDao().insert(entry); step = FlowStep.COMPLETE } })
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

@Composable
fun ColorPickerDialog(onColorSelected: (Color) -> Unit, onDismiss: () -> Unit) {
    val shades = listOf(
        Color(0xFFEF5350), Color(0xFFC62828), Color(0xFFAB47BC),
        Color(0xFF42A5F5), Color(0xFF1565C0), Color(0xFF26C6DA),
        Color(0xFF66BB6A), Color(0xFF2E7D32), Color(0xFFFFEE58),
        Color(0xFFF9A825), Color(0xFF8D6E63), Color(0xFF212121)
    )
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Customize Color") }, text = {
        LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.height(250.dp)) {
            items(shades) { col -> Box(modifier = Modifier.size(60.dp).padding(8.dp).clip(CircleShape).background(col).clickable { onColorSelected(col) }) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

// Renamed from JournalScreen to RecordsScreen
@Composable
fun RecordsScreen(db: AppDatabase) {
    val entries by db.urgeDao().getAllEntries().collectAsState(initial = emptyList())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entryToDelete by remember { mutableStateOf<UrgeEntry?>(null) }

    // Calculate the cutoff for one week ago (7 days * 24h * 60m * 60s * 1000ms)
    val oneWeekAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Urge Records", style = MaterialTheme.typography.headlineMedium, fontWeight = ComposeFontWeight.Bold)
                IconButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val textToCopy = entries.joinToString("\n\n") { entry ->
                        val date = SimpleDateFormat("MMM dd, yyyy h:mm a", Locale.getDefault()).format(Date(entry.timestamp))
                        "$date\nCategory: ${entry.category} (${entry.specificEmotion})\nLoved: ${entry.feltLoved}\nStress: ${entry.stressReason}\nExcited: ${entry.excitementWeek}"
                    }
                    val clip = ClipData.newPlainText("Urge Records", textToCopy)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Records copied to clipboard", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy All")
                }
            }
        }

        items(entries) { entry ->
            // UPDATED: Initial state depends on whether the entry is newer than one week
            var expanded by remember { mutableStateOf(entry.timestamp > oneWeekAgo) }

            val date = SimpleDateFormat("MMM dd, h:mm a", Locale.getDefault()).format(Date(entry.timestamp))
            val col = try { Color(android.graphics.Color.parseColor(entry.emotionColor)) } catch(e: Exception) { Color.Gray }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(col))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("${entry.category}: ${entry.specificEmotion}", fontWeight = ComposeFontWeight.Bold)
                        }
                        Text(date, style = MaterialTheme.typography.labelSmall)

                        if (expanded) {
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
                    IconButton(onClick = { entryToDelete = entry }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete Entry", tint = Color.Red.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }

    if (entryToDelete != null) {
        AlertDialog(
            onDismissRequest = { entryToDelete = null },
            title = { Text("Delete Record?") },
            text = { Text("This will permanently remove this urge entry from your history.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        entryToDelete?.let { db.urgeDao().deleteEntry(it) }
                        entryToDelete = null
                    }
                }) { Text("Delete", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { entryToDelete = null }) { Text("Cancel") }
            }
        )
    }
}

// NEW: Standard Journal Screen
@Composable
fun StandardJournalScreen(db: AppDatabase, journalColor: Color, urgeColor: Color, bothColor: Color) {
    val entries by db.urgeDao().getAllStandardJournals().collectAsState(initial = emptyList())
    val urgeEntries by db.urgeDao().getAllEntries().collectAsState(initial = emptyList())
    var currentView by rememberSaveable { mutableStateOf(JournalView.LIST) }
    var selectedDate by remember { mutableStateOf<Date?>(null) }
    
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var journalToDelete by remember { mutableStateOf<StandardJournalEntry?>(null) }

    if (currentView == JournalView.CALENDAR) {
        JournalCalendarView(
            journalEntries = entries,
            urgeEntries = urgeEntries,
            journalColor = journalColor,
            urgeColor = urgeColor,
            bothColor = bothColor,
            onBack = { currentView = JournalView.LIST },
            onDayClick = { selectedDate = it }
        )
    } else {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Daily Journal", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { currentView = JournalView.CALENDAR }) {
                        Icon(Icons.Default.CalendarMonth, null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Calendar")
                    }
                    // Copy to Clipboard Button
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val textToCopy = entries.joinToString("\n\n") { entry ->
                            val date = SimpleDateFormat("MMM dd, yyyy h:mm a", Locale.getDefault()).format(Date(entry.timestamp))
                            "$date\n${entry.content}"
                        }
                        val clip = android.content.ClipData.newPlainText("Journal Entries", textToCopy)
                        clipboard.setPrimaryClip(clip)
                        android.widget.Toast.makeText(context, "Journal copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy All")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().height(150.dp),
                placeholder = { Text("Write your thoughts here...") },
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
            )

            Button(
                onClick = {
                    if (text.isNotBlank()) {
                        scope.launch {
                            db.urgeDao().insertStandardJournal(StandardJournalEntry(content = text))
                            text = ""
                        }
                    }
                },
                modifier = Modifier.align(Alignment.End).padding(top = 8.dp)
            ) { Text("Save Entry") }

            Spacer(modifier = Modifier.height(24.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(entries) { entry ->
                    val date = SimpleDateFormat("MMM dd, yyyy \u2022 h:mm a", Locale.getDefault()).format(Date(entry.timestamp))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(date, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(entry.content, style = MaterialTheme.typography.bodyLarge)
                            }
                            IconButton(onClick = { journalToDelete = entry }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Journal", tint = Color.Red.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
        }
    }

    if (selectedDate != null) {
        DayEntriesDialog(
            date = selectedDate!!,
            journalEntries = entries,
            urgeEntries = urgeEntries,
            journalColor = journalColor,
            urgeColor = urgeColor,
            onDismiss = { selectedDate = null }
        )
    }

    if (journalToDelete != null) {
        AlertDialog(
            onDismissRequest = { journalToDelete = null },
            title = { Text("Delete Journal Entry?") },
            text = { Text("Are you sure you want to delete this thought? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        journalToDelete?.let { db.urgeDao().deleteStandardJournal(it) }
                        journalToDelete = null
                    }
                }) { Text("Delete", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { journalToDelete = null }) { Text("Cancel") }
            }
        )
    }
}

// --- CALENDAR COMPONENTS ---

@Composable
fun JournalCalendarView(
    journalEntries: List<StandardJournalEntry>,
    urgeEntries: List<UrgeEntry>,
    journalColor: Color,
    urgeColor: Color,
    bothColor: Color,
    onBack: () -> Unit,
    onDayClick: (Date) -> Unit
) {
    var calendar by remember { mutableStateOf(Calendar.getInstance()) }
    val monthYearFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text(monthYearFormat.format(calendar.time), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row {
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

        Spacer(modifier = Modifier.height(16.dp))

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
        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(totalSlots) { index ->
                val dayNum = index - startOffset + 1
                if (dayNum in 1..daysInMonth) {
                    val currentDayCal = (calendar.clone() as Calendar).apply {
                        set(Calendar.DAY_OF_MONTH, dayNum)
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val startTime = currentDayCal.timeInMillis
                    val endTime = startTime + 24 * 60 * 60 * 1000L

                    val hasJournal = journalEntries.any { it.timestamp in startTime until endTime }
                    val hasUrge = urgeEntries.any { it.timestamp in startTime until endTime }

                    val bgColor = when {
                        hasJournal && hasUrge -> bothColor
                        hasJournal -> journalColor
                        hasUrge -> urgeColor
                        else -> Color.Transparent
                    }

                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .padding(4.dp)
                            .clip(CircleShape)
                            .background(bgColor)
                            .clickable { onDayClick(currentDayCal.time) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = dayNum.toString(),
                            fontWeight = if (bgColor != Color.Transparent) FontWeight.Bold else FontWeight.Normal,
                            color = if (bgColor != Color.Transparent) Color.White else MaterialTheme.colorScheme.onSurface
                        )
                    }
                } else {
                    Box(modifier = Modifier.aspectRatio(1f))
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Legend
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LegendItem(journalColor, "Journal Entry")
            LegendItem(urgeColor, "Urge Flow Entry")
            LegendItem(bothColor, "Both Entries")
        }
    }
}

@Composable
fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun DayEntriesDialog(
    date: Date,
    journalEntries: List<StandardJournalEntry>,
    urgeEntries: List<UrgeEntry>,
    journalColor: Color,
    urgeColor: Color,
    onDismiss: () -> Unit
) {
    val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault())
    val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    val startTime = Calendar.getInstance().apply {
        time = date
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val endTime = startTime + 24 * 60 * 60 * 1000L

    val dayJournals = journalEntries.filter { it.timestamp in startTime until endTime }
    val dayUrges = urgeEntries.filter { it.timestamp in startTime until endTime }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dateFormat.format(date)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                if (dayJournals.isNotEmpty()) {
                    item { Text("Journal Entries", fontWeight = FontWeight.Bold, color = journalColor, modifier = Modifier.padding(vertical = 8.dp)) }
                    items(dayJournals) { entry ->
                        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = journalColor.copy(alpha = 0.1f))) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(timeFormat.format(Date(entry.timestamp)), style = MaterialTheme.typography.labelSmall, color = journalColor)
                                Text(entry.content, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                if (dayUrges.isNotEmpty()) {
                    item { Text("Urge Flow Entries", fontWeight = FontWeight.Bold, color = urgeColor, modifier = Modifier.padding(vertical = 8.dp)) }
                    items(dayUrges) { entry ->
                        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = urgeColor.copy(alpha = 0.1f))) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(timeFormat.format(Date(entry.timestamp)), style = MaterialTheme.typography.labelSmall, color = urgeColor)
                                Text("${entry.category}: ${entry.specificEmotion}", fontWeight = FontWeight.Bold)
                                Text("Loved: ${entry.feltLoved}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (dayJournals.isEmpty() && dayUrges.isEmpty()) {
                    item { Text("No entries for this day.") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun SettingsScreen(
    isDarkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    customThemeColor: Color?,
    onThemeColorChange: (Color?) -> Unit,
    journalColor: Color,
    onJournalColorChange: (Color) -> Unit,
    urgeColor: Color,
    onUrgeColorChange: (Color) -> Unit,
    bothColor: Color,
    onBothColorChange: (Color) -> Unit,
    onResetCalendarColors: () -> Unit,
    onBack: () -> Unit
) {
    var showColorPicker by remember { mutableStateOf(false) }
    var showCalendarPicker by remember { mutableStateOf(false) }
    var pickingFor by remember { mutableStateOf("") } // "theme", "journal", "urge", "both"

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DarkMode, null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Dark Mode", style = MaterialTheme.typography.bodyLarge)
                    }
                    Switch(checked = isDarkMode, onCheckedChange = onDarkModeChange)
                }

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp).clickable { 
                        pickingFor = "theme"
                        showColorPicker = true 
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Palette, null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Color Theme", style = MaterialTheme.typography.bodyLarge)
                    }
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(customThemeColor ?: MaterialTheme.colorScheme.primary)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp).clickable { showCalendarPicker = true },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CalendarMonth, null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Calendar Theme", style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(journalColor))
                        Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(urgeColor))
                        Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(bothColor))
                    }
                }
            }
        }
    }

    if (showCalendarPicker) {
        AlertDialog(
            onDismissRequest = { showCalendarPicker = false },
            title = { Text("Calendar Colors") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CalendarColorRow("Journal Entry", journalColor) {
                        pickingFor = "journal"
                        showColorPicker = true
                        showCalendarPicker = false
                    }
                    CalendarColorRow("Urge Flow Entry", urgeColor) {
                        pickingFor = "urge"
                        showColorPicker = true
                        showCalendarPicker = false
                    }
                    CalendarColorRow("Both Entries", bothColor) {
                        pickingFor = "both"
                        showColorPicker = true
                        showCalendarPicker = false
                    }
                    TextButton(onClick = {
                        onResetCalendarColors()
                        showCalendarPicker = false
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Reset to Default")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showCalendarPicker = false }) { Text("Close") } }
        )
    }

    if (showColorPicker) {
        ThemeColorPickerDialog(
            title = when(pickingFor) {
                "journal" -> "Journal Entry Color"
                "urge" -> "Urge Flow Color"
                "both" -> "Both Entries Color"
                else -> "App Theme Color"
            },
            onColorSelected = { 
                when(pickingFor) {
                    "theme" -> onThemeColorChange(it)
                    "journal" -> onJournalColorChange(it)
                    "urge" -> onUrgeColorChange(it)
                    "both" -> onBothColorChange(it)
                }
                showColorPicker = false
            },
            onReset = {
                if (pickingFor == "theme") onThemeColorChange(null)
                showColorPicker = false
            },
            showReset = pickingFor == "theme",
            onDismiss = { showColorPicker = false }
        )
    }
}

@Composable
fun CalendarColorRow(label: String, color: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Box(modifier = Modifier.size(24.dp).clip(CircleShape).background(color).border(1.dp, Color.Gray, CircleShape))
    }
}

@Composable
fun ThemeColorPickerDialog(
    title: String = "Choose App Theme Color",
    showReset: Boolean = true,
    onColorSelected: (Color) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val themeColors = listOf(
        Color(0xFFEF5350), Color(0xFFE91E63), Color(0xFF9C27B0),
        Color(0xFF673AB7), Color(0xFF3F51B5), Color(0xFF2196F3),
        Color(0xFF03A9F4), Color(0xFF00BCD4), Color(0xFF009688),
        Color(0xFF4CAF50), Color(0xFF8BC34A), Color(0xFFCDDC39),
        Color(0xFFFFEB3B), Color(0xFFFFC107), Color(0xFFFF9800),
        Color(0xFFFF5722), Color(0xFF795548), Color(0xFF607D8B)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.height(200.dp)) {
                    items(themeColors) { col ->
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .padding(4.dp)
                                .clip(CircleShape)
                                .background(col)
                                .clickable { onColorSelected(col) }
                        )
                    }
                }
                if (showReset) {
                    TextButton(
                        onClick = onReset,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        Text("Reset to Default")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

fun calculateStreak(entries: List<UrgeEntry>): Int {
    if (entries.isEmpty()) return 0
    val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    val uniqueDays = entries.map { Calendar.getInstance().apply { timeInMillis = it.timestamp; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis }.distinct().sortedDescending()
    var streak = 0
    val firstDay = uniqueDays.firstOrNull() ?: return 0
    var checkDate = if (firstDay == today) today else if (firstDay == today - 86400000L) today - 86400000L else return 0
    for (day in uniqueDays) { if (day == checkDate) { streak++; checkDate -= 86400000L } else break }
    return streak
}

@Composable
fun QuestionTemplate(q: String, v: String, onV: (String) -> Unit, onN: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(q, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = v,
            onValueChange = onV,
            modifier = Modifier.fillMaxWidth().height(150.dp).padding(top = 16.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
        )
        Button(onClick = onN, modifier = Modifier.padding(top = 24.dp)) { Text("Continue") }
    }
}

@Composable
fun DailyQuoteSection(db: AppDatabase) {
    val quotes by db.urgeDao().getAllQuotes().collectAsState(initial = emptyList())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }

    val dailyQuote = remember(quotes) {
        val prefs = context.getSharedPreferences("daily_quote_prefs", Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val lastDate = prefs.getString("last_date", "")
        val lastQuote = prefs.getString("last_quote", "")

        if (quotes.isEmpty()) {
            "Add a quote to inspire your day."
        } else if (lastDate == today && !lastQuote.isNullOrEmpty() && quotes.any { it.text == lastQuote }) {
            lastQuote
        } else {
            val picked = quotes.random().text
            prefs.edit().putString("last_date", today).putString("last_quote", picked).apply()
            picked
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 32.dp)) {
        Text("\"$dailyQuote\"", fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
        Row {
            IconButton(onClick = { showDialog = true }) { Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary) }
            if (quotes.isNotEmpty() && dailyQuote != "Add a quote to inspire your day.") {
                IconButton(onClick = {
                    scope.launch {
                        quotes.find { it.text == dailyQuote }?.let { db.urgeDao().deleteQuote(it) }
                        context.getSharedPreferences("daily_quote_prefs", Context.MODE_PRIVATE).edit().remove("last_quote").apply()
                    }
                }) { Icon(Icons.Default.DeleteSweep, null, tint = Color.Red.copy(alpha = 0.4f)) }
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Bulk Add Quotes") },
            text = {
                Column {
                    Text("Separate quotes with |", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text("Quote 1 | Quote 2 | ...") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if(text.isNotBlank()) {
                            val rawList = text.split("|")
                            val cleanQuotes = rawList.map { it.trim() }.filter { it.isNotEmpty() }
                            cleanQuotes.forEach { quoteText ->
                                db.urgeDao().insertQuote(Quote(text = quoteText))
                            }
                            android.widget.Toast.makeText(context, "Added ${cleanQuotes.size} quotes!", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        text = ""
                        showDialog = false
                    }
                }) { Text("Save All") }
            }
        )
    }
}
