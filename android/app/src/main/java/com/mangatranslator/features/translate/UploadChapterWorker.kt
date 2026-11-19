package com.mangatranslator.features.translate

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mangatranslator.core.common.AppException
import com.mangatranslator.core.common.asAppException
import com.mangatranslator.core.database.DraftDao
import com.mangatranslator.data.repositories.TranslationRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Background chapter submission:
 *   1. jobs-create (idempotent: the draft id is the idempotency key)
 *   2. upload every page that isn't uploaded yet (resumable)
 *   3. jobs-start (the server counts the chapter once and queues it)
 * Network problems => retry with backoff. Business errors (daily limit, …)
 * => stop and report the error code to the UI.
 */
@HiltWorker
class UploadChapterWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repo: TranslationRepository,
    private val dao: DraftDao,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val draftId = inputData.getString(KEY_DRAFT) ?: return Result.failure()
        val draft = dao.draft(draftId) ?: return Result.failure()
        dao.updateDraft(draft.copy(state = "submitting", errorCode = null))

        return try {
            val pages = dao.pages(draftId)
            val created = repo.createJob(draft, pages)
            val jobId = created.job.id
            dao.updateDraft(draft.copy(state = "submitting", serverJobId = jobId, errorCode = null))

            val paths = created.pages.associate { it.pageIndex to it.originalPath }
            var done = pages.count { it.uploaded }
            setProgress(workDataOf(KEY_UPLOADED to done, KEY_TOTAL to pages.size))
            for (page in pages) {
                if (page.uploaded) continue
                val path = paths[page.position] ?: throw AppException("upload_mismatch")
                repo.uploadPage(path, page)
                done++
                setProgress(workDataOf(KEY_UPLOADED to done, KEY_TOTAL to pages.size))
            }

            repo.startJob(jobId)
            dao.updateDraft(draft.copy(state = "submitted", serverJobId = jobId, errorCode = null))
            Result.success(workDataOf(KEY_JOB to jobId))
        } catch (e: Exception) {
            val err = e.asAppException()
            val retryable = err.isNetwork || err.httpStatus >= 500 && err.code != "provider_not_configured" || err.code == "rate_limited"
            if (retryable && runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                dao.draft(draftId)?.let { dao.updateDraft(it.copy(state = "failed", errorCode = err.code)) }
                Result.failure(workDataOf(KEY_ERROR to err.code, KEY_ERROR_EXTRA to (err.extras["max_pages"] ?: "")))
            }
        }
    }

    companion object {
        const val KEY_DRAFT = "draft_id"
        const val KEY_JOB = "job_id"
        const val KEY_ERROR = "error"
        const val KEY_ERROR_EXTRA = "error_extra"
        const val KEY_UPLOADED = "uploaded"
        const val KEY_TOTAL = "total"
        private const val MAX_ATTEMPTS = 8
    }
}
