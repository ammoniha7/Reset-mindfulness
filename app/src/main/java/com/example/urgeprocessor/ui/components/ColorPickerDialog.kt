package com.example.urgeprocessor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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
