package com.mangatranslator.core.common

import androidx.annotation.StringRes
import com.mangatranslator.R

/**
 * Every failure the UI can show. `code` is the stable error code returned by
 * the backend (see docs/api/API.md); `extras` carries values such as max_pages.
 */
class AppException(
    val code: String,
    val httpStatus: Int = 0,
    override val message: String = code,
    val extras: Map<String, String> = emptyMap(),
) : Exception(message) {
    val isNetwork get() = code == NETWORK
    val isAuth get() = code == "unauthenticated" || code == "session_expired"

    companion object {
        const val NETWORK = "network_error"
        const val UNKNOWN = "internal_error"
    }
}

/** Maps backend error codes to localized messages. Unknown codes fall back to a generic message. */
@StringRes
fun AppException.messageRes(): Int = when (code) {
    AppException.NETWORK -> R.string.error_network
    "unauthenticated", "session_expired" -> R.string.error_session_expired
    "rate_limited" -> R.string.error_rate_limited
    "forbidden" -> R.string.error_forbidden
    "provider_not_configured" -> R.string.error_provider_not_configured
    "server_not_configured" -> R.string.error_server_not_configured
    "quality_not_in_plan" -> R.string.error_quality_not_in_plan
    "uploads_incomplete" -> R.string.error_uploads_incomplete
    "account_inactive" -> R.string.error_account_inactive
    "daily_limit_reached" -> R.string.limit_reached_body
    "fair_use_review" -> R.string.fair_use_body
    "invalid_code" -> R.string.redeem_invalid
    "code_already_used" -> R.string.redeem_used
    "code_expired" -> R.string.redeem_expired
    "code_revoked" -> R.string.redeem_revoked
    "higher_plan_active" -> R.string.redeem_higher_plan
    else -> R.string.error_generic
}

/** Simple UI state wrapper used by every screen. */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Ready<T>(val data: T) : Loadable<T>
    data class Failed(val error: AppException) : Loadable<Nothing>
}

suspend inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: AppException) {
    Result.failure(e)
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: java.io.IOException) {
    Result.failure(AppException(AppException.NETWORK, message = e.message ?: "io"))
} catch (e: Exception) {
    Result.failure(AppException(AppException.UNKNOWN, message = e.message ?: "unknown"))
}

fun Throwable.asAppException(): AppException = when (this) {
    is AppException -> this
    is java.io.IOException -> AppException(AppException.NETWORK, message = message ?: "io")
    else -> AppException(AppException.UNKNOWN, message = message ?: "unknown")
}
