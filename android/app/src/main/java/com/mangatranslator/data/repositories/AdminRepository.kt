package com.mangatranslator.data.repositories

import com.mangatranslator.core.network.SupabaseApi
import com.mangatranslator.data.models.AdminStats
import com.mangatranslator.data.models.AdminUserDetail
import com.mangatranslator.data.models.AdminUsersResponse
import com.mangatranslator.data.models.AuditLogsResponse
import com.mangatranslator.data.models.CreatedCodesResponse
import com.mangatranslator.data.models.GiftCodesResponse
import com.mangatranslator.data.models.OkResponse
import com.mangatranslator.data.models.RedeemResponse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GiftCodeRepository @Inject constructor(private val api: SupabaseApi) {
    suspend fun redeem(code: String): RedeemResponse =
        api.function("gift-codes-redeem", RedeemResponse.serializer(), buildJsonObject { put("code", code) })
}

/**
 * Admin operations. The server re-checks the admin role on every call; the
 * app only decides whether to show the entry point.
 */
@Singleton
class AdminRepository @Inject constructor(private val api: SupabaseApi) {

    suspend fun activate(code: String) {
        api.function("admin-activate", OkResponse.serializer(), buildJsonObject { put("code", code) })
    }

    suspend fun stats(): AdminStats = call("stats", AdminStats.serializer())

    suspend fun searchUsers(query: String, offset: Int = 0) = call("users_search", AdminUsersResponse.serializer()) {
        put("query", query)
        put("limit", 30)
        put("offset", offset)
    }.users

    suspend fun userDetail(userId: String) = call("user_detail", AdminUserDetail.serializer()) { put("user_id", userId) }

    suspend fun createCodes(
        plan: String,
        durationDays: Int,
        quantity: Int,
        validForDays: Int?,
        maxUses: Int,
        notes: String,
    ): CreatedCodesResponse = call("gift_codes_create", CreatedCodesResponse.serializer()) {
        put("plan", plan)
        put("duration_days", durationDays)
        put("quantity", quantity)
        put("max_redemptions", maxUses.coerceIn(1, 100_000))
        if (notes.isNotBlank()) put("notes", notes)
        validForDays?.let { put("code_expires_at", Instant.now().plus(it.toLong(), ChronoUnit.DAYS).toString()) }
    }

    suspend fun listCodes(plan: String?, status: String?, offset: Int = 0) = call("gift_codes_list", GiftCodesResponse.serializer()) {
        plan?.let { put("plan", it) }
        status?.let { put("status", it) }
        put("limit", 50)
        put("offset", offset)
    }

    suspend fun revokeCode(id: String) {
        call("gift_code_revoke", OkResponse.serializer()) { put("code_id", id) }
    }

    suspend fun auditLogs(offset: Int = 0) = call("audit_logs", AuditLogsResponse.serializer()) {
        put("limit", 50)
        put("offset", offset)
    }.logs

    private suspend fun <T> call(
        action: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ): T {
        val body: JsonObject = buildJsonObject {
            put("action", action)
            extra()
        }
        return api.function("admin", serializer, body)
    }
}
