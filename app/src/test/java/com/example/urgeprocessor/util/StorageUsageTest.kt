package com.example.urgeprocessor.util

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageUsageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun countsDatabaseSidecarsAndAllAudioWithoutUnrelatedFiles() {
        val root = temporary.root
        val database = File(root, "urge_db").apply { writeBytes(ByteArray(100)) }
        File(root, "urge_db-wal").writeBytes(ByteArray(20))
        File(root, "urge_db-shm").writeBytes(ByteArray(30))
        File(root, "urge_db-journal").writeBytes(ByteArray(40))
        File(root, "another_db").writeBytes(ByteArray(500))
        val recordings = temporary.newFolder("recordings")
        File(recordings, "saved.m4a").writeBytes(ByteArray(200))
        File(recordings, "unfinished.m4a").writeBytes(ByteArray(50))
        val usage = readStorageUsage(database, recordings)
        assertEquals(190L, usage.databaseBytes)
        assertEquals(250L, usage.recordingBytes)
        assertEquals(440L, usage.totalBytes)
        File(recordings, "saved.m4a").delete()
        assertEquals(240L, readStorageUsage(database, recordings).totalBytes)
    }

    @Test fun missingStorageIsZeroAndDoesNotCreateDirectories() {
        val recordings = File(temporary.root, "recordings")
        assertEquals(StorageUsage(0, 0), readStorageUsage(File(temporary.root, "urge_db"), recordings))
        org.junit.Assert.assertFalse(recordings.exists())
    }
}
