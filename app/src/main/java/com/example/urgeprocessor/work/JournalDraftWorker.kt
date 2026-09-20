package com.example.urgeprocessor.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.urgeprocessor.data.DraftSaveResult
import com.example.urgeprocessor.data.JournalDraft
import com.example.urgeprocessor.data.JournalDraftStore
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

interface JournalDraftScheduler {
    fun schedule(draft: JournalDraft, now: Instant)
    fun cancel(id: String)
}

class WorkJournalDraftScheduler(context: Context) : JournalDraftScheduler {
    private val manager = WorkManager.getInstance(context)
    override fun schedule(draft: JournalDraft, now: Instant) {
        val request = OneTimeWorkRequestBuilder<JournalDraftWorker>()
            .setInputData(workDataOf("draftId" to draft.id))
            .setInitialDelay(Duration.between(now, draft.deadline).toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .build()
        manager.enqueueUniqueWork("journal-draft-${draft.id}", ExistingWorkPolicy.KEEP, request)
    }
    override fun cancel(id: String) { manager.cancelUniqueWork("journal-draft-$id") }
}

class JournalDraftWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("draftId") ?: return Result.failure()
        return when (JournalDraftStore.get(applicationContext).saveIfDue(id)) {
            DraftSaveResult.SAVED, DraftSaveResult.EMPTY -> Result.success()
            DraftSaveResult.NOT_DUE, DraftSaveResult.BUSY, DraftSaveResult.FAILED -> Result.retry()
        }
    }
}
