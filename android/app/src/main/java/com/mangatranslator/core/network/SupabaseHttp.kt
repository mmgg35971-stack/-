package com.mangatranslator.core.network

import com.mangatranslator.core.common.AppException
import com.mangatranslator.core.security.Session
import com.mangatranslator.core.security.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Authenticator
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = false
}

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

@Serializable
data class AuthResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("expires_at") val expiresAt: Long? = null,
    val user: AuthUser,
)

@Serializable
data class AuthUser(val id: String, val email: String? = null)

/**
 * Supabase Auth (GoTrue) + token lifecycle. Access tokens are refreshed
 * shortly before expiry, and once more on a 401, behind a mutex so parallel
 * requests never refresh twice.
 */
@Singleton
class AuthClient @Inject constructor(
    private val sessionStore: SessionStore,
    baseClient: OkHttpClient,
) {
    private val http = baseClient
    private val mutex = Mutex()

    suspend fun signInWithGoogleIdToken(idToken: String, rawNonce: String): Session {
        val body = AppJson.encodeToString(
            JsonObject.serializer(),
            JsonObject(mapOf("provider" to JsonPrimitive("google"), "id_token" to JsonPrimitive(idToken), "nonce" to JsonPrimitive(rawNonce))),
        )
        val res = post("${SupabaseConfig.url}/auth/v1/token?grant_type=id_token", body)
        return res.toSession().also { sessionStore.save(it) }
    }

    /** Email + password sign-in (Supabase Auth). */
    suspend fun signInWithPassword(email: String, password: String): Session {
        val body = AppJson.encodeToString(
            JsonObject.serializer(),
            JsonObject(mapOf("email" to JsonPrimitive(email), "password" to JsonPrimitive(password))),
        )
        return post("${SupabaseConfig.url}/auth/v1/token?grant_type=password", body).toSession().also { sessionStore.save(it) }
    }

    /**
     * Creates an email account. Returns the session when the project does not require
     * email confirmation, or null when Supabase sent a confirmation email first.
     */
    suspend fun signUpWithPassword(email: String, password: String, name: String): Session? {
        val body = AppJson.encodeToString(
            JsonObject.serializer(),
            JsonObject(
                mapOf(
                    "email" to JsonPrimitive(email),
                    "password" to JsonPrimitive(password),
                    "data" to JsonObject(mapOf("full_name" to JsonPrimitive(name))),
                ),
            ),
        )
        val text = postRaw("${SupabaseConfig.url}/auth/v1/signup", body)
        val obj = AppJson.parseToJsonElement(text).jsonObject
        if (obj["access_token"] == null) return null
        return AppJson.decodeFromString(AuthResponse.serializer(), text).toSession().also { sessionStore.save(it) }
    }

    suspend fun signOut() {
        val s = sessionStore.session.value
        if (s != null) {
            runCatching {
                withContext(Dispatchers.IO) {
                    http.newCall(
                        Request.Builder().url("${SupabaseConfig.url}/auth/v1/logout")
                            .header("apikey", SupabaseConfig.anonKey)
                            .header("Authorization", "Bearer ${s.accessToken}")
                            .post(ByteArray(0).toRequestBody()).build(),
                    ).execute().close()
                }
            }
        }
        sessionStore.clear()
    }

    /** Returns a valid access token, refreshing it when it expires within 60 s. */
    suspend fun accessToken(forceRefresh: Boolean = false): String? = mutex.withLock {
        val s = sessionStore.session.value ?: return null
        val now = System.currentTimeMillis() / 1000
        if (!forceRefresh && s.expiresAtEpochSec - now > 60) return s.accessToken
        return try {
            val body = AppJson.encodeToString(JsonObject.serializer(), JsonObject(mapOf("refresh_token" to JsonPrimitive(s.refreshToken))))
            post("${SupabaseConfig.url}/auth/v1/token?grant_type=refresh_token", body).toSession()
                .also { sessionStore.save(it) }.accessToken
        } catch (e: AppException) {
            if (e.httpStatus in 400..499) sessionStore.clear() // refresh token revoked/expired => signed out
            null
        }
    }

    private suspend fun post(url: String, json: String): AuthResponse =
        AppJson.decodeFromString(AuthResponse.serializer(), postRaw(url, json))

    private suspend fun postRaw(url: String, json: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("apikey", SupabaseConfig.anonKey).post(json.toRequestBody(JSON_MEDIA)).build()
        try {
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw authError(r.code, text)
                text
            }
        } catch (e: IOException) {
            throw AppException(AppException.NETWORK, message = e.message ?: "io")
        }
    }

    private fun AuthResponse.toSession() = Session(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAtEpochSec = expiresAt ?: (System.currentTimeMillis() / 1000 + expiresIn),
        userId = user.id,
        email = user.email,
    )
}

