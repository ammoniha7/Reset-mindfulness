package com.example.urgeprocessor

import android.content.Context
import android.os.Bundle
import android.os.Vibrator
import android.os.VibrationEffect
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
    var struggleToday: String = "",
    var struggleWeek: String = "",
    var excitementWeek: String = ""
)

@Entity(tableName = "quotes")
data class Quote(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val text: String
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
}

@Database(entities = [UrgeEntry::class, Quote::class], version = 2)
abstract class AppDatabase : RoomDatabase() {
    abstract fun urgeDao(): UrgeDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "urge_db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// --- UTILS & HELPERS ---

fun triggerVibration(context: Context, type: String) {
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    vibrator.cancel()

    // Define the pattern
    val pattern = when (type) {
        "Inhale" -> longArrayOf(0, 60)              // Reduced from 200 to 60
        "Hold" -> longArrayOf(0, 30, 60, 30)       // Very sharp double-click
        "Exhale" -> longArrayOf(0, 250)            // Reduced from 500 to 250
        "Done" -> longArrayOf(0, 50, 40, 50, 40, 150)
        else -> longArrayOf(0, 30)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        // THE TRICK: Set the Usage to USAGE_ALARM or USAGE_RINGTONE
        // This tells Android "This is important, don't block it like a button click"
        val attributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_ALARM)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1), attributes)
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(pattern, -1)
    }
}

fun exportEntriesToCsv(context: Context, entries: List<UrgeEntry>) {
    val csvHeader = "Date,Category,Emotion,Color,Felt Loved,Struggle Today,Struggle Week,Excited For\n"
    val csvData = entries.joinToString(separator = "\n") { entry ->
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(entry.timestamp))
        "\"$date\",\"${entry.category}\",\"${entry.specificEmotion}\",\"${entry.emotionColor}\",\"${entry.feltLoved}\",\"${entry.struggleToday}\",\"${entry.struggleWeek}\",\"${entry.excitementWeek}\""
    }
    val sendIntent = android.content.Intent().apply {
        action = android.content.Intent.ACTION_SEND
        putExtra(android.content.Intent.EXTRA_TEXT, csvHeader + csvData)
        type = "text/plain"
    }
    context.startActivity(android.content.Intent.createChooser(sendIntent, "Export Journal"))
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

// --- MAIN ACTIVITY ---

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.getDatabase(this)
        enableEdgeToEdge()
        setContent {
            UrgeProcessorTheme {
                var currentDest by rememberSaveable { mutableStateOf(AppDestinations.FLOW) }
                NavigationSuiteScaffold(
                    navigationSuiteItems = {
                        AppDestinations.entries.forEach {
                            item(icon = { Icon(it.icon, null) }, label = { Text(it.label) }, selected = it == currentDest, onClick = { currentDest = it })
                        }
                    }
                ) {
                    Scaffold { innerPadding ->
                        Box(modifier = Modifier.padding(innerPadding)) {
                            when (currentDest) {
                                AppDestinations.FLOW -> UrgeFlowScreen(db)
                                AppDestinations.BREATHE -> BreathingScreen()
                                AppDestinations.JOURNAL -> JournalScreen(db)
                                AppDestinations.STATS -> InsightsScreen(db)
                            }
                        }
                    }
                }
            }
        }
    }
}

enum class AppDestinations(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    FLOW("Urge Flow", Icons.Default.Psychology),
    BREATHE("Breathe", Icons.Default.Air),
    JOURNAL("Journal", Icons.Default.EditNote),
    STATS("Insights", Icons.Default.Insights)
}

