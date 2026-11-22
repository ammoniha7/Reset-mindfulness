package com.example.urgeprocessor

import android.content.Context
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.example.urgeprocessor.data.*
import com.example.urgeprocessor.util.DEFAULT_URGE_HOLD_SECONDS
import com.example.urgeprocessor.util.URGE_HOLD_SECONDS_KEY
import com.example.urgeprocessor.audio.RecordingController
import com.example.urgeprocessor.ui.components.RecordingDialog
import com.example.urgeprocessor.ui.screens.*
import com.example.urgeprocessor.ui.theme.ResetTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// --- MAIN ACTIVITY ---

class MainActivity : ComponentActivity() {
    private val recordings by lazy { RecordingController.get(this) }

    override fun onStop() {
        recordings.stop("Recording stopped when the app left the screen. Your audio is ready to review.")
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.getDatabase(this)
        val drafts = JournalDraftStore.get(this)
        val recordingController = recordings
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Also catches up after force-stop, sleep, or a clock change. No database
                // access occurs here unless there is an overdue draft to save.
                while (isActive) {
                    drafts.saveIfDue()
                    delay(1_000)
                }
            }
        }
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val prefs = remember { context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE) }
            var urgeHoldSeconds by remember {
                mutableIntStateOf(prefs.getInt(URGE_HOLD_SECONDS_KEY, DEFAULT_URGE_HOLD_SECONDS).coerceIn(1, 500))
            }
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
            
            ResetTheme(darkTheme = isDarkMode, customColor = customThemeColor) {
                var currentDest by rememberSaveable { mutableStateOf(AppDestinations.FLOW) }
                var showRecorder by rememberSaveable { mutableStateOf(recordingController.state.value.draft != null) }
                val recordingState by recordingController.state.collectAsState()
                var calendarResetTrigger by remember { mutableLongStateOf(0L) }

                if (showRecorder) {
                    RecordingDialog(db, recordingController, onDismiss = { showRecorder = false })
                }

                NavigationSuiteScaffold(
                    navigationSuiteItems = {
                        AppDestinations.entries.filter { it.showInNavBar }.forEach {
                            item(
                                icon = { Icon(it.icon, null) },
                                label = { Text(it.label) },
                                selected = it == currentDest,
                                onClick = {
                                    if (currentDest == it && it == AppDestinations.CALENDAR) {
                                        calendarResetTrigger = System.currentTimeMillis()
                                    }
                                    currentDest = it
                                }
                            )
                        }
                    }
                ) {
                    Scaffold { innerPadding ->
                        Box(modifier = Modifier.padding(innerPadding)) {
                            when (currentDest) {
                                AppDestinations.FLOW -> UrgeFlowScreen(db, holdSeconds = urgeHoldSeconds, onNavigateToSettings = { currentDest = AppDestinations.SETTINGS })
                                AppDestinations.BREATHE -> BreathingScreen()
                                AppDestinations.JOURNAL -> StandardJournalScreen(
                                    db = db,
                                    onNavigateToCalendar = { currentDest = AppDestinations.CALENDAR },
                                    onNavigateToImport = { currentDest = AppDestinations.IMPORT },
                                    onRecord = { showRecorder = true },
                                    hasRecordingDraft = recordingState.draft != null
                                )
                                AppDestinations.IMPORT -> JournalImportScreen(
                                    db = db,
                                    onBack = { currentDest = AppDestinations.JOURNAL }
                                )
                                AppDestinations.CALENDAR -> JournalCalendarView(
                                    db = db,
                                    journalEntries = db.urgeDao().getAllStandardJournals().collectAsState(initial = emptyList()).value,
                                    recordingEntries = db.urgeDao().getAllRecordings().collectAsState(initial = emptyList()).value,
                                    urgeEntries = db.urgeDao().getAllEntries().collectAsState(initial = emptyList()).value,
                                    journalColor = journalColor,
                                    urgeColor = urgeColor,
                                    bothColor = bothColor,
                                    onBack = { currentDest = AppDestinations.JOURNAL },
                                    resetTrigger = calendarResetTrigger
                                )
                                AppDestinations.SETTINGS -> SettingsScreen(
                                    urgeHoldSeconds = urgeHoldSeconds,
                                    onUrgeHoldSecondsChange = { seconds ->
                                        urgeHoldSeconds = seconds.coerceIn(1, 500)
                                        prefs.edit().putInt(URGE_HOLD_SECONDS_KEY, urgeHoldSeconds).apply()
                                    },
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
                                    onBack = { currentDest = AppDestinations.FLOW },
                                    journalCount = db.urgeDao().getAllStandardJournals().collectAsState(initial = emptyList()).value.size,
                                    urgeCount = db.urgeDao().getAllEntries().collectAsState(initial = emptyList()).value.size,
                                    recordingCount = db.urgeDao().getAllRecordings().collectAsState(initial = emptyList()).value.size
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
    JOURNAL("Journal", Icons.Default.EditNote),
    CALENDAR("Calendar", Icons.Default.CalendarMonth),
    SETTINGS("Settings", Icons.Default.Settings, false),
    IMPORT("Import", Icons.Default.UploadFile, false)
}
