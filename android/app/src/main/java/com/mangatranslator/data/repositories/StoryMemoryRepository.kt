package com.mangatranslator.data.repositories

import com.mangatranslator.core.network.AppJson
import com.mangatranslator.core.network.SupabaseApi
import com.mangatranslator.core.security.SessionStore
import com.mangatranslator.data.models.GlossaryEntry
import com.mangatranslator.data.models.StoryCharacter
import com.mangatranslator.data.models.WorkContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** Glossary term types, in the order the database enum defines them. */
val GLOSSARY_TYPES = listOf("character", "skill", "weapon", "place", "kingdom", "clan", "rank", "ability", "military", "magic", "term")

/**
 * Story memory (work_context) and the glossary of one work. RLS limits every
 * call to the signed-in user's own rows; suggestions are written only by the worker.
 */
@Singleton
class StoryMemoryRepository @Inject constructor(
    private val api: SupabaseApi,
    private val sessionStore: SessionStore,
) {
    suspend fun context(workId: String): WorkContext? = api.select(
        "work_context", WorkContext.serializer(), mapOf("work_id" to "eq.$workId", "select" to "*"),
    ).firstOrNull()

    /** Saves the whole memory. Creates the row if no chapter has been translated yet. */
    suspend fun saveContext(workId: String, exists: Boolean, summary: String, characters: List<StoryCharacter>, style: String, frozen: Boolean) {
        val chars = characters
            .filter { it.name.isNotBlank() }
            .take(60)
            .map { StoryCharacter(it.name.trim().take(80), it.arabic.trim().take(80), it.notes.trim().take(300)) }
        val body = buildJsonObject {
            put("story_summary", summary.take(4000))
            put("characters", AppJson.encodeToJsonElement(ListSerializer(StoryCharacter.serializer()), chars))
            put("dialogue_style", style.take(1000))
            put("memory_frozen", frozen)
        }
        if (exists) {
            api.update("work_context", mapOf("work_id" to "eq.$workId"), body)
        } else {
            val uid = requireNotNull(sessionStore.session.value?.userId)
            api.insert("work_context", buildJsonObject {
                put("work_id", workId)
                put("user_id", uid)
                body.forEach { (k, v) -> put(k, v) }
            }, WorkContext.serializer())
        }
    }

    /** This work's terms plus the user's general terms; suggestions first. */
    suspend fun glossary(workId: String): List<GlossaryEntry> = api.select(
        "glossary_entries", GlossaryEntry.serializer(),
        mapOf(
            "select" to "id,work_id,source_term,target_term,term_type,status",
            "or" to "(work_id.eq.$workId,work_id.is.null)",
            "order" to "status.desc,source_term.asc",
            "limit" to "1000",
        ),
    )

    suspend fun addTerm(workId: String, source: String, target: String, type: String): GlossaryEntry {
        val uid = requireNotNull(sessionStore.session.value?.userId)
        return api.insert("glossary_entries", buildJsonObject {
            put("user_id", uid)
            put("work_id", workId)
            put("source_term", source.trim().take(120))
            put("target_term", target.trim().take(120))
            put("term_type", type)
        }, GlossaryEntry.serializer())
    }

    /** Edits a term; editing a suggestion also confirms it. */
    suspend fun updateTerm(id: String, source: String, target: String, type: String) =
        api.update("glossary_entries", mapOf("id" to "eq.$id"), buildJsonObject {
            put("source_term", source.trim().take(120))
            put("target_term", target.trim().take(120))
            put("term_type", type)
            put("status", "confirmed")
        })

    suspend fun confirmTerm(id: String) =
        api.update("glossary_entries", mapOf("id" to "eq.$id"), buildJsonObject { put("status", "confirmed") })

    suspend fun deleteTerm(id: String) = api.delete("glossary_entries", mapOf("id" to "eq.$id"))
}
