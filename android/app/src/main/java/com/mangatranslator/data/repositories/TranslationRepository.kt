package com.mangatranslator.data.repositories

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.mangatranslator.core.database.DraftDao
import com.mangatranslator.core.database.DraftEntity
import com.mangatranslator.core.database.DraftPageEntity
import com.mangatranslator.core.network.SupabaseApi
import com.mangatranslator.core.security.SessionStore
import com.mangatranslator.data.models.CreateJobResponse
import com.mangatranslator.data.models.JobRow
import com.mangatranslator.data.models.OkResponse
import com.mangatranslator.data.models.PageRow
import com.mangatranslator.data.models.RetranslateResponse
import com.mangatranslator.data.models.SegmentRow
import com.mangatranslator.data.models.StartJobResponse
import com.mangatranslator.features.translate.PageImporter
import com.mangatranslator.features.translate.UploadChapterWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TranslationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: SupabaseApi,
    private val dao: DraftDao,
    private val importer: PageImporter,
    private val sessionStore: SessionStore,
) {
    // ---------------- local drafts (project saving) ----------------
    fun openDrafts(): Flow<List<DraftEntity>> = dao.openDrafts()
    fun observeDraft(id: String) = dao.observeDraft(id)
    fun observePages(id: String) = dao.observePages(id)

    suspend fun newDraft(quality: String): DraftEntity {
        val d = DraftEntity(
            id = UUID.randomUUID().toString(),
            chapterTitle = "",
            chapterNumber = null,
            workId = null,
            quality = quality,
            createdAt = System.currentTimeMillis(),
            state = "editing",
        )
        dao.upsertDraft(d)
        return d
    }

    suspend fun updateDraft(d: DraftEntity) = dao.updateDraft(d)

    suspend fun addImages(draftId: String, uris: List<Uri>): Int {
        val existing = dao.pages(draftId)
        val r = importer.importImages(draftId, uris, existing.size)
        dao.replacePages(draftId, existing + r.pages)
        return r.skipped
    }

    suspend fun addPdf(draftId: String, uri: Uri) {
        val existing = dao.pages(draftId)
        val r = importer.importPdf(draftId, uri, existing.size)
        dao.replacePages(draftId, existing + r.pages)
    }

    /** Persists a new order (move / delete / sort) and renumbers positions. */
    suspend fun setPageOrder(draftId: String, ordered: List<DraftPageEntity>) {
        val removed = dao.pages(draftId).map { it.localPath }.toSet() - ordered.map { it.localPath }.toSet()
        removed.forEach { File(it).delete() }
        dao.replacePages(draftId, ordered.mapIndexed { i, p -> p.copy(position = i) })
    }

    suspend fun discardDraft(draftId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(draftId))
        dao.deleteDraft(draftId)
        dao.deletePages(draftId)
        importer.deleteDraftFiles(draftId)
    }

    /** Hands the chapter to WorkManager: uploads survive app close and network loss. */
    fun submit(draftId: String) {
        val req = OneTimeWorkRequestBuilder<UploadChapterWorker>()
            .setInputData(workDataOf(UploadChapterWorker.KEY_DRAFT to draftId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(draftId), ExistingWorkPolicy.KEEP, req)
    }

    fun observeSubmission(draftId: String): Flow<List<WorkInfo>> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(workName(draftId))

    private fun workName(draftId: String) = "upload-$draftId"

    // ---------------- server steps (used by the worker) ----------------
    suspend fun createJob(draft: DraftEntity, pages: List<DraftPageEntity>): CreateJobResponse =
        api.function("jobs-create", CreateJobResponse.serializer(), buildJsonObject {
            put("idempotency_key", draft.id)
            put("chapter_title", draft.chapterTitle)
            draft.chapterNumber?.let { put("chapter_number", it) }
            draft.workId?.let { put("work_id", it) }
            put("quality", draft.quality)
            putJsonArray("pages") {
                pages.forEach { p ->
                    addJsonObject {
                        put("mime_type", p.mimeType)
                        put("byte_size", File(p.localPath).length())
                    }
                }
            }
        })

    suspend fun uploadPage(storagePath: String, page: DraftPageEntity) {
        api.upload(storagePath, File(page.localPath).readBytes(), page.mimeType)
        dao.markUploaded(page.draftId, page.position)
    }

    suspend fun startJob(jobId: String): StartJobResponse =
        api.function("jobs-start", StartJobResponse.serializer(), buildJsonObject { put("job_id", jobId) })

    // ---------------- job status / reader ----------------
    suspend fun job(jobId: String): JobRow? = api.select(
        "translation_jobs", JobRow.serializer(),
        mapOf("id" to "eq.$jobId", "select" to "id,work_id,chapter_title,page_count,status,pages_completed,pages_failed,usage_counted,usage_refunded,error_code"),
    ).firstOrNull()

    suspend fun pages(jobId: String): List<PageRow> = api.select(
        "translation_pages", PageRow.serializer(),
        mapOf("job_id" to "eq.$jobId", "select" to "id,page_index,original_path,rendered_path,width,height,status,error_code", "order" to "page_index.asc"),
    )

    suspend fun segments(jobId: String): List<SegmentRow> = api.select(
        "translation_segments", SegmentRow.serializer(),
        mapOf("job_id" to "eq.$jobId", "select" to "*", "order" to "reading_order.asc", "limit" to "10000"),
    )

    fun imageUrl(path: String) = api.objectUrl(path)

    suspend fun saveSegmentEdit(segmentId: String, text: String) =
        api.update("translation_segments", mapOf("id" to "eq.$segmentId"), buildJsonObject { put("user_edited_text", text) })

    /** Re-translates one bubble on the server (story context + neighbouring lines). Does not count a chapter. */
    suspend fun retranslateSegment(segmentId: String, hint: String?, replaceEdit: Boolean): RetranslateResponse =
        api.function("segments-retranslate", RetranslateResponse.serializer(), buildJsonObject {
            put("segment_id", segmentId)
            hint?.trim()?.takeIf { it.isNotEmpty() }?.let { put("hint", it.take(300)) }
            put("replace_edit", replaceEdit)
        })

    suspend fun reportSegment(segmentId: String, reason: String) {
        val uid = requireNotNull(sessionStore.session.value?.userId)
        api.insert("translation_reports", buildJsonObject {
            put("user_id", uid)
            put("segment_id", segmentId)
            put("reason", reason.take(500))
        }, JsonObject.serializer())
        api.update("translation_segments", mapOf("id" to "eq.$segmentId"), buildJsonObject { put("reported_bad", true) })
    }

    suspend fun addGlossary(workId: String?, source: String, target: String, type: String = "term") {
        val uid = requireNotNull(sessionStore.session.value?.userId)
        api.insert("glossary_entries", buildJsonObject {
            put("user_id", uid)
            workId?.let { put("work_id", it) }
            put("source_term", source.trim().take(120))
            put("target_term", target.trim().take(120))
            put("term_type", type)
        }, JsonObject.serializer())
    }

    suspend fun retryPage(pageId: String) {
        api.function("jobs-retry-page", OkResponse.serializer(), buildJsonObject { put("page_id", pageId) })
    }
}
