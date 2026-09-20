package com.example.urgeprocessor.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.OnConflictStrategy
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface UrgeDao {
    @Insert suspend fun insert(entry: UrgeEntry): Long
    @Query("SELECT * FROM urge_entries ORDER BY timestamp DESC")
    fun getAllEntries(): Flow<List<UrgeEntry>>
    @Delete suspend fun deleteEntry(entry: UrgeEntry): Int

    @Insert suspend fun insertQuote(quote: Quote): Long
    @Query("SELECT * FROM quotes")
    fun getAllQuotes(): Flow<List<Quote>>
    @Delete suspend fun deleteQuote(quote: Quote): Int

    // A retry after process death must not save the same draft twice.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertJournalDraft(entry: StandardJournalEntry): Long

    @Insert suspend fun insertStandardJournal(entry: StandardJournalEntry): Long
    @Query("SELECT * FROM standard_journal ORDER BY timestamp DESC")
    fun getAllStandardJournals(): Flow<List<StandardJournalEntry>>
    @Update suspend fun updateStandardJournal(entry: StandardJournalEntry): Int
    @Delete suspend fun deleteStandardJournal(entry: StandardJournalEntry): Int
}
