package com.example.urgeprocessor.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

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
