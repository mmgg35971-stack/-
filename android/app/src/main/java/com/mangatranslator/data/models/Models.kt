package com.mangatranslator.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Wire models that mirror the backend exactly (docs/database/SCHEMA.md, docs/api/API.md).
// They are immutable and used directly by the UI layer.

@Serializable
data class PlanFeatures(
    @SerialName("translation_quality") val translationQuality: List<String> = listOf("standard"),
    val glossary: Boolean = true,
    @SerialName("context_manager") val contextManager: Boolean = false,
    @SerialName("max_pages_per_chapter") val maxPagesPerChapter: Int = 60,
    @SerialName("advanced_rendering") val advancedRendering: Boolean = false,
    @SerialName("batch_export") val batchExport: Boolean = false,
)

@Serializable
data class Entitlements(
    @SerialName("plan_id") val planId: String,
    @SerialName("plan_name") val planName: String,
    @SerialName("plan_source") val planSource: String = "free",
    @SerialName("plan_expires_at") val planExpiresAt: String? = null,
    @SerialName("daily_limit") val dailyLimit: Int? = null,
    @SerialName("used_today") val usedToday: Int = 0,
    @SerialName("remaining_today") val remainingToday: Int? = null,
    @SerialName("usage_date") val usageDate: String,
    @SerialName("resets_at") val resetsAt: String? = null,
    @SerialName("queue_priority") val queuePriority: Int = 0,
    val features: PlanFeatures = PlanFeatures(),
    @SerialName("is_admin") val isAdmin: Boolean = false,
) {
    val isUnlimited get() = dailyLimit == null
    val progress: Float get() = if (dailyLimit == null || dailyLimit == 0) 0f else (usedToday.toFloat() / dailyLimit).coerceIn(0f, 1f)
}