enum class FlowStep { CATEGORY, SPECIFIC, COLOR, LOVED, STRUGGLE_TODAY, STRUGGLE_WEEK, EXCITEMENT, COMPLETE }

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
    var isExpanded by remember { mutableStateOf(false) }
    var hapticsEnabled by rememberSaveable { mutableStateOf(true) }

    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(durationMillis = if (phase == "Inhale") 4000 else if (phase == "Exhale") 8000 else 500, easing = LinearEasing),
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
                // --- INHALE (4s) ---
                phase = "Inhale"
                targetScale = 1.0f
                if (hapticsEnabled) triggerVibration(context, "Inhale")
                delay(4000)

                // --- HOLD (7s) ---
                phase = "Hold"
                if (hapticsEnabled) triggerVibration(context, "Hold")
                delay(7000)

                // --- EXHALE (8s) ---
                phase = "Exhale"
                targetScale = 0.6f
                if (hapticsEnabled) triggerVibration(context, "Exhale")
                delay(8000)

                // --- HOLD (4s) ---
                phase = "Hold"
                if (hapticsEnabled) triggerVibration(context, "Hold")
                delay(4000)

                cyclesLeft--
            }
            isRunning = false
            phase = "Done!"
            if (hapticsEnabled) triggerVibration(context, "Done")
        } else {
            phase = "Ready?"
            targetScale = 0.6f
        }
    }

    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 32.dp)) {
            Text("Vibration Cues", style = MaterialTheme.typography.bodyLarge)
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
            ExposedDropdownMenuBox(expanded = isExpanded, onExpandedChange = { isExpanded = !isExpanded }) {
                OutlinedTextField(value = "$selectedCycles Cycles", onValueChange = {}, readOnly = true, label = { Text("Set Cycles") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth(0.6f))
                ExposedDropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }) {
                    listOf(5, 10, 30).forEach { DropdownMenuItem(text = { Text("$it Cycles") }, onClick = { selectedCycles = it; isExpanded = false }) }
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = { isRunning = !isRunning }, modifier = Modifier.fillMaxWidth(0.6f), colors = if (isRunning) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()) {
            Text(if (isRunning) "Stop" else "Start 4-7-8")
        }
    }
}

@Composable
fun UrgeFlowScreen(db: AppDatabase) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(FlowStep.CATEGORY) }
    var entry by remember { mutableStateOf(UrgeEntry()) }
    val categories = listOf("Accepting", "Angry", "Sad", "Fear", "Stressed", "Joy")
    val specificEmotions = mapOf(
        "Accepting" to listOf("Calm", "Centered", "Content", "Forgiving", "Patient"),
        "Angry" to listOf("Agitated", "Frustrated", "Pissed", "Resentful", "Outraged"),
        "Sad" to listOf("Lonely", "Grief", "Disappointed", "Hopeless", "Empty"),
        "Fear" to listOf("Anxious", "Terrified", "Insecure", "Vulnerable", "Panicked"),
        "Stressed" to listOf("Overwhelmed", "Burnt Out", "Pressured", "Distracted"),
        "Joy" to listOf("Bliss", "Vibrant", "Grateful", "Inspired", "Playful")
    )

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(40.dp))
        when (step) {
            FlowStep.CATEGORY -> {
                Text("How are you feeling?", style = MaterialTheme.typography.headlineMedium); Spacer(modifier = Modifier.height(30.dp))
                LazyVerticalGrid(columns = GridCells.Fixed(2), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.heightIn(max = 300.dp)) {
                    items(categories) { cat -> Button(onClick = { entry.category = cat; step = FlowStep.SPECIFIC }, modifier = Modifier.height(60.dp)) { Text(cat) } }
                }
                DailyQuoteSection(db)
            }
            FlowStep.SPECIFIC -> {
                Text("Be more specific:", style = MaterialTheme.typography.headlineSmall)
                val emotionsToShow = specificEmotions[entry.category] ?: listOf("General", "Unsure", "Mixed")
                emotionsToShow.forEach { emotion ->
                    OutlinedButton(onClick = { entry.specificEmotion = emotion; step = FlowStep.COLOR }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text(emotion) }
                }
            }
            FlowStep.COLOR -> {
                Text("Give your emotion a color:", style = MaterialTheme.typography.headlineSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 24.dp)) {
                    val colorMap = mapOf("Blue" to Color.Blue, "Green" to Color.Green, "Red" to Color.Red, "Yellow" to Color.Yellow)
                    colorMap.forEach { (name, color) -> Box(modifier = Modifier.size(60.dp).clip(CircleShape).background(color).clickable { entry.emotionColor = name; step = FlowStep.LOVED }) }
                }
            }
            FlowStep.LOVED -> QuestionTemplate("Felt loved today?", entry.feltLoved, { entry = entry.copy(feltLoved = it) }, { step = FlowStep.STRUGGLE_TODAY })
            FlowStep.STRUGGLE_TODAY -> QuestionTemplate("Biggest struggle today?", entry.struggleToday, { entry = entry.copy(struggleToday = it) }, { step = FlowStep.STRUGGLE_WEEK })
            FlowStep.STRUGGLE_WEEK -> QuestionTemplate("Biggest struggle this week?", entry.struggleWeek, { entry = entry.copy(struggleWeek = it) }, { step = FlowStep.EXCITEMENT })
            FlowStep.EXCITEMENT -> QuestionTemplate("Excited for what?", entry.excitementWeek, { entry = entry.copy(excitementWeek = it) }, { scope.launch { db.urgeDao().insert(entry); step = FlowStep.COMPLETE } })
            FlowStep.COMPLETE -> {
                Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(80.dp), tint = Color(0xFF4CAF50))
                Text("Saved", style = MaterialTheme.typography.headlineSmall)
                Button(onClick = { entry = UrgeEntry(); step = FlowStep.CATEGORY }) { Text("Restart") }
            }
        }
    }
}

