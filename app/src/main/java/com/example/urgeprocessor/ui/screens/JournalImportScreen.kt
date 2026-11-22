package com.example.urgeprocessor.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import com.example.urgeprocessor.data.RecordingFiles
import com.example.urgeprocessor.data.importRecordingBackup
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.urgeprocessor.data.AppDatabase
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import com.example.urgeprocessor.data.importEntries

@Composable
fun JournalImportScreen(db: AppDatabase, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedBackupPath by rememberSaveable { mutableStateOf<String?>(null) }
    var inputText by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            uri?.let {
                isProcessing = true
                scope.launch {
                    try {
                        val selected = withContext(Dispatchers.IO) {
                            val file = File(context.cacheDir, "entry-import-${UUID.randomUUID()}")
                            try {
                                val stream = context.contentResolver.openInputStream(it) ?: error("Could not open file")
                                stream.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
                                val zip = file.inputStream().use { input -> input.read() == 0x50 && input.read() == 0x4b }
                                if (zip) {
                                    ZipFile(file).use { archive -> check(archive.getEntry("entries.json") != null) }
                                    file to null
                                } else {
                                    check(file.length() <= 8_388_608) { "Text file is too large" }
                                    val text = file.readText(Charsets.UTF_8)
                                    file.delete()
                                    null to text
                                }
                            } catch (failure: Throwable) { file.delete(); throw failure }
                        }
                        selectedBackupPath?.let { File(it).delete() }
                        selectedBackupPath = selected.first?.path
                        inputText = selected.second.orEmpty()
                        resultMessage = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        resultMessage = "Could not read the selected file."
                    } finally {
                        isProcessing = false
                    }
                }
            }
        }
    )

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text("Import Entries", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }

        Text(
            "Open a Reset .zip backup (including recordings), an older .json backup, or a .txt export, or paste journal text with each entry starting on a new line with MM-DD-YYYY: or MM/DD/YYYY:. Import adds records without replacing existing ones; importing the same file again creates duplicates.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        if (selectedBackupPath != null) {
            Text("Recording backup selected. Tap Start Import to restore it.")
            TextButton(onClick = {
                selectedBackupPath?.let { File(it).delete() }
                selectedBackupPath = null
            }, enabled = !isProcessing) { Text("Clear selected backup") }
        }

        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
            placeholder = { Text("Paste journal text here...") },
            shape = RoundedCornerShape(8.dp),
            enabled = !isProcessing && selectedBackupPath == null
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { filePickerLauncher.launch(arrayOf("text/plain", "application/json", "application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                modifier = Modifier.weight(1f),
                enabled = !isProcessing,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Icon(Icons.Default.AttachFile, null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Select file")
            }

            Button(
                onClick = {
                    isProcessing = true
                    scope.launch {
                        try {
                            val importedCount = selectedBackupPath?.let {
                                importRecordingBackup(db, RecordingFiles(context), File(it))
                            } ?: importEntries(db, inputText)
                            selectedBackupPath?.let { File(it).delete() }
                            selectedBackupPath = null
                            resultMessage = "Imported $importedCount records."
                            inputText = ""
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: IllegalArgumentException) {
                            resultMessage = e.message ?: "Invalid input. No records were imported."
                        } catch (e: Exception) {
                            resultMessage = "Import failed. No records were imported. Check the file and try again."
                        } finally {
                            isProcessing = false
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = (inputText.isNotBlank() || selectedBackupPath != null) && !isProcessing
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
