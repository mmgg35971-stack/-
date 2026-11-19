package com.mangatranslator.features.admin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.mangatranslator.R
import com.mangatranslator.core.common.AppException
import com.mangatranslator.core.common.Formatters
import com.mangatranslator.core.common.asAppException
import com.mangatranslator.core.common.messageRes
import com.mangatranslator.core.designsystem.Avatar
import com.mangatranslator.core.designsystem.ButtonVariant
import com.mangatranslator.core.designsystem.ConfirmDialog
import com.mangatranslator.core.designsystem.EmptyState
import com.mangatranslator.core.designsystem.ErrorState
import com.mangatranslator.core.designsystem.LoadingState
import com.mangatranslator.core.designsystem.LocalSnackbar
import com.mangatranslator.core.designsystem.MtButton
import com.mangatranslator.core.designsystem.MtCard
import com.mangatranslator.core.designsystem.MtTextField
import com.mangatranslator.core.designsystem.MtTopBar
import com.mangatranslator.core.designsystem.PlanBadge
import com.mangatranslator.core.designsystem.SectionHeader
import com.mangatranslator.core.theme.MtTheme
import com.mangatranslator.core.theme.Radii
import com.mangatranslator.core.theme.Spacing
import com.mangatranslator.data.models.AdminStats
import com.mangatranslator.data.models.AiCostDay
import com.mangatranslator.data.models.ConfigEntry
import com.mangatranslator.data.models.SubscriptionRow
import com.mangatranslator.data.models.AdminUser
import com.mangatranslator.data.models.AdminUserDetail
import com.mangatranslator.data.models.AuditLog
import com.mangatranslator.data.models.CreatedCodesResponse
import com.mangatranslator.data.models.GiftCodeRow
import com.mangatranslator.data.repositories.AdminRepository
import com.mangatranslator.features.giftcodes.AdminActivateContent
import com.mangatranslator.features.giftcodes.AdminActivateViewModel
import com.mangatranslator.features.subscriptions.planNameRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@Composable
fun AdminActivateScreen(nav: NavController, vm: AdminActivateViewModel = hiltViewModel()) = AdminActivateContent(nav, vm)

@HiltViewModel
class AdminViewModel @Inject constructor(private val admin: AdminRepository) : ViewModel() {
    var stats by mutableStateOf<AdminStats?>(null)
        private set
    var statsError by mutableStateOf<AppException?>(null)
        private set

    var userQuery by mutableStateOf("")

        private set
    var users by mutableStateOf<List<AdminUser>>(emptyList())
        private set
    var usersError by mutableStateOf<AppException?>(null)
        private set
    var detail by mutableStateOf<AdminUserDetail?>(null)
        private set

    var codes by mutableStateOf<List<GiftCodeRow>>(emptyList())

        private set
    var codesError by mutableStateOf<AppException?>(null)
        private set
    var planFilter by mutableStateOf<String?>(null)
        private set
    var statusFilter by mutableStateOf<String?>(null)
        private set
    var created by mutableStateOf<CreatedCodesResponse?>(null)

    var logs by mutableStateOf<List<AuditLog>>(emptyList())

        private set
    var logsError by mutableStateOf<AppException?>(null)
        private set

    var costDays by mutableStateOf<List<AiCostDay>>(emptyList())
        private set
    var config by mutableStateOf<List<ConfigEntry>>(emptyList())
        private set
    var configError by mutableStateOf<AppException?>(null)
        private set

    var loading by mutableStateOf(false)

        private set
    private var searchJob: Job? = null

    fun loadStats() = launchLoading {
        try { stats = admin.stats(); statsError = null } catch (e: Exception) { statsError = e.asAppException() }
        runCatching { costDays = admin.aiCostDaily(14) }
    }

    suspend fun grant(userId: String, plan: String, days: Int, note: String) {
        admin.grantSubscription(userId, plan, days, note)
        detail = admin.userDetail(userId)
    }

    suspend fun revokeSubscription(userId: String, subscriptionId: String, note: String) {
        admin.revokeSubscription(subscriptionId, note)
        detail = admin.userDetail(userId)
    }

    fun loadConfig() = launchLoading {
        try { config = admin.config(); configError = null } catch (e: Exception) { configError = e.asAppException() }
    }

    suspend fun saveConfig(key: String, value: JsonElement) {
        admin.updateConfig(key, value)
        config = admin.config()
    }

