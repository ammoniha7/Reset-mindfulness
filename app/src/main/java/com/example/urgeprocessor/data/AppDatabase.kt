package com.example.urgeprocessor.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [UrgeEntry::class, Quote::class, StandardJournalEntry::class, RecordingEntry::class], version = 7, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun urgeDao(): UrgeDao
    companion object {
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS recordings (id TEXT NOT NULL, timestamp INTEGER NOT NULL, journalDate TEXT NOT NULL, zoneId TEXT NOT NULL, durationMs INTEGER NOT NULL, title TEXT NOT NULL, fileName TEXT NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recordings_journalDate ON recordings(journalDate)")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE standard_journal ADD COLUMN sourceDraftId TEXT")
                db.execSQL("CREATE UNIQUE INDEX index_standard_journal_sourceDraftId ON standard_journal(sourceDraftId)")
            }
        }

        @Volatile private var INSTANCE: AppDatabase? = null
        internal fun buildDatabase(context: Context, name: String): AppDatabase =
            // Missing migrations must fail without deleting the user's existing records.
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
                .addMigrations(MIGRATION_5_6, MIGRATION_6_7).build()

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context, "urge_db").also { INSTANCE = it }
            }
        }
    }
}