@Composable
fun JournalItem(entry: UrgeEntry, db: AppDatabase, initiallyExpanded: Boolean) {
    var isExpanded by remember { mutableStateOf(initiallyExpanded) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val date = SimpleDateFormat("MMM dd, h:mm a", Locale.getDefault()).format(Date(entry.timestamp))
    val displayColor = when (entry.emotionColor) {
        "Blue" -> Color(0xFF2196F3); "Green" -> Color(0xFF4CAF50); "Red" -> Color(0xFFF44336); "Yellow" -> Color(0xFFFFEB3B); else -> Color.Gray
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Entry?") },
            text = { Text("This will permanently remove this journal entry.") },
            confirmButton = { TextButton(onClick = { scope.launch { db.urgeDao().deleteEntry(entry) }; showDeleteConfirm = false }) { Text("Delete", color = Color.Red) } },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
        )
    }

    Card(modifier = Modifier.fillMaxWidth().clickable { isExpanded = !isExpanded }, shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(displayColor))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = if (isExpanded) "${entry.category} (${entry.specificEmotion})" else entry.category, fontWeight = FontWeight.Bold)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(date, style = MaterialTheme.typography.labelMedium)
                    IconButton(onClick = { showDeleteConfirm = true }) { Icon(Icons.Default.DeleteOutline, null, tint = Color.Red.copy(alpha = 0.6f)) }
                }
            }
            if (isExpanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                JournalDetailRow("Loved:", entry.feltLoved)
                JournalDetailRow("Today:", entry.struggleToday)
                JournalDetailRow("Week:", entry.struggleWeek)
                JournalDetailRow("Excited:", entry.excitementWeek)
            }
        }
    }
}

@Composable
fun JournalDetailRow(label: String, value: String) {
    if (value.isNotBlank()) {
        Text(label, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Text(value, modifier = Modifier.padding(bottom = 8.dp))
    }
}

@Composable
fun JournalScreen(db: AppDatabase) {
    val entries by db.urgeDao().getAllEntries().collectAsState(initial = emptyList())
    val weekInMs = 7 * 24 * 60 * 60 * 1000L
    val now = System.currentTimeMillis()
    val recent = entries.filter { (now - it.timestamp) < weekInMs }
    val archive = entries.filter { (now - it.timestamp) >= weekInMs }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Past 7 Days", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        if (recent.isEmpty()) item { Text("No recent entries.", color = Color.Gray) }
        else items(recent) { JournalItem(it, db, true) }
        if (archive.isNotEmpty()) {
            item { Text("Archive", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp)) }
            items(archive) { JournalItem(it, db, false) }
        }
    }
}