    fun searchUsers(q: String = userQuery) {
        userQuery = q
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            try { users = admin.searchUsers(q.trim()); usersError = null } catch (e: Exception) { usersError = e.asAppException() }
        }
    }

    fun openUser(id: String, onError: (AppException) -> Unit) = viewModelScope.launch {
        runCatching { detail = admin.userDetail(id) }.onFailure { onError(it.asAppException()) }
    }

    fun closeUser() { detail = null }

    fun loadCodes(plan: String? = planFilter, status: String? = statusFilter) = launchLoading {
        planFilter = plan; statusFilter = status
        try { codes = admin.listCodes(plan, status).codes; codesError = null } catch (e: Exception) { codesError = e.asAppException() }
    }

    suspend fun createCodes(plan: String, days: Int, qty: Int, validFor: Int?, maxUses: Int, notes: String) {
        created = admin.createCodes(plan, days, qty, validFor, maxUses, notes)
        loadCodes()
    }

    suspend fun revoke(id: String) {
        admin.revokeCode(id)
        codes = codes.map { if (it.id == id) it.copy(status = "revoked") else it }
    }

    fun loadLogs() = launchLoading {
        try { logs = admin.auditLogs(); logsError = null } catch (e: Exception) { logsError = e.asAppException() }
    }

    private fun launchLoading(block: suspend () -> Unit) = viewModelScope.launch {
        loading = true
        try { block() } finally { loading = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(nav: NavController, vm: AdminViewModel = hiltViewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(R.string.admin_tab_overview, R.string.admin_tab_users, R.string.admin_tab_codes, R.string.admin_tab_logs, R.string.admin_tab_settings)
    LaunchedEffect(tab) {
        when (tab) {
            0 -> vm.loadStats()
            1 -> vm.searchUsers()
            2 -> vm.loadCodes()
            3 -> vm.loadLogs()
            4 -> vm.loadConfig()
        }
    }
    Column(Modifier.fillMaxSize()) {
        MtTopBar(stringResource(R.string.admin_title), onBack = { nav.popBackStack() })
        PrimaryScrollableTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background, edgePadding = Spacing.sm) {
            tabs.forEachIndexed { i, res -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(res), maxLines = 1) }) }
        }
        when (tab) {
            0 -> OverviewTab(vm)
            1 -> UsersTab(vm)
            2 -> CodesTab(vm)
            3 -> LogsTab(vm)
            else -> SettingsTab(vm)
        }
    }
}

// ---------------- Overview ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverviewTab(vm: AdminViewModel) {
    val s = vm.stats
    when {
        s == null && vm.statsError != null -> ErrorState(vm.statsError!!, onRetry = { vm.loadStats() })
        s == null -> LoadingState()
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            if (s.aiCostAlert) {
                MtCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Warning, null, tint = MtTheme.colors.warning)
                        Spacer(Modifier.width(Spacing.sm))
                        Text(stringResource(R.string.admin_ai_cost_alert, "%.2f".format(s.aiCostLimitUsd)), color = MtTheme.colors.warning)
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md), maxItemsInEachRow = 2) {
                val tile = Modifier.weight(1f)
                Stat(tile, R.string.admin_users_total, s.usersTotal.toString())
                Stat(tile, R.string.admin_users_active, s.usersActive7d.toString())
                Stat(tile, R.string.admin_users_free, (s.usersByPlan["free"] ?: 0).toString())
                Stat(tile, R.string.admin_users_pro, (s.usersByPlan["pro"] ?: 0).toString())
                Stat(tile, R.string.admin_users_premium, (s.usersByPlan["premium"] ?: 0).toString())
                Stat(tile, R.string.admin_chapters_total, s.chaptersTotal.toString())
                Stat(tile, R.string.admin_chapters_today, s.chaptersToday.toString())
                Stat(tile, R.string.admin_failed_total, s.jobsFailedTotal.toString())
                Stat(tile, R.string.admin_codes_total, s.codesTotal.toString())
                Stat(tile, R.string.admin_codes_used, s.codesUsed.toString())
                Stat(tile, R.string.admin_codes_remaining, s.codesRemaining.toString())
                Stat(tile, R.string.admin_ai_cost_today, "$" + "%.2f".format(s.aiCostTodayUsd))
                Stat(tile, R.string.admin_ai_failed_today, s.aiFailedToday.toString())
            }
            if (vm.costDays.isNotEmpty()) AiCostCard(vm.costDays, s.aiCostLimitUsd)
            s.generatedAt?.let { Text(Formatters.dateTime(it), style = MaterialTheme.typography.labelSmall, color = MtTheme.colors.textSecondary) }
            MtButton(stringResource(R.string.action_retry), { vm.loadStats() }, variant = ButtonVariant.Secondary, loading = vm.loading, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Stat(modifier: Modifier, label: Int, value: String) {
    MtCard(modifier) {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MtTheme.colors.textSecondary)
    }
}

