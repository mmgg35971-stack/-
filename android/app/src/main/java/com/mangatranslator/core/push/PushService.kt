package com.mangatranslator.core.push

import android.Manifest
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.mangatranslator.MainActivity
import com.mangatranslator.MangaTranslatorApp
import com.mangatranslator.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PushService : FirebaseMessagingService() {
    @Inject lateinit var push: PushManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch { push.register(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: return
        val jobId = message.data["job_id"]
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { if (jobId != null) putExtra(MainActivity.EXTRA_JOB_ID, jobId) }
        val pending = PendingIntent.getActivity(this, jobId.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, MangaTranslatorApp.CHANNEL_JOBS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message.notification?.body)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        // Skipped when the user denied the notification permission (Android 13+).
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) {
            try { NotificationManagerCompat.from(this).notify(jobId.hashCode(), n) } catch (_: SecurityException) { }
        }
    }
}
