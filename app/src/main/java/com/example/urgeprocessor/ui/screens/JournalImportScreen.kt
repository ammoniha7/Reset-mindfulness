package com.example.urgeprocessor.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.urgeprocessor.data.AppDatabase
import com.example.urgeprocessor.data.StandardJournalEntry
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun JournalImportScreen(db: AppDatabase, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            uri?.let {
                try {
                    context.contentResolver.openInputStream(it)?.use { stream ->
                        inputText = stream.bufferedReader().readText()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Error reading file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    )

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text("Bulk Import Journal", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }

        Text(
            "Paste your text below or upload a .txt file. Format should be MM-DD-YYYY: or MM/DD/YYYY: followed by the entry.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
            placeholder = { Text("Paste journal text here...") },
            shape = RoundedCornerShape(8.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { filePickerLauncher.launch(arrayOf("text/plain")) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Icon(Icons.Default.AttachFile, null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Select .txt")
            }

            Button(
                onClick = {
                    isProcessing = true
                    scope.launch {
                        val importedCount = parseAndImportJournal(db, inputText)
                        isProcessing = false
                        resultMessage = "Successfully imported $importedCount entries!"
                        inputText = ""
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = inputText.isNotBlank() && !isProcessing
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text("Start Import")
                }
            }
        }

        resultMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}

suspend fun parseAndImportJournal(db: AppDatabase, text: String): Int {
    // Regex for MM-DD-YYYY or MM/DD/YYYY with optional colon
    val regex = Regex("""(\d{1,2}[-/]\d{1,2}[-/]\d{4}):?""")
    val matches = regex.findAll(text).toList()
    if (matches.isEmpty()) return 0

    var count = 0
    val dateFormats = listOf(
        SimpleDateFormat("MM-dd-yyyy", Locale.US),
        SimpleDateFormat("MM/dd/yyyy", Locale.US),
        SimpleDateFormat("M-d-yyyy", Locale.US),
        SimpleDateFormat("M/d/yyyy", Locale.US)
    )

    for (i in matches.indices) {
        val currentMatch = matches[i]
        val dateString = currentMatch.groupValues[1]
        
        // Find everything between this match and the next one (or end of string)
        val start = currentMatch.range.last + 1
        val end = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
        val content = text.substring(start, end).trim()

        if (content.isNotBlank()) {
            var timestamp: Long? = null
            for (format in dateFormats) {
                try {
                    val date = format.parse(dateString)
                    if (date != null) {
                        // Normalize to midnight
                        val cal = Calendar.getInstance().apply {
                            time = date
                            set(Calendar.HOUR_OF_DAY, 0)
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        timestamp = cal.timeInMillis
                        break
                    }
                } catch (e: Exception) {}
            }

            if (timestamp != null) {
                db.urgeDao().insertStandardJournal(StandardJournalEntry(timestamp = timestamp, content = content))
                count++
            }
        }
    }
    return count
}