// ---------------- Users ----------------

@Composable
private fun UsersTab(vm: AdminViewModel) {
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        MtTextField(vm.userQuery, { vm.searchUsers(it) }, stringResource(R.string.admin_users_search), modifier = Modifier.padding(Spacing.screen))
        vm.usersError?.let { ErrorState(it, onRetry = { vm.searchUsers() }); return@Column }
        LazyColumn(contentPadding = PaddingValues(horizontal = Spacing.screen, vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            items(vm.users, key = { it.id }) { u ->
                MtCard(Modifier.fillMaxWidth(), onClick = {
                    vm.openUser(u.id) { e -> scope.launch { snackbar.showSnackbar(ctx.getString(e.messageRes())) } }
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(u.avatarUrl, u.displayName ?: u.email)
                        Spacer(Modifier.width(Spacing.md))
                        Column(Modifier.weight(1f)) {
                            Text(u.displayName ?: "—", style = MaterialTheme.typography.titleSmall)
                            Text(u.email ?: u.id, style = MaterialTheme.typography.bodySmall, color = MtTheme.colors.textSecondary)
                            Text(stringResource(R.string.admin_user_usage, u.usedToday), style = MaterialTheme.typography.labelSmall, color = MtTheme.colors.textSecondary)
                        }
                        PlanBadge(u.planId)
                    }
                }
            }
        }
    }
    vm.detail?.let { d ->
        var granting by remember(d.profile.id) { mutableStateOf(false) }
        var revoking by remember(d.profile.id) { mutableStateOf<SubscriptionRow?>(null) }
        if (granting) GrantSubscriptionDialog(vm, d.profile.id) { granting = false }
        revoking?.let { sub -> RevokeSubscriptionDialog(vm, d.profile.id, sub) { revoking = null } }
        AlertDialog(
            onDismissRequest = vm::closeUser,
            title = { Text(d.profile.displayName ?: d.profile.email ?: d.profile.id) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    SelectionContainer { Text(d.profile.email ?: "", style = MaterialTheme.typography.bodySmall) }
                    SelectionContainer { Text(d.profile.id, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) }
                    Text(stringResource(R.string.admin_user_plan, stringResource(planNameRes(d.entitlements.planId))))
                    Text(stringResource(R.string.admin_user_usage, d.entitlements.usedToday))
                    Text(stringResource(R.string.admin_user_created, Formatters.date(d.profile.createdAt)))
                    Spacer(Modifier.height(Spacing.sm))
                    Text(stringResource(R.string.admin_user_subs), style = MaterialTheme.typography.titleSmall)
                    d.subscriptions.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${stringResource(planNameRes(s.planId))} · ${s.source} · ${s.status} · ${Formatters.date(s.startsAt)} → ${s.expiresAt?.let(Formatters::date) ?: "∞"}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            if (s.status == "active") {
                                MtButton(stringResource(R.string.admin_sub_revoke), { revoking = s }, variant = ButtonVariant.Ghost)
                            }
                        }
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    MtButton(stringResource(R.string.admin_sub_grant), { granting = true }, variant = ButtonVariant.Secondary, modifier = Modifier.fillMaxWidth())
                    if (d.usageHistory.isNotEmpty()) {
                        Spacer(Modifier.height(Spacing.sm))
                        d.usageHistory.take(14).forEach { Text("${it.date}: ${it.used}", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            },
            confirmButton = { MtButton(stringResource(R.string.action_close), vm::closeUser, variant = ButtonVariant.Ghost) },
            containerColor = MtTheme.colors.card,
            shape = RoundedCornerShape(Radii.xl),
        )
    }
}

// ---------------- Gift codes ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CodesTab(vm: AdminViewModel) {
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var creating by remember { mutableStateOf(false) }
    var toRevoke by remember { mutableStateOf<GiftCodeRow?>(null) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            MtButton(stringResource(R.string.admin_codes_create), { creating = true }, icon = Icons.Outlined.Add, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                FilterChip(vm.planFilter == null, { vm.loadCodes(plan = null) }, { Text(stringResource(R.string.admin_filter_all)) })
                listOf("pro", "premium").forEach { p -> FilterChip(vm.planFilter == p, { vm.loadCodes(plan = p) }, { Text(stringResource(planNameRes(p))) }) }
                Spacer(Modifier.width(Spacing.sm))
                FilterChip(vm.statusFilter == null, { vm.loadCodes(status = null) }, { Text(stringResource(R.string.admin_filter_all)) })
                listOf("active", "exhausted", "revoked").forEach { s -> FilterChip(vm.statusFilter == s, { vm.loadCodes(status = s) }, { Text(stringResource(codeStatusRes(s))) }) }
            }
        }
        when {
            vm.codesError != null -> ErrorState(vm.codesError!!, onRetry = { vm.loadCodes() })
            vm.codes.isEmpty() && vm.loading -> LoadingState()
            vm.codes.isEmpty() -> EmptyState(Icons.Outlined.Add, stringResource(R.string.admin_codes_empty))
            else -> LazyColumn(contentPadding = PaddingValues(horizontal = Spacing.screen, vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items(vm.codes, key = { it.id }) { c ->
                    MtCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Only a hint is stored server-side; full codes are shown once, at creation.
                            Text(c.codeHint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            PlanBadge(c.planId)
                        }
                        Text(
                            listOf(
                                stringResource(R.string.admin_codes_days, c.durationDays),
                                stringResource(codeStatusRes(c.status)),
                                "${c.redemptionCount}/${c.maxRedemptions}",
                                c.expiresAt?.let { stringResource(R.string.admin_codes_expires, Formatters.date(it)) } ?: stringResource(R.string.admin_codes_no_expiry),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MtTheme.colors.textSecondary,
                        )
                        c.redemptions.forEach { r ->
                            Text(
                                stringResource(R.string.admin_codes_used_by, (r.user?.email ?: r.user?.displayName ?: "—") + " · " + Formatters.dateTime(r.redeemedAt)),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        c.notes?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MtTheme.colors.textSecondary) }
                        if (c.status == "active") {
                            MtButton(stringResource(R.string.admin_codes_revoke), { toRevoke = c }, variant = ButtonVariant.Danger, modifier = Modifier.padding(top = Spacing.sm))
                        }
                    }
                }
            }
        }
    }

    if (creating) CreateCodesDialog(vm, onDismiss = { creating = false })
    vm.created?.let { CreatedCodesDialog(it, onDismiss = { vm.created = null }) }
    toRevoke?.let { c ->
        ConfirmDialog(
            title = stringResource(R.string.admin_codes_revoke),
            text = c.codeHint,
            confirm = stringResource(R.string.admin_codes_revoke),
            danger = true,
            onDismiss = { toRevoke = null },
            onConfirm = {
                toRevoke = null
                scope.launch { runCatching { vm.revoke(c.id) }.onFailure { snackbar.showSnackbar(ctx.getString(it.asAppException().messageRes())) } }
            },
        )
    }
}

private fun codeStatusRes(s: String) = when (s) {
    "revoked" -> R.string.admin_codes_status_revoked
    "exhausted" -> R.string.admin_codes_status_exhausted
    else -> R.string.admin_codes_status_active
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateCodesDialog(vm: AdminViewModel, onDismiss: () -> Unit) {
    var plan by remember { mutableStateOf("pro") }
    var days by remember { mutableIntStateOf(30) }
    var qty by remember { mutableStateOf("10") }
    var validFor by remember { mutableStateOf("") }
    var singleUse by remember { mutableStateOf(true) }
    var maxUses by remember { mutableStateOf("2") }
    var notes by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val qtyInt = qty.toIntOrNull()
    val validInt = validFor.toIntOrNull()
    val maxUsesInt = if (singleUse) 1 else maxUses.toIntOrNull()
    val valid = qtyInt != null && qtyInt in 1..1000 &&
        (validFor.isBlank() || (validInt != null && validInt in 1..3650)) &&
        maxUsesInt != null && maxUsesInt in 1..100_000

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.admin_codes_create)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(R.string.admin_codes_plan), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    listOf("pro", "premium").forEach { p -> FilterChip(plan == p, { plan = p }, { Text(stringResource(planNameRes(p))) }) }
                }
                Text(stringResource(R.string.admin_codes_duration), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    listOf(7, 30, 90, 180, 365).forEach { d -> FilterChip(days == d, { days = d }, { Text(stringResource(R.string.admin_codes_days, d)) }) }
                }
                MtTextField(qty, { qty = it.filter(Char::isDigit).take(4) }, stringResource(R.string.admin_codes_quantity), keyboardType = KeyboardType.Number)
                MtTextField(validFor, { validFor = it.filter(Char::isDigit).take(4) }, stringResource(R.string.admin_codes_expiry_days), keyboardType = KeyboardType.Number)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { singleUse = !singleUse }) {
                    Checkbox(singleUse, { singleUse = it })
                    Text(stringResource(R.string.admin_codes_single_use))
                }
                if (!singleUse) {
                    MtTextField(maxUses, { maxUses = it.filter(Char::isDigit).take(6) }, stringResource(R.string.admin_codes_max_uses), keyboardType = KeyboardType.Number)
                }
                MtTextField(notes, { notes = it.take(500) }, stringResource(R.string.admin_codes_notes), singleLine = false)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            MtButton(stringResource(R.string.action_confirm), enabled = valid, loading = busy, onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        vm.createCodes(plan, days, qtyInt!!, validInt, maxUsesInt!!, notes)
                        onDismiss()
                    } catch (e: Exception) {
                        error = ctx.getString(e.asAppException().messageRes())
                    } finally {
                        busy = false
                    }
                }
            })
        },
        dismissButton = { MtButton(stringResource(R.string.action_cancel), onDismiss, variant = ButtonVariant.Ghost, enabled = !busy) },
        containerColor = MtTheme.colors.card,
        shape = RoundedCornerShape(Radii.xl),
    )
}