/** Adds apikey + bearer token to every Supabase request. */
class SupabaseAuthInterceptor(private val auth: AuthClient) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { auth.accessToken() } ?: SupabaseConfig.anonKey
        val req = chain.request().newBuilder()
            .header("apikey", SupabaseConfig.anonKey)
            .header("Authorization", "Bearer $token")
            .build()
        return chain.proceed(req)
    }
}

/** On 401 refresh once and retry; give up afterwards (user is signed out). */
class SupabaseTokenAuthenticator(private val auth: AuthClient) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorResponse != null) return null
        val token = runBlocking { auth.accessToken(forceRefresh = true) } ?: return null
        return response.request.newBuilder().header("Authorization", "Bearer $token").build()
    }
}

/** Supabase Auth errors carry `error_code` (newer) or `error` + `error_description` (older). */
fun authError(status: Int, body: String): AppException {
    val obj = runCatching { AppJson.parseToJsonElement(body).jsonObject }.getOrNull()
    val errorCode = obj?.get("error_code")?.jsonPrimitive?.contentOrNull
    val legacy = obj?.get("error")?.jsonPrimitive?.contentOrNull
    val message = obj?.get("msg")?.jsonPrimitive?.contentOrNull
        ?: obj?.get("error_description")?.jsonPrimitive?.contentOrNull
        ?: obj?.get("message")?.jsonPrimitive?.contentOrNull ?: body.take(200)
    val code = when {
        errorCode != null -> errorCode
        legacy == "invalid_grant" && message.contains("confirm", ignoreCase = true) -> "email_not_confirmed"
        legacy == "invalid_grant" -> "invalid_credentials"
        status == 429 -> "rate_limited"
        status == 422 && message.contains("registered", ignoreCase = true) -> "user_already_exists"
        status == 422 && message.contains("password", ignoreCase = true) -> "weak_password"
        status in 400..499 -> "session_expired".takeIf { status == 401 } ?: "invalid_input"
        else -> AppException.UNKNOWN
    }
    return AppException(code, status, message)
}

fun parseError(status: Int, body: String): AppException {
    val obj = runCatching { AppJson.parseToJsonElement(body).jsonObject }.getOrNull()
    val fnCode = obj?.get("error")?.jsonPrimitive?.contentOrNull
    val pgCode = obj?.get("code")?.jsonPrimitive?.contentOrNull
    val message = obj?.get("message")?.jsonPrimitive?.contentOrNull ?: obj?.get("msg")?.jsonPrimitive?.contentOrNull ?: body.take(200)
    val extras = obj?.filterValues { it is JsonPrimitive }?.mapValues { (it.value as JsonPrimitive).content } ?: emptyMap()
    val code = when {
        fnCode != null && fnCode != "invalid_grant" -> fnCode
        pgCode == "42501" -> "forbidden"
        status == 401 -> "session_expired"
        status == 403 -> "forbidden"
        status == 429 -> "rate_limited"
        else -> AppException.UNKNOWN
    }
    return AppException(code, status, message, extras)
}

/**
 * Typed access to PostgREST, Edge Functions and Storage. All calls run on IO
 * and throw [AppException] with a stable code on failure.
 */
