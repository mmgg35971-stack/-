package com.mangatranslator.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class Session(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSec: Long,
    val userId: String,
    val email: String? = null,
)

private val Context.sessionDataStore by preferencesDataStore(name = "session")

/**
 * Stores the Supabase session encrypted with an AES-256-GCM key that never
 * leaves the Android Keystore. Plan/role data is NOT stored here: it is
 * always fetched from the server.
 */
@Singleton
class SessionStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val key = stringPreferencesKey("s")
    private val json = Json { ignoreUnknownKeys = true }
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session

    init {
        // One small synchronous read at startup so the first screen knows if we are signed in.
        _session.value = runBlocking { load() }
    }

    private suspend fun load(): Session? = runCatching {
        val enc = context.sessionDataStore.data.first()[key] ?: return null
        json.decodeFromString(Session.serializer(), decrypt(enc))
    }.getOrNull()

    suspend fun save(session: Session) {
        context.sessionDataStore.edit { it[key] = encrypt(json.encodeToString(Session.serializer(), session)) }
        _session.value = session
    }

    suspend fun clear() {
        context.sessionDataStore.edit { it.remove(key) }
        _session.value = null
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(enc: String): String {
        val bytes = Base64.decode(enc, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, 12))
        return String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }

    private companion object {
        const val ALIAS = "mt_session_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
