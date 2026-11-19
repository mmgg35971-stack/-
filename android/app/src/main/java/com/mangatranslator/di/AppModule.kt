package com.mangatranslator.di

import android.content.Context
import androidx.room.Room
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.mangatranslator.BuildConfig
import com.mangatranslator.core.database.AppDatabase
import com.mangatranslator.core.database.DraftDao
import com.mangatranslator.core.network.AuthClient
import com.mangatranslator.core.network.AuthedClient
import com.mangatranslator.core.network.SupabaseAuthInterceptor
import com.mangatranslator.core.network.SupabaseTokenAuthenticator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** Plain client: used only for the Auth endpoints. */
    @Provides
    @Singleton
    fun baseOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .apply {
            if (BuildConfig.DEBUG) {
                // Never log bodies/headers: they contain tokens and user images.
                addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            }
        }
        .build()

    /** Client with apikey + bearer token + refresh-on-401, used for REST, Functions, Storage and images. */
    @Provides
    @Singleton
    @AuthedClient
    fun authedOkHttp(base: OkHttpClient, auth: AuthClient): OkHttpClient = base.newBuilder()
        .addInterceptor(SupabaseAuthInterceptor(auth))
        .authenticator(SupabaseTokenAuthenticator(auth))
        .build()

    @Provides
    @Singleton
    fun imageLoader(@ApplicationContext context: Context, @AuthedClient client: OkHttpClient): ImageLoader =
        ImageLoader.Builder(context)
            .okHttpClient(client)
            .memoryCache { MemoryCache.Builder(context).maxSizePercent(0.20).build() }
            .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("images")).maxSizeBytes(300L * 1024 * 1024).build() }
            .crossfade(true)
            .respectCacheHeaders(false)
            .build()

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "manga_translator.db").build()

    @Provides
    fun draftDao(db: AppDatabase): DraftDao = db.draftDao()
}
