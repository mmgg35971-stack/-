package com.mangatranslator.core.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.mangatranslator.BuildConfig
import com.mangatranslator.core.network.SupabaseApi
import com.mangatranslator.data.models.OkResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Optional FCM push. Firebase is initialised from BuildConfig values instead
 * of google-services.json, so the app builds and runs without Firebase; push
 * is simply off until the four FIREBASE_* values are provided.
 */
@Singleton
class PushManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: SupabaseApi,
) {
    val isConfigured: Boolean
        get() = BuildConfig.FIREBASE_APP_ID.isNotBlank() && BuildConfig.FIREBASE_API_KEY.isNotBlank() &&
            BuildConfig.FIREBASE_PROJECT_ID.isNotBlank() && BuildConfig.FIREBASE_SENDER_ID.isNotBlank()

    fun init() {
        if (!isConfigured || FirebaseApp.getApps(context).isNotEmpty()) return
        FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .build(),
        )
    }

    private suspend fun currentToken(): String? {
        if (!isConfigured) return null
        init()
        return suspendCancellableCoroutine { cont ->
            FirebaseMessaging.getInstance().token.addOnCompleteListener { t ->
                cont.resume(if (t.isSuccessful) t.result else null)
            }
        }
    }

    /** Call after sign-in and whenever FCM rotates the token. Failures are ignored (push is best-effort). */
    suspend fun register(token: String? = null) {
        val t = token ?: currentToken() ?: return
        runCatching {
            api.function("devices-register", OkResponse.serializer(), buildJsonObject {
                put("token", t)
                put("app_language", if (Locale.getDefault().language == "en") "en" else "ar")
            })
        }
    }

    /** Call before sign-out so the next account on this phone does not get our pushes. */
    suspend fun unregister() {
        val t = currentToken() ?: return
        runCatching {
            api.function("devices-register", OkResponse.serializer(), buildJsonObject {
                put("token", t)
                put("remove", true)
            })
        }
    }
}
