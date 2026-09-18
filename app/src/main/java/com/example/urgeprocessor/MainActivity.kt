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
import com.example.urgeprocessor.ui.screens.*
import com.example.urgeprocessor.ui.theme.ResetTheme
import kotlinx.coroutines.flow.first

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
            
            ResetTheme(darkTheme = isDarkMode, customColor = customThemeColor) {
                var currentDest by rememberSaveable { mutableStateOf(AppDestinations.FLOW) }
                var calendarResetTrigger by remember { mutableLongStateOf(0L) }

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
                                AppDestinations.FLOW -> UrgeFlowScreen(db, onNavigateToSettings = { currentDest = AppDestinations.SETTINGS })
                                AppDestinations.BREATHE -> BreathingScreen()
                                AppDestinations.JOURNAL -> StandardJournalScreen(
                                    db = db,
                                    onNavigateToCalendar = { currentDest = AppDestinations.CALENDAR },
                                    onNavigateToImport = { currentDest = AppDestinations.IMPORT }
                                )
                                AppDestinations.IMPORT -> JournalImportScreen(
                                    db = db,
                                    onBack = { currentDest = AppDestinations.JOURNAL }
                                )
                                AppDestinations.CALENDAR -> JournalCalendarView(
                                    db = db,
                                    journalEntries = db.urgeDao().getAllStandardJournals().collectAsState(initial = emptyList()).value,
                                    urgeEntries = db.urgeDao().getAllEntries().collectAsState(initial = emptyList()).value,
                                    journalColor = journalColor,
                                    urgeColor = urgeColor,
                                    bothColor = bothColor,
                                    onBack = { currentDest = AppDestinations.JOURNAL },
                                    resetTrigger = calendarResetTrigger
                                )
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
                                    onBack = { currentDest = AppDestinations.FLOW },
                                    journalCount = db.urgeDao().getAllStandardJournals().collectAsState(initial = emptyList()).value.size,
                                    urgeCount = db.urgeDao().getAllEntries().collectAsState(initial = emptyList()).value.size
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
