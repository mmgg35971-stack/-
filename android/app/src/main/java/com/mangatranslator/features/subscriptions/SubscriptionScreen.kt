package com.mangatranslator.features.subscriptions

import android.app.Activity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.mangatranslator.R
import com.mangatranslator.core.billing.BillingEvent
import com.mangatranslator.core.billing.BillingManager
import com.mangatranslator.core.billing.PlayOffer
import com.mangatranslator.core.common.AppException
import com.mangatranslator.core.common.asAppException
import com.mangatranslator.core.common.messageRes
import com.mangatranslator.core.designsystem.ButtonVariant
import com.mangatranslator.core.designsystem.ErrorState
import com.mangatranslator.core.designsystem.LoadingState
import com.mangatranslator.core.designsystem.LocalSnackbar
import com.mangatranslator.core.designsystem.MtButton
import com.mangatranslator.core.designsystem.MtCard
import com.mangatranslator.core.designsystem.MtTopBar
import com.mangatranslator.core.designsystem.PlanBadge
import com.mangatranslator.core.theme.MtTheme
import com.mangatranslator.core.theme.Spacing
import com.mangatranslator.data.models.Plan
import com.mangatranslator.data.repositories.AccountRepository
import com.mangatranslator.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SubscriptionViewModel @Inject constructor(
    private val account: AccountRepository,
    private val billing: BillingManager,
) : ViewModel() {
    val me = account.me
    val offers = billing.offers
    val billingAvailable = billing.available
    val events = billing.events
    var restoring by mutableStateOf(false)
        private set
    var plans by mutableStateOf<List<Plan>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<AppException?>(null)
        private set

    init { load() }

    fun load() {
        loading = true
        viewModelScope.launch {
            try {
                // Plans and their limits come from the database, never hard-coded in the app.
                plans = account.plans().sortedBy { it.rank }
                error = null
                // Prices come from Google Play for the plans that have a Play product id.
                launch { runCatching { billing.loadOffers(plans.mapNotNull { p -> p.playProductId?.let { p.id to it } }.toMap()) } }
            } catch (e: Exception) {
                error = e.asAppException()
            } finally {
                loading = false
            }
        }
    }

    fun buy(activity: Activity, offer: PlayOffer) = billing.launch(activity, offer)

    fun restore() {
        restoring = true
        viewModelScope.launch {
            runCatching { billing.restore() }
            restoring = false
        }
    }
}

fun planNameRes(id: String) = when (id) {
    "premium" -> R.string.plan_premium
    "pro" -> R.string.plan_pro
    else -> R.string.plan_free
}

@Composable
private fun priorityText(p: Int) = stringResource(
    when {
        p >= 100 -> R.string.plans_priority_highest
        p >= 50 -> R.string.plans_priority_high
        else -> R.string.plans_priority_normal
    },
)

@Composable
private fun qualityText(levels: List<String>) = levels.map {
    stringResource(
        when (it) {
            "best" -> R.string.quality_best
            "high" -> R.string.quality_high
            else -> R.string.quality_standard
        },
    )
}.joinToString(" · ")

