package com.mangatranslator.features.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.mangatranslator.R
import com.mangatranslator.core.common.AppException
import com.mangatranslator.core.common.Formatters
import com.mangatranslator.core.common.asAppException
import com.mangatranslator.core.common.messageRes
import com.mangatranslator.core.designsystem.ConfirmDialog
import com.mangatranslator.core.designsystem.EmptyState
import com.mangatranslator.core.designsystem.ErrorState
import com.mangatranslator.core.designsystem.LoadingState
import com.mangatranslator.core.designsystem.LocalSnackbar
import com.mangatranslator.core.designsystem.MtTextField
import com.mangatranslator.core.designsystem.MtTopBar
import com.mangatranslator.core.theme.MtTheme
import com.mangatranslator.core.theme.Spacing
import com.mangatranslator.data.models.HistoryItem
import com.mangatranslator.data.repositories.HistoryRepository
import com.mangatranslator.data.repositories.TranslationRepository
import com.mangatranslator.features.home.ChapterCard
import com.mangatranslator.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

private val ACTIVE = setOf("uploading", "queued", "ocr", "translating", "rendering")

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val history: HistoryRepository,
    private val translation: TranslationRepository,
) : ViewModel() {
    var items by mutableStateOf<List<HistoryItem>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<AppException?>(null)
        private set
    var query by mutableStateOf("")
        private set
    private var searchJob: Job? = null

    init { load() }

    fun load(pull: Boolean = false) {
        if (pull) refreshing = true
        viewModelScope.launch {
            try {
                items = history.list(query); error = null
            } catch (e: Exception) {
                error = e.asAppException()
            } finally {
                loading = false; refreshing = false
            }
        }
    }

    /** Debounced server-side search over chapter and work titles. */
    fun search(q: String) {
        query = q
        searchJob?.cancel()
        searchJob = viewModelScope.launch { delay(350); load() }
    }

    suspend fun delete(item: HistoryItem) {
        history.delete(item.id)
        items = items.filterNot { it.id == item.id }
    }

    fun coverUrl(item: HistoryItem) = item.coverPath?.let { translation.imageUrl(it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(nav: NavController, vm: HistoryViewModel = hiltViewModel()) {
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var toDelete by remember { mutableStateOf<HistoryItem?>(null) }
    LaunchedEffect(Unit) { if (!vm.loading) vm.load() }

    Column(Modifier.fillMaxSize()) {
        MtTopBar(stringResource(R.string.history_title))
        MtTextField(vm.query, vm::search, stringResource(R.string.history_search), modifier = Modifier.padding(horizontal = Spacing.screen))
        when {
            vm.loading -> LoadingState()
            vm.error != null && vm.items.isEmpty() -> ErrorState(vm.error!!, onRetry = { vm.load() })
            else -> PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.load(pull = true) }) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(Spacing.screen),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    if (vm.items.isEmpty()) {
                        item { EmptyState(Icons.Outlined.History, stringResource(R.string.history_empty)) }
                    }
                    items(vm.items, key = { it.id }) { item ->
                        Column {
                            ChapterCard(item, vm.coverUrl(item)) {
                                if (item.status in listOf("completed", "partially_failed")) nav.navigate(Routes.reader(item.id))
                                else nav.navigate(Routes.job(item.id))
                            }
                            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                                val meta = buildList {
                                    add(Formatters.dateTime(item.createdAt))
                                    if (item.processingMs != null) add(ctx.getString(R.string.history_duration, Formatters.seconds(item.processingMs)))
                                    add(ctx.getString(if (item.isSaved) R.string.history_saved else R.string.history_not_saved))
                                }.joinToString(" · ")
                                Text(meta, style = MaterialTheme.typography.labelSmall, color = MtTheme.colors.textSecondary, modifier = Modifier.weight(1f))
                                // Running jobs cannot be deleted (RLS refuses); hide the action instead of failing.
                                if (item.status !in ACTIVE) {
                                    IconButton(onClick = { toDelete = item }) {
                                        Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete), tint = MtTheme.colors.textSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    toDelete?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.action_delete),
            text = stringResource(R.string.history_delete_confirm),
            confirm = stringResource(R.string.action_delete),
            danger = true,
            onDismiss = { toDelete = null },
            onConfirm = {
                toDelete = null
                scope.launch {
                    runCatching { vm.delete(item) }.onFailure { snackbar.showSnackbar(ctx.getString(it.asAppException().messageRes())) }
                }
            },
        )
    }
}
