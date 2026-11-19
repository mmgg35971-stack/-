package com.mangatranslator

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mangatranslator.core.theme.MangaTranslatorTheme
import com.mangatranslator.data.repositories.AccountRepository
import com.mangatranslator.navigation.AppRoot
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var account: AccountRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by account.settings.collectAsStateWithLifecycle()
            MangaTranslatorTheme(themeSetting = settings.theme) {
                AppRoot()
            }
        }
    }
}