@Singleton
class SupabaseApi @Inject constructor(@AuthedClient private val http: OkHttpClient) {
    private val base = SupabaseConfig.url

    // ---------------- PostgREST ----------------
    suspend fun <T> select(table: String, serializer: KSerializer<T>, query: Map<String, String>): List<T> {
        val text = execute(Request.Builder().url(restUrl(table, query)).get().build())
        return AppJson.decodeFromString(ListSerializer(serializer), text)
    }

    suspend fun <T> insert(table: String, body: JsonElement, serializer: KSerializer<T>): T {
        val text = execute(
            Request.Builder().url(restUrl(table, emptyMap()))
                .header("Prefer", "return=representation")
                .post(body.toString().toRequestBody(JSON_MEDIA)).build(),
        )
        return AppJson.decodeFromString(ListSerializer(serializer), text).first()
    }

    suspend fun update(table: String, filter: Map<String, String>, patch: JsonObject) {
        execute(
            Request.Builder().url(restUrl(table, filter))
                .header("Prefer", "return=minimal")
                .patch(patch.toString().toRequestBody(JSON_MEDIA)).build(),
        )
    }

    suspend fun delete(table: String, filter: Map<String, String>) {
        execute(Request.Builder().url(restUrl(table, filter)).header("Prefer", "return=minimal").delete().build())
    }

    suspend fun <T> rpc(fn: String, serializer: KSerializer<T>, args: JsonObject = JsonObject(emptyMap())): T {
        val text = execute(
            Request.Builder().url("$base/rest/v1/rpc/$fn").post(args.toString().toRequestBody(JSON_MEDIA)).build(),
        )
        return AppJson.decodeFromString(serializer, text)
    }

    // ---------------- Edge Functions ----------------
    suspend fun <T> function(name: String, serializer: KSerializer<T>, body: JsonObject = JsonObject(emptyMap())): T {
        val text = execute(
            Request.Builder().url("$base/functions/v1/$name").post(body.toString().toRequestBody(JSON_MEDIA)).build(),
        )
        return AppJson.decodeFromString(serializer, text)
    }

    // ---------------- Storage ----------------
    suspend fun upload(path: String, bytes: ByteArray, mime: String) {
        try {
            execute(
                Request.Builder().url("$base/storage/v1/object/${SupabaseConfig.BUCKET}/$path")
                    .header("x-upsert", "false")
                    .post(bytes.toRequestBody(mime.toMediaType())).build(),
            )
        } catch (e: AppException) {
            // Already uploaded by a previous attempt => fine (originals are immutable).
            if (e.httpStatus == 409 || e.message.contains("Duplicate", ignoreCase = true)) return
            throw e
        }
    }

    /** Deletes the user's own objects (storage RLS only allows their folder). */
    suspend fun removeObjects(paths: List<String>) {
        if (paths.isEmpty()) return
        val body = AppJson.encodeToString(
            JsonObject.serializer(),
            JsonObject(mapOf("prefixes" to kotlinx.serialization.json.JsonArray(paths.map { JsonPrimitive(it) }))),
        )
        execute(
            Request.Builder().url("$base/storage/v1/object/${SupabaseConfig.BUCKET}")
                .delete(body.toRequestBody(JSON_MEDIA)).build(),
        )
    }

    /** Authenticated object URL; the shared OkHttp client adds the bearer token (see ImageLoader). */
    fun objectUrl(path: String): String = "$base/storage/v1/object/authenticated/${SupabaseConfig.BUCKET}/$path"

    private fun restUrl(table: String, query: Map<String, String>): HttpUrl =
        "$base/rest/v1/$table".toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()

    private suspend fun execute(req: Request): String = withContext(Dispatchers.IO) {
        try {
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw parseError(r.code, text)
                text
            }
        } catch (e: IOException) {
            throw AppException(AppException.NETWORK, message = e.message ?: "io")
        }
    }
}

@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthedClient
