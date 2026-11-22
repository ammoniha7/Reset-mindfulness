package com.example.urgeprocessor.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.urgeprocessor.ui.components.ThemeColorPickerDialog
import com.example.urgeprocessor.data.RecordingFiles
import com.example.urgeprocessor.util.StorageUsage
import com.example.urgeprocessor.util.readStorageUsage
import com.example.urgeprocessor.util.formatStorageSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    onBack: () -> Unit,
    journalCount: Int,
    urgeCount: Int,
    recordingCount: Int
) {
    val context = LocalContext.current
    val storage by produceState<StorageUsage?>(null, context, listOf(journalCount, urgeCount, recordingCount)) {
        value = withContext(Dispatchers.IO) {
            readStorageUsage(context.getDatabasePath("urge_db"), RecordingFiles(context).directory)
        }
    }
    var showColorPicker by remember { mutableStateOf(false) }
    var showCalendarPicker by remember { mutableStateOf(false) }
    var pickingFor by remember { mutableStateOf("") } // "theme", "journal", "urge", "both"

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text("Appearance", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
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

        Spacer(modifier = Modifier.height(24.dp))
        Text("Storage & Stats", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StorageRow(Icons.Default.SdStorage, "Total Database Size", storage?.let { formatStorageSize(it.databaseBytes) } ?: "…")
                StorageRow(Icons.Default.Mic, "Recording Storage", storage?.let { formatStorageSize(it.recordingBytes) } ?: "…")
                StorageRow(Icons.Default.SdStorage, "Total Storage", storage?.let { formatStorageSize(it.totalBytes) } ?: "…")
                Text("Total includes database and audio files, including unfinished recordings. App installation, cache, and exported backups are excluded.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                StorageRow(Icons.Default.EditNote, "Journal Entries", journalCount.toString())
                StorageRow(Icons.Default.Psychology, "Urge Records", urgeCount.toString())
                StorageRow(Icons.Default.Mic, "Voice Entries", recordingCount.toString())
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
fun StorageRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(modifier = Modifier.weight(1f).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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
