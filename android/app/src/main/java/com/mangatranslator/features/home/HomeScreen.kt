package com.mangatranslator.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.mangatranslator.R
import com.mangatranslator.core.common.AppException
import com.mangatranslator.core.common.Formatters
import com.mangatranslator.core.common.asAppException
import com.mangatranslator.core.designsystem.Avatar
import com.mangatranslator.core.designsystem.ButtonVariant
import com.mangatranslator.core.designsystem.EmptyState
import com.mangatranslator.core.designsystem.ErrorState
import com.mangatranslator.core.designsystem.LoadingState
import com.mangatranslator.core.designsystem.MtButton
import com.mangatranslator.core.designsystem.MtCard
import com.mangatranslator.core.designsystem.MtProgressBar
import com.mangatranslator.core.designsystem.PlanBadge
import com.mangatranslator.core.designsystem.SectionHeader
import com.mangatranslator.core.designsystem.StatusChip
import com.mangatranslator.core.theme.MtTheme
import com.mangatranslator.core.theme.Radii
import com.mangatranslator.core.theme.Spacing
import com.mangatranslator.data.models.Entitlements
import com.mangatranslator.data.models.HistoryItem
import com.mangatranslator.data.repositories.AccountRepository
import com.mangatranslator.data.repositories.HistoryRepository
import com.mangatranslator.data.repositories.TranslationRepository
import com.mangatranslator.navigation.Routes
import com.mangatranslator.navigation.switchTab
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val account: AccountRepository,
    private val history: HistoryRepository,
    private val translation: TranslationRepository,
) : ViewModel() {
    val me = account.me
    var recent by mutableStateOf<List<HistoryItem>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<AppException?>(null)
        private set

    init { load() }

    fun load(pull: Boolean = false) {
        if (pull) refreshing = true else if (me.value == null) loading = true
        viewModelScope.launch {
            try {
                val m = async { account.refreshMe() }
                val r = async { history.list(limit = 5) }
                val s = async { runCatching { account.loadSettings() } }
                m.await(); recent = r.await(); s.await()
                error = null
            } catch (e: Exception) {
                error = e.asAppException()
            } finally {
                loading = false
                refreshing = false
            }
        }
    }

    fun coverUrl(item: HistoryItem): String? = item.coverPath?.let { translation.imageUrl(it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavController, vm: HomeViewModel = hiltViewModel()) {
    val me by vm.me.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (!vm.loading) vm.load() }

    when {
        me == null && vm.loading -> LoadingState()
        me == null && vm.error != null -> ErrorState(vm.error!!, onRetry = { vm.load() })
        me != null -> PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.load(pull = true) }) {
            val profile = me!!.profile
            val ent = me!!.entitlements
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.screen),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(profile.avatarUrl, profile.displayName, size = 44.dp)
                        Spacer(Modifier.width(Spacing.md))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.home_welcome, profile.displayName ?: ""),
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(stringResource(R.string.home_welcome_sub), style = MaterialTheme.typography.bodySmall, color = MtTheme.colors.textSecondary)
                        }
                        PlanBadge(ent.planId)
                        IconButton(onClick = { nav.navigate(Routes.NOTIFICATIONS) }) {
                            Icon(Icons.Outlined.NotificationsNone, contentDescription = stringResource(R.string.notifications))
                        }
                    }
                }
                item { UsageCard(ent) }
                item {
                    MtButton(
                        stringResource(R.string.home_start_translating),
                        onClick = { nav.switchTab(Routes.TRANSLATE) },
                        icon = Icons.Outlined.Bolt,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    SectionHeader(stringResource(R.string.home_recent)) {
                        if (vm.recent.isNotEmpty()) {
                            MtButton(stringResource(R.string.home_see_all), { nav.switchTab(Routes.HISTORY) }, variant = ButtonVariant.Ghost)
                        }
                    }
                }
                if (vm.recent.isEmpty()) {
                    item { EmptyState(Icons.Outlined.AutoStories, stringResource(R.string.home_recent_empty)) }
                } else {
                    items(vm.recent, key = { it.id }) { item ->
                        ChapterCard(item, vm.coverUrl(item)) {
                            if (item.status in listOf("completed", "partially_failed")) nav.navigate(Routes.reader(item.id))
                            else nav.navigate(Routes.job(item.id))
                        }
                    }
                }
                item { PlanCard(ent) { nav.navigate(Routes.PLANS) } }
            }
        }
    }
}

@Composable
fun UsageCard(ent: Entitlements) {
    MtCard(Modifier.fillMaxWidth(), highlighted = true) {
        Text(stringResource(R.string.home_translated_today), style = MaterialTheme.typography.labelLarge, color = MtTheme.colors.textSecondary)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            if (ent.isUnlimited) stringResource(R.string.home_chapters_unlimited, ent.usedToday)
            else stringResource(R.string.home_chapters_of, ent.usedToday, ent.dailyLimit ?: 0),
            style = MaterialTheme.typography.headlineSmall,
        )
        if (!ent.isUnlimited) {
            Spacer(Modifier.height(Spacing.md))
            MtProgressBar(ent.progress, color = if ((ent.remainingToday ?: 0) == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(Spacing.sm))
            Text(stringResource(R.string.home_remaining, ent.remainingToday ?: 0), style = MaterialTheme.typography.bodyMedium)
        }
        Text(stringResource(R.string.home_limit_resets), style = MaterialTheme.typography.bodySmall, color = MtTheme.colors.textSecondary)
    }
}

@Composable
fun ChapterCard(item: HistoryItem, coverUrl: String?, onClick: () -> Unit) {
    MtCard(Modifier.fillMaxWidth(), onClick = onClick, contentPadding = PaddingValues(Spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(width = 56.dp, height = 76.dp).clip(RoundedCornerShape(Radii.sm)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.AutoStories, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                if (coverUrl != null) AsyncImage(coverUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(item.workTitle ?: item.chapterTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.workTitle != null) {
                    Text(
                        item.chapterNumber?.let { "#${formatNumber(it)} · ${item.chapterTitle}" } ?: item.chapterTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MtTheme.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "${Formatters.date(item.createdAt)} · ${stringResource(R.string.pages_count, item.pageCount)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MtTheme.colors.textSecondary,
                )
                StatusChip(item.status)
            }
        }
    }
}

private fun formatNumber(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

@Composable
fun PlanCard(ent: Entitlements, onClick: () -> Unit) {
    MtCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                PlanBadge(ent.planId)
                if (ent.planId == "free") {
                    Text(stringResource(R.string.home_plan_free_pitch), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(stringResource(R.string.home_plan_active_until, Formatters.date(ent.planExpiresAt)), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (ent.planId != "premium") MtButton(stringResource(R.string.home_upgrade), onClick, variant = ButtonVariant.Secondary)
        }
    }
}
