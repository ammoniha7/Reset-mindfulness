package com.example.urgeprocessor.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.urgeprocessor.data.AppDatabase
import com.example.urgeprocessor.data.Quote
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

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
        Text("\"$dailyQuote\"", fontStyle = FontStyle.Italic, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
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
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences)
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
                            Toast.makeText(context, "Added ${cleanQuotes.size} quotes!", Toast.LENGTH_SHORT).show()
                        }
                        text = ""
                        showDialog = false
                    }
                }) { Text("Save All") }
            }
        )
    }
}
