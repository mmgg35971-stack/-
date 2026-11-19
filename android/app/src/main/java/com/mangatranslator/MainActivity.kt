package com.mangatranslator

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mangatranslator.core.theme.MangaTranslatorTheme
import com.mangatranslator.data.repositories.AccountRepository
import com.mangatranslator.core.push.DeepLinks
import com.mangatranslator.navigation.AppRoot
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var account: AccountRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val settings by account.settings.collectAsStateWithLifecycle()
            MangaTranslatorTheme(themeSetting = settings.theme) {
                AppRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Push notification tap: open that chapter once the main screen is ready. */
    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_JOB_ID)?.takeIf { it.length in 32..40 }?.let { DeepLinks.pendingJobId.value = it }
    }

    companion object {
        const val EXTRA_JOB_ID = "job_id"
    }
}
