package com.example.urgeprocessor.util

import java.io.File

data class StorageUsage(val databaseBytes: Long, val recordingBytes: Long) {
    val totalBytes: Long get() = databaseBytes + recordingBytes
}

fun readStorageUsage(database: File, recordings: File): StorageUsage {
    // SQLite can keep recent entries in the write-ahead log before checkpointing.
    val databaseBytes = listOf("", "-wal", "-shm", "-journal").sumOf { suffix ->
        File(database.path + suffix).let { if (it.isFile) it.length() else 0L }
    }
    // Include unfinished recordings too: they occupy storage even before saving.
    val recordingBytes = if (recordings.isDirectory) {
        recordings.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } else 0L
    return StorageUsage(databaseBytes, recordingBytes)
}

fun formatStorageSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes.toDouble() / (1024L * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format("%.2f MB", bytes.toDouble() / (1024L * 1024))
    bytes >= 1024 -> String.format("%.1f KB", bytes.toDouble() / 1024)
    else -> "$bytes Bytes"
}