@Serializable
data class Profile(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
    val email: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val status: String = "active",
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class MeResponse(val profile: Profile, val entitlements: Entitlements)

@Serializable
data class Plan(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val rank: Int,
    @SerialName("daily_chapter_limit") val dailyChapterLimit: Int? = null,
    @SerialName("queue_priority") val queuePriority: Int,
    val features: PlanFeatures = PlanFeatures(),
    @SerialName("play_product_id") val playProductId: String? = null,
)

@Serializable
data class HistoryItem(
    val id: String,
    @SerialName("work_id") val workId: String? = null,
    @SerialName("work_title") val workTitle: String? = null,
    @SerialName("cover_path") val coverPath: String? = null,
    @SerialName("chapter_title") val chapterTitle: String,
    @SerialName("chapter_number") val chapterNumber: Double? = null,
    @SerialName("page_count") val pageCount: Int,
    val status: String,
    @SerialName("pages_completed") val pagesCompleted: Int = 0,
    @SerialName("pages_failed") val pagesFailed: Int = 0,
    @SerialName("processing_ms") val processingMs: Int? = null,
    @SerialName("is_saved") val isSaved: Boolean = false,
    @SerialName("created_at") val createdAt: String,
    @SerialName("completed_at") val completedAt: String? = null,
)

@Serializable
data class CountOnly(val count: Int)

@Serializable
data class LibraryWork(
    val id: String,
    val title: String,
    @SerialName("content_type") val contentType: String = "manhwa",
    @SerialName("cover_path") val coverPath: String? = null,
    val category: String? = null,
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    @SerialName("last_read_job_id") val lastReadJobId: String? = null,
    @SerialName("last_read_page") val lastReadPage: Int = 0,
    @SerialName("last_read_at") val lastReadAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("translation_jobs") val jobs: List<CountOnly> = emptyList(),
) {
    val chapterCount get() = jobs.firstOrNull()?.count ?: 0
}

@Serializable
data class JobRow(
    val id: String,
    @SerialName("work_id") val workId: String? = null,
    @SerialName("chapter_title") val chapterTitle: String,
    @SerialName("page_count") val pageCount: Int,
    val status: String,
    @SerialName("pages_completed") val pagesCompleted: Int = 0,
    @SerialName("pages_failed") val pagesFailed: Int = 0,
    @SerialName("usage_counted") val usageCounted: Boolean = false,
    @SerialName("usage_refunded") val usageRefunded: Boolean = false,
    @SerialName("error_code") val errorCode: String? = null,
)

@Serializable
data class PageRow(
    val id: String,
    @SerialName("page_index") val pageIndex: Int,
    @SerialName("original_path") val originalPath: String,
    @SerialName("rendered_path") val renderedPath: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val status: String,
    @SerialName("error_code") val errorCode: String? = null,
)

@Serializable
data class SegmentRow(
    val id: String,
    @SerialName("page_id") val pageId: String,
    @SerialName("reading_order") val readingOrder: Int,
    val category: String,
    @SerialName("bbox_x") val x: Float,
    @SerialName("bbox_y") val y: Float,
    @SerialName("bbox_w") val w: Float,
    @SerialName("bbox_h") val h: Float,
    @SerialName("is_vertical") val isVertical: Boolean = false,
    @SerialName("original_text") val originalText: String,
    @SerialName("ocr_confidence") val confidence: Float? = null,
    @SerialName("needs_review") val needsReview: Boolean = false,
    @SerialName("translated_text") val translatedText: String? = null,
    @SerialName("user_edited_text") val userEditedText: String? = null,
    val style: SegmentStyle = SegmentStyle(),
) {
    /** User edits always win over the machine translation. */
    val displayText: String get() = userEditedText ?: translatedText ?: ""
}

@Serializable
data class SegmentStyle(
    @SerialName("font_scale") val fontScale: Float? = null,
    val color: String? = null,           // "#RRGGBB"
    val background: String? = null,      // "#RRGGBB" or "none"
    val align: String? = null,           // start | center | end
    @SerialName("offset_x") val offsetX: Float = 0f,
    @SerialName("offset_y") val offsetY: Float = 0f,
)

@Serializable
data class UserSettings(
    @SerialName("reading_direction") val readingDirection: String = "vertical",
    @SerialName("page_transition") val pageTransition: String = "scroll",
    @SerialName("reader_fullscreen") val readerFullscreen: Boolean = true,
    @SerialName("image_quality") val imageQuality: String = "high",
    @SerialName("reader_brightness") val readerBrightness: Float = 1f,
    @SerialName("arabic_font") val arabicFont: String = "cairo",
    @SerialName("font_scale") val fontScale: Float = 1f,
    @SerialName("translation_quality") val translationQuality: String = "standard",
    @SerialName("show_original_text") val showOriginalText: Boolean = false,
    @SerialName("keep_original_images") val keepOriginalImages: Boolean = true,
    @SerialName("use_glossary") val useGlossary: Boolean = true,
    @SerialName("app_language") val appLanguage: String = "ar",
    val theme: String = "dark",
    @SerialName("notifications_enabled") val notificationsEnabled: Boolean = true,
    @SerialName("allow_training_use") val allowTrainingUse: Boolean = false,
)

@Serializable
data class AppNotification(
    val id: String,
    val type: String,
    val title: String,
    val body: String,
    val data: JsonElement? = null,
    @SerialName("read_at") val readAt: String? = null,
    @SerialName("created_at") val createdAt: String,
)

// ---------------- Edge Function responses ----------------

@Serializable
data class CreatedJob(val id: String, val status: String, @SerialName("page_count") val pageCount: Int)

@Serializable
data class CreatedPage(
    val id: String,
    @SerialName("page_index") val pageIndex: Int,
    @SerialName("original_path") val originalPath: String,
    val status: String,
)

@Serializable
data class CreateJobResponse(val job: CreatedJob, val pages: List<CreatedPage>, val reused: Boolean = false)

@Serializable
data class StartJobResponse(
    val ok: Boolean,
    @SerialName("already_counted") val alreadyCounted: Boolean = false,
    val status: String? = null,
    val usage: Entitlements? = null,
)

@Serializable
data class RedeemResponse(
    val ok: Boolean,
    @SerialName("plan_id") val planId: String,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("duration_days") val durationDays: Int,
)

@Serializable
data class OkResponse(val ok: Boolean = true)

// ---------------- Admin ----------------

@Serializable
data class AdminStats(
    @SerialName("users_total") val usersTotal: Int,
    @SerialName("users_active_7d") val usersActive7d: Int,
    @SerialName("users_by_plan") val usersByPlan: Map<String, Int> = emptyMap(),
    @SerialName("chapters_translated_total") val chaptersTotal: Int,
    @SerialName("chapters_started_today") val chaptersToday: Int,
    @SerialName("jobs_failed_total") val jobsFailedTotal: Int,
    @SerialName("jobs_failed_today") val jobsFailedToday: Int = 0,
    @SerialName("gift_codes_total") val codesTotal: Int,
    @SerialName("gift_codes_used") val codesUsed: Int,
    @SerialName("gift_codes_remaining") val codesRemaining: Int,
    @SerialName("ai_cost_today_usd") val aiCostTodayUsd: Double = 0.0,
    @SerialName("ai_requests_failed_today") val aiFailedToday: Int = 0,
    @SerialName("ai_cost_alert") val aiCostAlert: Boolean = false,
    @SerialName("ai_cost_daily_limit_usd") val aiCostLimitUsd: Double = 0.0,
    @SerialName("generated_at") val generatedAt: String? = null,
)

@Serializable
data class AdminUser(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
    val email: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("plan_id") val planId: String,
    @SerialName("plan_expires_at") val planExpiresAt: String? = null,
    @SerialName("used_today") val usedToday: Int = 0,
    @SerialName("is_admin") val isAdmin: Boolean = false,
)

@Serializable
data class AdminUsersResponse(val users: List<AdminUser>)

@Serializable
data class SubscriptionRow(
    val id: String,
    @SerialName("plan_id") val planId: String,
    val source: String,
    val status: String,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class UsageDay(@SerialName("usage_date") val date: String, @SerialName("chapters_used") val used: Int)

@Serializable
data class AdminUserDetail(
    val profile: Profile,
    val entitlements: Entitlements,
    val subscriptions: List<SubscriptionRow> = emptyList(),
    @SerialName("usage_history") val usageHistory: List<UsageDay> = emptyList(),
)

@Serializable
data class RedemptionUser(val id: String, val email: String? = null, @SerialName("display_name") val displayName: String? = null)

@Serializable
data class CodeRedemption(@SerialName("redeemed_at") val redeemedAt: String, @SerialName("user_profiles") val user: RedemptionUser? = null)

@Serializable
data class GiftCodeRow(
    val id: String,
    @SerialName("batch_id") val batchId: String,
    @SerialName("code_hint") val codeHint: String,
    @SerialName("plan_id") val planId: String,
    @SerialName("duration_days") val durationDays: Int,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("max_redemptions") val maxRedemptions: Int,
    @SerialName("redemption_count") val redemptionCount: Int,
    val status: String,
    val notes: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("gift_code_redemptions") val redemptions: List<CodeRedemption> = emptyList(),
)

@Serializable
data class GiftCodesResponse(val codes: List<GiftCodeRow>, val total: Int? = null)

@Serializable
data class CreatedCodesResponse(
    @SerialName("batch_id") val batchId: String,
    val plan: String,
    @SerialName("duration_days") val durationDays: Int,
    @SerialName("code_expires_at") val codeExpiresAt: String? = null,
    val codes: List<String>,
)

@Serializable
data class AuditLog(
    val id: Long,
    @SerialName("actor_id") val actorId: String? = null,
    val action: String,
    @SerialName("target_type") val targetType: String? = null,
    @SerialName("target_id") val targetId: String? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class AuditLogsResponse(val logs: List<AuditLog>)
