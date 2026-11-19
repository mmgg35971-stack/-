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
    val isConfigured: Boolean get() = url.startsWith("https://") && anonKey.isNotBlank() && googleWebClientId.isNotBlank()

    const val BUCKET = "chapter-pages"
}
