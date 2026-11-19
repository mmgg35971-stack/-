package com.mangatranslator.features.giftcodes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.mangatranslator.R
import com.mangatranslator.core.common.AppException
import com.mangatranslator.core.common.Formatters
import com.mangatranslator.core.common.asAppException
import com.mangatranslator.core.common.messageRes
import com.mangatranslator.core.designsystem.MtButton
import com.mangatranslator.core.designsystem.MtCard
import com.mangatranslator.core.designsystem.MtTextField
import com.mangatranslator.core.designsystem.MtTopBar
import com.mangatranslator.core.theme.MtTheme
import com.mangatranslator.core.theme.Spacing
import com.mangatranslator.data.models.RedeemResponse
import com.mangatranslator.data.repositories.AccountRepository
import com.mangatranslator.data.repositories.AdminRepository
import com.mangatranslator.data.repositories.GiftCodeRepository
import com.mangatranslator.features.subscriptions.planNameRes
import com.mangatranslator.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RedeemViewModel @Inject constructor(
    private val codes: GiftCodeRepository,
    private val account: AccountRepository,
) : ViewModel() {
    var code by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<AppException?>(null)
        private set
    var result by mutableStateOf<RedeemResponse?>(null)
        private set

    fun redeem() {
        if (busy || code.isBlank()) return
        busy = true; error = null
        viewModelScope.launch {
            try {
                // The server normalizes, hashes and validates the code; the app never decides validity.
                result = codes.redeem(code.trim())
                runCatching { account.refreshMe() }
            } catch (e: Exception) {
                error = e.asAppException()
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun RedeemScreen(nav: NavController, vm: RedeemViewModel = hiltViewModel()) {
    Column(Modifier.fillMaxSize()) {
        MtTopBar(stringResource(R.string.redeem_title), onBack = { nav.popBackStack() })
        Column(Modifier.fillMaxSize().padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            val r = vm.result
            if (r != null) {
                MtCard(Modifier.fillMaxWidth(), highlighted = true) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = MtTheme.colors.success, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = Spacing.sm))
                    Text(
                        stringResource(R.string.redeem_success, stringResource(planNameRes(r.planId)), Formatters.date(r.expiresAt)),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                MtButton(stringResource(R.string.nav_home), { nav.popBackStack(Routes.HOME, inclusive = false) }, modifier = Modifier.fillMaxWidth())
            } else {
                Icon(Icons.Outlined.CardGiftcard, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = Spacing.xl))
                MtTextField(
                    vm.code,
                    { vm.code = it.uppercase().take(32) },
                    stringResource(R.string.redeem_title),
                    placeholder = stringResource(R.string.redeem_hint),
                    error = vm.error?.let { stringResource(it.messageRes()) },
                )
                MtButton(stringResource(R.string.redeem_button), vm::redeem, loading = vm.busy, enabled = vm.code.isNotBlank(), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// ---------------- Admin activation (hidden entry from Profile) ----------------

@HiltViewModel
class AdminActivateViewModel @Inject constructor(
    private val admin: AdminRepository,
    private val account: AccountRepository,
) : ViewModel() {
    var code by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var failed by mutableStateOf<AppException?>(null)
        private set
    var done by mutableStateOf(false)
        private set

    fun activate() {
        if (busy || code.isBlank()) return
        busy = true; failed = null
        viewModelScope.launch {
            try {
                // Verified on the server against a hashed secret, rate limited and audit logged.
                admin.activate(code.trim())
                account.refreshMe()
                done = true
            } catch (e: Exception) {
                failed = e.asAppException()
            } finally {
                busy = false
                code = ""
            }
        }
    }
}

@Composable
fun AdminActivateContent(nav: NavController, vm: AdminActivateViewModel) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        MtTopBar(stringResource(R.string.admin_activate_title), onBack = { nav.popBackStack() })
        Column(Modifier.fillMaxSize().padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            if (vm.done) {
                Text(stringResource(R.string.admin_activated), style = MaterialTheme.typography.titleMedium, color = MtTheme.colors.success)
                MtButton(stringResource(R.string.profile_admin), {
                    nav.navigate(Routes.ADMIN) { popUpTo(Routes.ADMIN_ACTIVATE) { inclusive = true } }
                }, modifier = Modifier.fillMaxWidth())
            } else {
                MtTextField(
                    vm.code, { vm.code = it.take(128) }, stringResource(R.string.admin_activate_hint),
                    error = vm.failed?.let { e ->
                        if (e.code == "rate_limited" || e.isNetwork) ctx.getString(e.messageRes()) else ctx.getString(R.string.admin_activate_failed)
                    },
                )
                MtButton(stringResource(R.string.admin_activate_button), vm::activate, loading = vm.busy, enabled = vm.code.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
    }
}
