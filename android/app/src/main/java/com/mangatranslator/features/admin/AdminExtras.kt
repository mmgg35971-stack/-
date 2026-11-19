package com.mangatranslator.features.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mangatranslator.R
import com.mangatranslator.core.common.Formatters
import com.mangatranslator.core.common.asAppException
import com.mangatranslator.core.common.messageRes
import com.mangatranslator.core.designsystem.ButtonVariant
import com.mangatranslator.core.designsystem.ErrorState
import com.mangatranslator.core.designsystem.LoadingState
import com.mangatranslator.core.designsystem.LocalSnackbar
import com.mangatranslator.core.designsystem.MtButton
import com.mangatranslator.core.designsystem.MtCard
import com.mangatranslator.core.designsystem.MtTextField
import com.mangatranslator.core.theme.MtTheme
import com.mangatranslator.core.theme.Radii
import com.mangatranslator.core.theme.Spacing
import com.mangatranslator.data.models.AiCostDay
import com.mangatranslator.data.models.ConfigEntry
import com.mangatranslator.data.models.SubscriptionRow
import com.mangatranslator.features.subscriptions.planNameRes
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

// ---------------- AI cost (Overview) ----------------

/** Daily AI cost for the last days, as bars scaled to the highest day. Days over the alert limit are drawn in the warning color. */
@Composable
internal fun AiCostCard(days: List<AiCostDay>, limitUsd: Double) {
    val max = days.maxOf { it.costUsd }.coerceAtLeast(0.01)
    MtCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.admin_ai_cost_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            stringResource(R.string.admin_ai_cost_total, "%.2f".format(days.sumOf { it.costUsd }), days.sumOf { it.requests }, days.sumOf { it.failed }),
            style = MaterialTheme.typography.bodySmall,
            color = MtTheme.colors.textSecondary,
        )
        Spacer(Modifier.height(Spacing.md))
        days.sortedByDescending { it.day }.forEach { d ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Formatters.date(d.day), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(88.dp))
                Box(Modifier.weight(1f).height(10.dp)) {
                    val over = limitUsd > 0 && d.costUsd >= limitUsd
                    Box(
                        Modifier.fillMaxHeight()
                            .fillMaxWidth((d.costUsd / max).toFloat().coerceIn(0.02f, 1f))
                            .background(if (over) MtTheme.colors.warning else MaterialTheme.colorScheme.primary, RoundedCornerShape(Radii.sm)),
                    )
                }
                Spacer(Modifier.width(Spacing.sm))
                Text("$" + "%.2f".format(d.costUsd), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(64.dp))
                if (d.failed > 0) Text("✕${d.failed}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ---------------- Subscriptions (user detail) ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GrantSubscriptionDialog(vm: AdminViewModel, userId: String, onDismiss: () -> Unit) {
    var plan by remember { mutableStateOf("pro") }
    var days by remember { mutableIntStateOf(30) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val snackbar = LocalSnackbar.current
    val done = stringResource(R.string.admin_sub_granted)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.admin_sub_grant)) },
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
                Text(stringResource(R.string.admin_sub_grant_hint), style = MaterialTheme.typography.bodySmall, color = MtTheme.colors.textSecondary)
                MtTextField(note, { note = it.take(300) }, stringResource(R.string.admin_sub_note), singleLine = false)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            MtButton(stringResource(R.string.action_confirm), loading = busy, onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        vm.grant(userId, plan, days, note)
                        onDismiss()
                        snackbar.showSnackbar(done)
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
internal fun RevokeSubscriptionDialog(vm: AdminViewModel, userId: String, sub: SubscriptionRow, onDismiss: () -> Unit) {
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.admin_sub_revoke)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(R.string.admin_sub_revoke_confirm, stringResource(planNameRes(sub.planId))))
                if (sub.source == "google_play") {
                    // Revoking here ends access in the app only; the store subscription and refunds are handled in Play Console.
                    Text(stringResource(R.string.admin_sub_revoke_play), style = MaterialTheme.typography.bodySmall, color = MtTheme.colors.warning)
                }
                MtTextField(note, { note = it.take(300) }, stringResource(R.string.admin_sub_note), singleLine = false)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            MtButton(stringResource(R.string.admin_sub_revoke), variant = ButtonVariant.Danger, loading = busy, onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        vm.revokeSubscription(userId, sub.id, note)
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

// ---------------- Settings tab (app_config) ----------------

private fun JsonElement?.num(field: String? = null): String {
    val el = if (field == null) this else (this as? JsonObject)?.get(field)
    val d = (el as? JsonPrimitive)?.doubleOrNull ?: return ""
    return if (d == Math.floor(d)) d.toLong().toString() else d.toString()
}

@Composable
internal fun SettingsTab(vm: AdminViewModel) {
    when {
        vm.config.isEmpty() && vm.configError != null -> ErrorState(vm.configError!!, onRetry = { vm.loadConfig() })
        vm.config.isEmpty() -> LoadingState()
        else -> {
            val byKey = vm.config.associateBy(ConfigEntry::key)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.screen),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(stringResource(R.string.admin_settings_hint), style = MaterialTheme.typography.bodySmall, color = MtTheme.colors.textSecondary)
                val fair = byKey["fair_use"]?.value
                ConfigCard(
                    key = "fair_use",
                    title = R.string.admin_cfg_fair_use,
                    fields = listOf(
                        ConfigField("premium_soft_daily_chapters", R.string.admin_cfg_premium_soft, fair.num("premium_soft_daily_chapters"), 10.0..100_000.0),
                        ConfigField("max_job_starts_per_10min", R.string.admin_cfg_job_starts, fair.num("max_job_starts_per_10min"), 1.0..1000.0),
                    ),
                    vm = vm,
                )
                ConfigCard(
                    key = "ai_cost_alerts",
                    title = R.string.admin_cfg_cost_alert,
                    fields = listOf(ConfigField("daily_usd_limit", R.string.admin_cfg_cost_limit, byKey["ai_cost_alerts"]?.value.num("daily_usd_limit"), 0.0..1_000_000.0, decimal = true)),
                    vm = vm,
                )
                ConfigCard(
                    key = "max_pages_per_chapter",
                    title = R.string.admin_cfg_max_pages,
                    fields = listOf(ConfigField(null, R.string.admin_cfg_max_pages_value, byKey["max_pages_per_chapter"]?.value.num(), 1.0..500.0)),
                    vm = vm,
                )
            }
        }
    }
}

