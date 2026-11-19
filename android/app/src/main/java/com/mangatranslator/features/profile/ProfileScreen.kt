package com.mangatranslator.features.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.mangatranslator.BuildConfig
import com.mangatranslator.R
import com.mangatranslator.core.common.Formatters
import com.mangatranslator.core.common.asAppException
import com.mangatranslator.core.common.messageRes
import com.mangatranslator.core.designsystem.Avatar
import com.mangatranslator.core.designsystem.ButtonVariant
import com.mangatranslator.core.designsystem.LoadingState
import com.mangatranslator.core.designsystem.LocalSnackbar
import com.mangatranslator.core.designsystem.MtButton
import com.mangatranslator.core.designsystem.MtCard
import com.mangatranslator.core.designsystem.MtTextField
import com.mangatranslator.core.designsystem.MtTopBar
import com.mangatranslator.core.designsystem.PlanBadge
import com.mangatranslator.core.theme.MtTheme
import com.mangatranslator.core.theme.Radii
import com.mangatranslator.core.theme.Spacing
import com.mangatranslator.data.repositories.AccountRepository
import com.mangatranslator.data.repositories.AuthRepository
import com.mangatranslator.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val account: AccountRepository,
    private val auth: AuthRepository,
) : ViewModel() {
    val me = account.me

    init { viewModelScope.launch { runCatching { account.refreshMe() } } }

    suspend fun rename(name: String) = account.updateDisplayName(name)

    fun signOut() {
        viewModelScope.launch {
            runCatching { auth.signOut() }
            account.clear()
        }
    }

    suspend fun deleteAccount() {
        account.deleteAccount()
        account.clear()
    }
}

@Composable
fun ProfileScreen(nav: NavController, vm: ProfileViewModel = hiltViewModel()) {
    val me by vm.me.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var editName by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var versionTaps by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        MtTopBar(stringResource(R.string.profile_title))
        val m = me ?: return@Column LoadingState()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            MtCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(m.profile.avatarUrl, m.profile.displayName, size = 56.dp)
                    Spacer(Modifier.width(Spacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(m.profile.displayName ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(m.profile.email ?: "", style = MaterialTheme.typography.bodySmall, color = MtTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { editName = true }) { Icon(Icons.Outlined.Edit, stringResource(R.string.profile_edit_name)) }
                }
            }

            MtCard(Modifier.fillMaxWidth(), highlighted = m.entitlements.planId != "free", onClick = { nav.navigate(Routes.PLANS) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.profile_subscription), style = MaterialTheme.typography.labelLarge, color = MtTheme.colors.textSecondary)
                        m.entitlements.planExpiresAt?.let {
                            Text(stringResource(R.string.home_plan_active_until, Formatters.date(it)), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    PlanBadge(m.entitlements.planId)
                }
            }

            MtCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = Spacing.xs)) {
                ProfileRow(Icons.Outlined.WorkspacePremium, stringResource(R.string.plans_title)) { nav.navigate(Routes.PLANS) }
                ProfileRow(Icons.Outlined.CardGiftcard, stringResource(R.string.profile_redeem)) { nav.navigate(Routes.REDEEM) }
                ProfileRow(Icons.Outlined.Notifications, stringResource(R.string.notifications)) { nav.navigate(Routes.NOTIFICATIONS) }
                ProfileRow(Icons.Outlined.Settings, stringResource(R.string.profile_settings)) { nav.navigate(Routes.SETTINGS) }
                // The entry point is shown only for admins; the server re-checks the role on every admin call.
                if (m.entitlements.isAdmin) {
                    ProfileRow(Icons.Outlined.AdminPanelSettings, stringResource(R.string.profile_admin)) { nav.navigate(Routes.ADMIN) }
                }
            }

            MtButton(stringResource(R.string.profile_sign_out), vm::signOut, variant = ButtonVariant.Secondary, icon = Icons.AutoMirrored.Outlined.Logout, modifier = Modifier.fillMaxWidth())
            MtButton(stringResource(R.string.profile_delete_account), { deleting = true }, variant = ButtonVariant.Danger, modifier = Modifier.fillMaxWidth())

            Text(
                stringResource(R.string.profile_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelSmall,
                color = MtTheme.colors.textSecondary,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    // Hidden entry to admin activation: 7 taps on the version. Activation itself is verified by the server.
                    .clickable {
                        versionTaps++
                        if (versionTaps >= 7) { versionTaps = 0; nav.navigate(Routes.ADMIN_ACTIVATE) }
                    }
                    .padding(Spacing.md),
            )
        }
    }

    if (editName) {
        var name by remember { mutableStateOf(me?.profile?.displayName ?: "") }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) editName = false },
            title = { Text(stringResource(R.string.profile_edit_name)) },
            text = { MtTextField(name, { name = it.take(60) }, stringResource(R.string.profile_display_name)) },
            confirmButton = {
                MtButton(stringResource(R.string.action_save), enabled = name.isNotBlank(), loading = busy, onClick = {
                    busy = true
                    scope.launch {
                        runCatching { vm.rename(name) }
                            .onSuccess { editName = false }
                            .onFailure { snackbar.showSnackbar(ctx.getString(it.asAppException().messageRes())) }
                        busy = false
                    }
                })
            },
            dismissButton = { MtButton(stringResource(R.string.action_cancel), { editName = false }, variant = ButtonVariant.Ghost) },
            containerColor = MtTheme.colors.card,
            shape = RoundedCornerShape(Radii.xl),
        )
    }

    if (deleting) {
        var typed by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) deleting = false },
            title = { Text(stringResource(R.string.profile_delete_account)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Text(stringResource(R.string.profile_delete_confirm))
                    MtTextField(typed, { typed = it }, stringResource(R.string.profile_delete_type))
                }
            },
            confirmButton = {
                MtButton(
                    stringResource(R.string.action_delete), variant = ButtonVariant.Danger,
                    enabled = typed.trim() == "DELETE", loading = busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            runCatching { vm.deleteAccount() }
                                .onFailure { snackbar.showSnackbar(ctx.getString(it.asAppException().messageRes())); busy = false }
                        }
                    },
                )
            },
            dismissButton = { MtButton(stringResource(R.string.action_cancel), { deleting = false }, variant = ButtonVariant.Ghost, enabled = !busy) },
            containerColor = MtTheme.colors.card,
            shape = RoundedCornerShape(Radii.xl),
        )
    }
}

@Composable
private fun ProfileRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(Spacing.md))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        // AutoMirrored: points forward in both RTL and LTR.
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MtTheme.colors.textSecondary)
    }
}
