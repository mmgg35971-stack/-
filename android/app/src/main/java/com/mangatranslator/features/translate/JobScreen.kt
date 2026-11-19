package com.mangatranslator.features.translate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.mangatranslator.R
import com.mangatranslator.core.common.AppException
import com.mangatranslator.core.common.asAppException
import com.mangatranslator.core.designsystem.ButtonVariant
import com.mangatranslator.core.designsystem.ErrorState
import com.mangatranslator.core.designsystem.LoadingState
import com.mangatranslator.core.designsystem.MtButton
import com.mangatranslator.core.designsystem.MtCard
import com.mangatranslator.core.designsystem.MtProgressBar
import com.mangatranslator.core.designsystem.MtTopBar
import com.mangatranslator.core.designsystem.StatusChip
import com.mangatranslator.core.theme.MtTheme
import com.mangatranslator.core.theme.Spacing
import com.mangatranslator.data.models.JobRow
import com.mangatranslator.data.models.PageRow
import com.mangatranslator.data.repositories.TranslationRepository
import com.mangatranslator.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

private val TERMINAL = setOf("completed", "partially_failed", "failed", "cancelled")

@HiltViewModel
class JobViewModel @Inject constructor(
    private val repo: TranslationRepository,
    saved: SavedStateHandle,
) : ViewModel() {
    val jobId: String = checkNotNull(saved["jobId"])
    var job by mutableStateOf<JobRow?>(null)
        private set
    var pages by mutableStateOf<List<PageRow>>(emptyList())
        private set
    var error by mutableStateOf<AppException?>(null)
        private set
    var retrying by mutableStateOf<String?>(null)
        private set

    /** Real progress from the server, polled until the job reaches a final state. */
    private var pollJob: kotlinx.coroutines.Job? = null

    init { poll() }

    private fun poll() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch { pollLoop() }
    }

    private suspend fun pollLoop() = kotlinx.coroutines.coroutineScope {
        while (isActive) {
            try {
                job = repo.job(jobId)
                pages = repo.pages(jobId)
                error = null
            } catch (e: Exception) {
                error = e.asAppException()
            }
            if (job?.status in TERMINAL) break
            delay(3000)
        }
    }

    fun retryPage(pageId: String) = viewModelScope.launch {
        retrying = pageId
        try {
            repo.retryPage(pageId)
            poll()
        } catch (e: Exception) {
            error = e.asAppException()
        } finally {
            retrying = null
        }
    }

    fun reload() = poll()
}

@Composable
fun JobScreen(nav: NavController, vm: JobViewModel = hiltViewModel()) {
    val job = vm.job
    Column(Modifier.fillMaxSize()) {
        MtTopBar(stringResource(R.string.job_title), onBack = { nav.popBackStack() })
        when {
            job == null && vm.error != null -> ErrorState(vm.error!!, onRetry = vm::reload)
            job == null -> LoadingState()
            else -> LazyColumn(
                contentPadding = PaddingValues(Spacing.screen),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                item {
                    MtCard(Modifier.fillMaxWidth(), highlighted = true) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(job.chapterTitle, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            StatusChip(job.status)
                        }
                        val done = vm.pages.count { it.status == "completed" }
                        val total = job.pageCount
                        Text(stringResource(R.string.job_progress_pages, done, total), style = MaterialTheme.typography.bodyMedium)
                        MtProgressBar(if (total == 0) 0f else done.toFloat() / total)
                        if (job.status !in TERMINAL) {
                            Text(stringResource(R.string.job_background_hint), style = MaterialTheme.typography.bodySmall, color = MtTheme.colors.textSecondary)
                        }
                        if (job.usageRefunded) {
                            Text(stringResource(R.string.job_refunded), style = MaterialTheme.typography.bodySmall, color = MtTheme.colors.success)
                        }
                    }
                }
                val failed = vm.pages.filter { it.status == "failed" }
                if (failed.isNotEmpty()) {
                    item { Text(stringResource(R.string.job_failed_pages, failed.size), color = MtTheme.colors.warning, style = MaterialTheme.typography.bodyMedium) }
                    items(failed, key = { it.id }) { p ->
                        MtCard(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.reader_page_of, p.pageIndex + 1, job.pageCount), modifier = Modifier.weight(1f))
                                if (!job.usageRefunded) {
                                    MtButton(stringResource(R.string.job_retry_page), { vm.retryPage(p.id) }, variant = ButtonVariant.Secondary, loading = vm.retrying == p.id)
                                }
                            }
                            p.errorCode?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MtTheme.colors.textSecondary) }
                        }
                    }
                }
                if (job.status == "completed" || job.status == "partially_failed") {
                    item {
                        MtButton(stringResource(R.string.job_open_reader), { nav.navigate(Routes.reader(job.id)) }, icon = Icons.AutoMirrored.Outlined.MenuBook, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