@Composable
fun SubscriptionScreen(nav: NavController, vm: SubscriptionViewModel = hiltViewModel()) {
    val me by vm.me.collectAsStateWithLifecycle()
    val offers by vm.offers.collectAsStateWithLifecycle()
    val billingAvailable by vm.billingAvailable.collectAsStateWithLifecycle()
    val current = me?.entitlements?.planId ?: "free"
    val activity = LocalContext.current as Activity
    val snackbar = LocalSnackbar.current
    val ctx = LocalContext.current

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            val msg = when (e) {
                is BillingEvent.Activated -> ctx.getString(R.string.billing_activated, ctx.getString(planNameRes(e.planId)))
                BillingEvent.Pending -> ctx.getString(R.string.billing_pending)
                BillingEvent.Cancelled -> null
                is BillingEvent.Failed -> ctx.getString(e.error.messageRes())
            }
            msg?.let { snackbar.showSnackbar(it) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        MtTopBar(stringResource(R.string.plans_title), onBack = { nav.popBackStack() })
        when {
            vm.loading -> LoadingState()
            vm.error != null -> ErrorState(vm.error!!, onRetry = vm::load)
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.screen),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                Text(stringResource(R.string.plans_subtitle), style = MaterialTheme.typography.bodyMedium, color = MtTheme.colors.textSecondary)

                vm.plans.forEach { p ->
                    val isCurrent = p.id == current
                    MtCard(Modifier.fillMaxWidth(), highlighted = isCurrent || p.id == "premium") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.displayName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            if (isCurrent) PlanBadge(p.id)
                        }
                        Spacer(Modifier.height(Spacing.sm))
                        Feature(stringResource(R.string.plans_feature_daily), p.dailyChapterLimit?.toString() ?: stringResource(R.string.plans_unlimited))
                        Feature(stringResource(R.string.plans_feature_quality), qualityText(p.features.translationQuality))
                        Feature(stringResource(R.string.plans_feature_priority), priorityText(p.queuePriority))
                        Feature(stringResource(R.string.plans_feature_context), stringResource(if (p.features.contextManager) R.string.plans_yes else R.string.plans_no))
                        Feature(stringResource(R.string.plans_feature_max_pages), p.features.maxPagesPerChapter.toString())
                        if (p.id == "premium") {
                            Spacer(Modifier.height(Spacing.xs))
                            Text(stringResource(R.string.plans_fair_use), style = MaterialTheme.typography.bodySmall, color = MtTheme.colors.textSecondary)
                        }
                        Spacer(Modifier.height(Spacing.md))
                        when {
                            isCurrent -> MtButton(stringResource(R.string.plans_current), {}, enabled = false, variant = ButtonVariant.Secondary, modifier = Modifier.fillMaxWidth())
                            p.id != "free" -> {
                                val offer = offers.firstOrNull { it.planId == p.id }
                                if (offer != null) {
                                    Text(
                                        stringResource(R.string.billing_price, offer.formattedPrice, stringResource(periodRes(offer.billingPeriod))),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Spacer(Modifier.height(Spacing.sm))
                                }
                                // Without a Play price the button stays disabled: the app never sells at a guessed price.
                                MtButton(
                                    stringResource(if (offer != null) R.string.billing_subscribe else R.string.upgrade_plan),
                                    { offer?.let { vm.buy(activity, it) } },
                                    enabled = offer != null,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }

                MtCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Outlined.Info, null, tint = MtTheme.colors.accent)
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            stringResource(
                                when (billingAvailable) {
                                    true -> R.string.billing_terms
                                    false -> R.string.plans_billing_pending
                                    null -> R.string.billing_loading
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(Spacing.md))
                    if (billingAvailable == true) {
                        MtButton(stringResource(R.string.billing_restore), vm::restore, loading = vm.restoring, icon = Icons.Outlined.Restore, variant = ButtonVariant.Ghost, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(Spacing.sm))
                    }
                    MtButton(stringResource(R.string.plans_redeem_instead), { nav.navigate(Routes.REDEEM) }, icon = Icons.Outlined.CardGiftcard, variant = ButtonVariant.Secondary, modifier = Modifier.fillMaxWidth())
                }

                ComparisonTable(vm.plans)
            }
        }
    }
}

/** ISO-8601 billing period (P1M, P1Y, P1W) → label. */
private fun periodRes(period: String) = when {
    period.endsWith("Y") -> R.string.billing_per_year
    period.endsWith("W") -> R.string.billing_per_week
    else -> R.string.billing_per_month
}

@Composable
private fun Feature(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Check, null, tint = MtTheme.colors.success, modifier = Modifier.padding(end = Spacing.sm))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ComparisonTable(plans: List<Plan>) {
    MtCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.plans_compare), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.md))
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            val cell = Modifier.width(96.dp)
            val label = Modifier.width(130.dp)
            Row {
                Spacer(label)
                plans.forEach { Text(it.displayName, cell, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
            }
            HorizontalDivider(Modifier.padding(vertical = Spacing.sm), color = MtTheme.colors.border)
            val yes = stringResource(R.string.plans_yes)
            val no = stringResource(R.string.plans_no)
            val rows: List<Pair<String, (Plan) -> String>> = listOf(
                stringResource(R.string.plans_feature_daily) to { p -> p.dailyChapterLimit?.toString() ?: "∞" },
                stringResource(R.string.plans_feature_library) to { _ -> yes },
                stringResource(R.string.plans_feature_history) to { _ -> yes },
                stringResource(R.string.plans_feature_context) to { p -> if (p.features.contextManager) yes else no },
                stringResource(R.string.plans_feature_advanced) to { p -> if (p.features.advancedRendering) yes else no },
            )
            rows.forEach { (name, value) ->
                Row(Modifier.padding(vertical = Spacing.xs)) {
                    Text(name, label, style = MaterialTheme.typography.bodySmall, color = MtTheme.colors.textSecondary)
                    plans.forEach { Text(value(it), cell, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
                }
            }
        }
    }
}
