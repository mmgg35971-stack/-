package com.mangatranslator.data.repositories

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.mangatranslator.core.common.AppException
import com.mangatranslator.core.network.AuthClient
import com.mangatranslator.core.network.SupabaseConfig
import com.mangatranslator.core.security.Session
import com.mangatranslator.core.security.SessionStore
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Email + password accounts (Supabase Auth), and optional official Google
 * sign-in through Credential Manager. Google returns an ID
 * token bound to a nonce; Supabase verifies that token server-side and issues
 * our session. We never see or store a Google password.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val auth: AuthClient,
    private val sessionStore: SessionStore,
) {
    val session: StateFlow<Session?> = sessionStore.session

    suspend fun signInWithGoogle(activity: Activity): Session {
        val rawNonce = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val hashedNonce = MessageDigest.getInstance("SHA-256").digest(rawNonce.toByteArray())
            .joinToString("") { "%02x".format(it) }

        val option = GetSignInWithGoogleOption.Builder(SupabaseConfig.googleWebClientId)
            .setNonce(hashedNonce)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        val credential = try {
            CredentialManager.create(activity).getCredential(activity, request).credential
        } catch (e: GetCredentialCancellationException) {
            throw AppException("signin_cancelled")
        } catch (e: NoCredentialException) {
            throw AppException("signin_no_google_account")
        } catch (e: GetCredentialException) {
            throw AppException("signin_failed", message = e.message ?: "credential")
        }

        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw AppException("signin_failed", message = "unexpected credential ${credential.type}")
        }
        val google = GoogleIdTokenCredential.createFrom(credential.data)
        return auth.signInWithGoogleIdToken(google.idToken, rawNonce)
    }

    suspend fun signInWithEmail(email: String, password: String): Session = auth.signInWithPassword(email.trim(), password)

    /** Null when Supabase requires the user to confirm their email first. */
    suspend fun signUpWithEmail(email: String, password: String, name: String): Session? =
        auth.signUpWithPassword(email.trim(), password, name.trim().take(60))

    suspend fun signOut() = auth.signOut()
}
