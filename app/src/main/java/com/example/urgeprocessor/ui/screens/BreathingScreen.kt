package com.example.urgeprocessor.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.urgeprocessor.util.triggerVibration
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BreathingScreen() {
    val context = LocalContext.current
    var isRunning by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf("Ready?") }
    var targetScale by remember { mutableFloatStateOf(0.6f) }
    var selectedCycles by remember { mutableStateOf(5) }
    var cyclesLeft by remember { mutableStateOf(5) }
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
