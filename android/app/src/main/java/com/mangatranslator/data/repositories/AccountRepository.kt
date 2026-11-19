package com.mangatranslator.data.repositories

import com.mangatranslator.core.network.AppJson
import com.mangatranslator.core.network.SupabaseApi
import com.mangatranslator.core.security.SessionStore
import com.mangatranslator.data.models.AppNotification
import com.mangatranslator.data.models.MeResponse
import com.mangatranslator.data.models.OkResponse
import com.mangatranslator.data.models.Plan
import com.mangatranslator.data.models.UserSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepository @Inject constructor(
    private val api: SupabaseApi,
    private val sessionStore: SessionStore,
) {
    /** Last value fetched from the server, shared across screens (never persisted). */
    private val _me = MutableStateFlow<MeResponse?>(null)
    val me: StateFlow<MeResponse?> = _me

    private val _settings = MutableStateFlow(UserSettings())
    val settings: StateFlow<UserSettings> = _settings

    suspend fun refreshMe(): MeResponse = api.function("me", MeResponse.serializer()).also { _me.value = it }

    suspend fun plans(): List<Plan> =
        api.select("subscription_plans", Plan.serializer(), mapOf("select" to "*", "order" to "rank.asc"))

    suspend fun loadSettings(): UserSettings {
        val uid = sessionStore.session.value?.userId ?: return _settings.value
        val rows = api.select("user_settings", UserSettings.serializer(), mapOf("user_id" to "eq.$uid", "select" to "*"))
        return (rows.firstOrNull() ?: UserSettings()).also { _settings.value = it }
    }

    /** Optimistic update; reverts if the server rejects it. */
    suspend fun updateSettings(transform: (UserSettings) -> UserSettings) {
        val uid = sessionStore.session.value?.userId ?: return
        val before = _settings.value
        val after = transform(before)
        _settings.value = after
        try {
            val all = AppJson.encodeToJsonElement(UserSettings.serializer(), after).jsonObject
            val old = AppJson.encodeToJsonElement(UserSettings.serializer(), before).jsonObject
            val diff = JsonObject(all.filter { (k, v) -> old[k] != v })
            if (diff.isNotEmpty()) api.update("user_settings", mapOf("user_id" to "eq.$uid"), diff)
        } catch (e: Exception) {
            _settings.value = before
            throw e
        }
    }

    suspend fun updateDisplayName(name: String) {
        val uid = sessionStore.session.value?.userId ?: return
        api.update("user_profiles", mapOf("id" to "eq.$uid"), buildJsonObject { put("display_name", name.trim().take(60)) })
        refreshMe()
    }

    suspend fun deleteAccount() {
        api.function("account-delete", OkResponse.serializer(), buildJsonObject { put("confirm", "DELETE") })
        sessionStore.clear()
        _me.value = null
    }

    suspend fun notifications(): List<AppNotification> = api.select(
        "notifications", AppNotification.serializer(),
        mapOf("select" to "*", "order" to "created_at.desc", "limit" to "50"),
    )

    suspend fun markAllRead() {
        api.update("notifications", mapOf("read_at" to "is.null"), JsonObject(mapOf("read_at" to JsonPrimitive(Instant.now().toString()))))
    }

    fun clear() {
        _me.value = null
        _settings.value = UserSettings()
    }
}
