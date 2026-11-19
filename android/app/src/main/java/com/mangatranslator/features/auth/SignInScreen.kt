package com.mangatranslator.features.auth

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.SettingsSuggest
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mangatranslator.R
import com.mangatranslator.core.common.AppException
import com.mangatranslator.core.common.asAppException
import com.mangatranslator.core.common.messageRes
import com.mangatranslator.core.designsystem.EmptyState
import com.mangatranslator.core.designsystem.MtButton
import com.mangatranslator.core.theme.MtColors
import com.mangatranslator.core.theme.MtTheme
import com.mangatranslator.core.theme.Spacing
import com.mangatranslator.data.repositories.AccountRepository
import com.mangatranslator.data.repositories.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val account: AccountRepository,
) : ViewModel() {
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<AppException?>(null)
        private set

    fun signIn(activity: Activity) {
        if (loading) return
        loading = true
        error = null
        viewModelScope.launch {
            try {
                auth.signInWithGoogle(activity)
                runCatching { account.refreshMe(); account.loadSettings() }
            } catch (e: Exception) {
                val err = e.asAppException()
                if (err.code != "signin_cancelled") error = err
            } finally {
                loading = false
            }
        }
    }
}

@Composable
fun SignInScreen(vm: AuthViewModel = hiltViewModel()) {
    val activity = LocalContext.current as Activity
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1A1033), MaterialTheme.colorScheme.background)))
            .systemBarsPadding(),
    ) {
        Column(
            Modifier.fillMaxSize().padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(120.dp))
            Spacer(Modifier.height(Spacing.lg))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, color = MtColors.PurpleLight)
            Spacer(Modifier.height(Spacing.md))
            Text(stringResource(R.string.signin_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Spacing.sm))
            Text(
                stringResource(R.string.signin_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MtTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.xxl))
            MtButton(
                text = stringResource(R.string.signin_google),
                onClick = { vm.signIn(activity) },
                loading = vm.loading,
                icon = Icons.AutoMirrored.Outlined.Login,
                modifier = Modifier.fillMaxWidth(),
            )
            vm.error?.let { err ->
                Spacer(Modifier.height(Spacing.md))
                val msg = when (err.code) {
                    "signin_no_google_account" -> R.string.signin_no_google_account
                    "signin_failed" -> R.string.signin_failed
                    else -> err.messageRes()
                }
                Text(stringResource(msg), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(Spacing.xl))
            Text(
                stringResource(R.string.signin_terms),
                style = MaterialTheme.typography.bodySmall,
                color = MtTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Shown only when the APK was built without backend configuration — never a fake demo mode. */
@Composable
fun ConfigMissingScreen() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.config_missing_title), style = MaterialTheme.typography.titleLarge)
            EmptyState(Icons.Outlined.SettingsSuggest, stringResource(R.string.config_missing_body))
        }
    }
}
