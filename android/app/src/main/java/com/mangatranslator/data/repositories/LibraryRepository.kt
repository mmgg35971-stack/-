package com.mangatranslator.data.repositories

import com.mangatranslator.core.network.SupabaseApi
import com.mangatranslator.core.security.SessionStore
import com.mangatranslator.data.models.HistoryItem
import com.mangatranslator.data.models.LibraryWork
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepository @Inject constructor(
    private val api: SupabaseApi,
    private val sessionStore: SessionStore,
) {
    suspend fun works(): List<LibraryWork> = api.select(
        "user_library", LibraryWork.serializer(),
        mapOf("select" to "*,translation_jobs(count)", "order" to "updated_at.desc", "limit" to "500"),
    )

    suspend fun create(title: String, type: String): LibraryWork {
        val uid = requireNotNull(sessionStore.session.value?.userId)
        return api.insert("user_library", buildJsonObject {
            put("user_id", uid)
            put("title", title.trim().take(200))
            put("content_type", type)
        }, LibraryWork.serializer())
    }

    suspend fun setFavorite(id: String, favorite: Boolean) =
        api.update("user_library", mapOf("id" to "eq.$id"), buildJsonObject { put("is_favorite", favorite) })

    suspend fun delete(id: String) = api.delete("user_library", mapOf("id" to "eq.$id"))

    suspend fun recordRead(workId: String, jobId: String, page: Int) =
        api.update("user_library", mapOf("id" to "eq.$workId"), buildJsonObject {
            put("last_read_job_id", jobId)
            put("last_read_page", page)
            put("last_read_at", Instant.now().toString())
        })

    suspend fun chapters(workId: String): List<HistoryItem> = api.select(
        "translation_history", HistoryItem.serializer(),
        mapOf("work_id" to "eq.$workId", "select" to "*", "order" to "chapter_number.asc.nullslast,created_at.asc"),
    )
}

@Singleton
class HistoryRepository @Inject constructor(private val api: SupabaseApi) {
    suspend fun list(query: String = "", limit: Int = 100): List<HistoryItem> {
        val params = mutableMapOf("select" to "*", "order" to "created_at.desc", "limit" to "$limit")
        val q = query.trim().replace(Regex("[,()*]"), " ")
        if (q.isNotEmpty()) params["or"] = "(chapter_title.ilike.*$q*,work_title.ilike.*$q*)"
        return api.select("translation_history", HistoryItem.serializer(), params)
    }

    /** Deletes the record first (RLS refuses running jobs), then the chapter's images. */
    suspend fun delete(jobId: String) {
        val pages = api.select(
            "translation_pages", com.mangatranslator.data.models.PageRow.serializer(),
            mapOf("job_id" to "eq.$jobId", "select" to "id,page_index,original_path,rendered_path,status"),
        )
        api.delete("translation_jobs", mapOf("id" to "eq.$jobId"))
        api.removeObjects(pages.flatMap { listOfNotNull(it.originalPath, it.renderedPath) })
    }
}