@Composable
fun InsightsScreen(db: AppDatabase) {
    val entries by db.urgeDao().getAllEntries().collectAsState(initial = emptyList())
    val context = LocalContext.current
    val streak = calculateStreak(entries)
    val weekInMs = 7 * 24 * 60 * 60 * 1000L
    val now = System.currentTimeMillis()
    val recent = entries.filter { (now - it.timestamp) < weekInMs }
    val colorCounts = recent.groupingBy { it.emotionColor }.eachCount()

    Column(modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Your Stats", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            IconButton(onClick = { exportEntriesToCsv(context, entries) }) { Icon(Icons.Default.Share, "Export", tint = MaterialTheme.colorScheme.primary) }
        }
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocalFireDepartment, null, tint = Color(0xFFFF5722), modifier = Modifier.size(40.dp))
                Column(modifier = Modifier.padding(start = 16.dp)) {
                    Text("$streak Day Streak", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Stay consistent!")
                }
            }
        }
        if (recent.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Color Balance (Last 7 Days)", fontWeight = FontWeight.Bold)
                    Row(modifier = Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(15.dp)).padding(top = 8.dp)) {
                        listOf("Red", "Blue", "Green", "Yellow").forEach { name ->
                            val count = colorCounts[name] ?: 0
                            if (count > 0) Box(modifier = Modifier.fillMaxHeight().weight(count.toFloat()).background(when(name){"Red"->Color.Red;"Blue"->Color.Blue;"Green"->Color.Green;else->Color.Yellow}))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QuestionTemplate(q: String, v: String, onV: (String) -> Unit, onN: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(q, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        OutlinedTextField(value = v, onValueChange = onV, modifier = Modifier.fillMaxWidth().height(200.dp).padding(top = 16.dp))
        Button(onClick = onN, modifier = Modifier.padding(top = 24.dp)) { Text("Continue") }
    }
}

@Composable
fun DailyQuoteSection(db: AppDatabase) {
    val quotes by db.urgeDao().getAllQuotes().collectAsState(initial = emptyList())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }
    var newQuoteText by remember { mutableStateOf("") }
    val dailyQuote = remember(quotes) {
        val prefs = context.getSharedPreferences("daily_quote_prefs", Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val savedDate = prefs.getString("last_date", "")
        val savedQuote = prefs.getString("last_quote", "")
        if (quotes.isEmpty()) "Add a quote to inspire your day."
        else if (savedDate == today && !savedQuote.isNullOrEmpty() && quotes.any { it.text == savedQuote }) savedQuote
        else {
            val picked = quotes.randomOrNull()?.text ?: "Add a quote to inspire your day."
            prefs.edit().putString("last_date", today).putString("last_quote", picked).apply()
            picked
        }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "\"$dailyQuote\"", fontSize = 20.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(horizontal = 24.dp))
        Row {
            IconButton(onClick = { showDialog = true }) { Icon(Icons.Default.AddCircleOutline, null, tint = MaterialTheme.colorScheme.primary) }
            if (quotes.isNotEmpty() && dailyQuote != "Add a quote to inspire your day.") {
                IconButton(onClick = { scope.launch { quotes.find { it.text == dailyQuote }?.let { db.urgeDao().deleteQuote(it); context.getSharedPreferences("daily_quote_prefs", Context.MODE_PRIVATE).edit().remove("last_quote").apply() } } }) {
                    Icon(Icons.Default.DeleteSweep, null, tint = MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                }
            }
        }
    }
    if (showDialog) {
        AlertDialog(onDismissRequest = { showDialog = false }, title = { Text("Add Inspiration") }, text = { OutlinedTextField(value = newQuoteText, onValueChange = { newQuoteText = it }) }, confirmButton = { TextButton(onClick = { if (newQuoteText.isNotBlank()) { scope.launch { db.urgeDao().insertQuote(Quote(text = newQuoteText)); newQuoteText = ""; showDialog = false } } }) { Text("Save") } })
    }
}