@Composable
private fun CreatedCodesDialog(res: CreatedCodesResponse, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val text = res.codes.joinToString("\n")
    AlertDialog(
        onDismissRequest = {}, // Must be closed explicitly: this is the only time the codes are visible.
        title = { Text(stringResource(R.string.admin_codes_created, res.codes.size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(R.string.admin_codes_once_warning), color = MtTheme.colors.warning, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(color = MtTheme.colors.border)
                SelectionContainer {
                    Text(text, fontFamily = FontFamily.Monospace, modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    MtButton(stringResource(R.string.action_copy), {
                        copyToClipboard(ctx, text)
                        scope.launch { snackbar.showSnackbar(ctx.getString(R.string.copied)) }
                    }, variant = ButtonVariant.Secondary, icon = Icons.Outlined.ContentCopy)
                    MtButton(stringResource(R.string.action_share), { shareText(ctx, text) }, variant = ButtonVariant.Secondary, icon = Icons.Outlined.Share)
                }
                MtButton(stringResource(R.string.action_export), {
                    runCatching { shareCsv(ctx, res) }.onFailure { scope.launch { snackbar.showSnackbar(ctx.getString(R.string.error_generic)) } }
                }, variant = ButtonVariant.Secondary, icon = Icons.Outlined.FileDownload, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { MtButton(stringResource(R.string.action_close), onDismiss) },
        containerColor = MtTheme.colors.card,
        shape = RoundedCornerShape(Radii.xl),
    )
}

private fun copyToClipboard(ctx: Context, text: String) {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText("codes", text)
    // Mark as sensitive so Android 13+ does not show the content in the clipboard preview.
    clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    cm.setPrimaryClip(clip)
}

private fun shareText(ctx: Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(i, null))
}

/** Writes the batch to cache/exports and shares it through the FileProvider (no storage permission needed). */
private fun shareCsv(ctx: Context, res: CreatedCodesResponse) {
    val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, "gift-codes-${res.batchId.take(8)}.csv")
    file.writeText(buildString {
        append("code,plan,duration_days,code_expires_at,batch_id\n")
        res.codes.forEach { append("$it,${res.plan},${res.durationDays},${res.codeExpiresAt ?: ""},${res.batchId}\n") }
    })
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    val i = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, null))
}

// ---------------- Audit logs ----------------

@Composable
private fun LogsTab(vm: AdminViewModel) {
    when {
        vm.logsError != null -> ErrorState(vm.logsError!!, onRetry = { vm.loadLogs() })
        vm.logs.isEmpty() && vm.loading -> LoadingState()
        vm.logs.isEmpty() -> EmptyState(Icons.Outlined.Warning, stringResource(R.string.admin_logs_empty))
        else -> LazyColumn(contentPadding = PaddingValues(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            item { SectionHeader(stringResource(R.string.admin_tab_logs)) }
            items(vm.logs, key = { it.id }) { l ->
                MtCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(Spacing.md)) {
                    Text(l.action, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                    Text(
                        listOfNotNull(Formatters.dateTime(l.createdAt), l.targetType, l.targetId?.take(8), l.actorId?.take(8)).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = MtTheme.colors.textSecondary,
                    )
                }
            }
        }
    }
}