private data class ConfigField(val name: String?, val label: Int, val initial: String, val range: ClosedFloatingPointRange<Double>, val decimal: Boolean = false)

/** One app_config key. Validated here for quick feedback; the server validates again and audits every change. */
@Composable
private fun ConfigCard(key: String, title: Int, fields: List<ConfigField>, vm: AdminViewModel) {
    val values = remember(fields.map { it.initial }) { fields.map { mutableStateOf(it.initial) } }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val ctx = LocalContext.current
    val saved = stringResource(R.string.admin_settings_saved)
    val parsed = fields.mapIndexed { i, f -> values[i].value.toDoubleOrNull()?.takeIf { it in f.range && (f.decimal || it == Math.floor(it)) } }
    val valid = parsed.all { it != null }
    val changed = fields.indices.any { values[it].value != fields[it].initial }

    MtCard(Modifier.fillMaxWidth()) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(Spacing.sm))
        fields.forEachIndexed { i, f ->
            MtTextField(
                values[i].value,
                { v -> values[i].value = v.filter { it.isDigit() || (f.decimal && it == '.') }.take(9) },
                stringResource(f.label) + " (${f.range.start.toLong()}–${f.range.endInclusive.toLong()})",
                keyboardType = if (f.decimal) KeyboardType.Decimal else KeyboardType.Number,
            )
            Spacer(Modifier.height(Spacing.xs))
        }
        MtButton(stringResource(R.string.action_save), enabled = valid && changed, loading = busy, onClick = {
            busy = true
            scope.launch {
                runCatching {
                    val single = fields.singleOrNull()?.takeIf { it.name == null }
                    val value: JsonElement = if (single != null) {
                        JsonPrimitive(parsed[0]!!.toLong())
                    } else {
                        buildJsonObject {
                            fields.forEachIndexed { i, f ->
                                if (f.decimal) put(f.name!!, parsed[i]!!) else put(f.name!!, parsed[i]!!.toLong())
                            }
                        }
                    }
                    vm.saveConfig(key, value)
                }.onSuccess { snackbar.showSnackbar(saved) }
                    .onFailure { snackbar.showSnackbar(ctx.getString(it.asAppException().messageRes())) }
                busy = false
            }
        }, modifier = Modifier.fillMaxWidth())
    }
}
