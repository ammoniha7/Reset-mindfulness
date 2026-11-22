package com.example.urgeprocessor.data

import androidx.room.Index
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "urge_entries")
data class UrgeEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    var category: String = "",
    var specificEmotion: String = "",
    var emotionColor: String = "",
    var feltLoved: String = "",
    var stressReason: String = "",
    var excitementWeek: String = ""
)

@Entity(tableName = "quotes")
data class Quote(@PrimaryKey(autoGenerate = true) val id: Int = 0, val text: String)

@Entity(tableName = "standard_journal", indices = [Index(value = ["sourceDraftId"], unique = true)])
data class StandardJournalEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val content: String,
    val sourceDraftId: String? = null
)

// Capture time and the assigned journal day are separate so travel cannot move a recording.
@Entity(tableName = "recordings", indices = [Index(value = ["journalDate"])])
data class RecordingEntry(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val journalDate: String,
    val zoneId: String,
    val durationMs: Long,
    val title: String = "",
    val fileName: String
)
