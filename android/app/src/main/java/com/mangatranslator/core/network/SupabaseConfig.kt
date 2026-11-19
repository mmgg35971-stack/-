package com.mangatranslator.core.network

import com.mangatranslator.BuildConfig

/**
 * Public client configuration. The anon key is designed to be public: it only
 * grants what Row Level Security allows. No secret keys ever live in the APK.
 */
object SupabaseConfig {
    val url: String = BuildConfig.SUPABASE_URL.trimEnd('/')
    val anonKey: String = BuildConfig.SUPABASE_ANON_KEY
    val googleWebClientId: String = BuildConfig.GOOGLE_WEB_CLIENT_ID
    // Google sign-in is optional (email accounts work without it).
    val isConfigured: Boolean get() = url.startsWith("https://") && anonKey.isNotBlank()

    const val BUCKET = "chapter-pages"
}
