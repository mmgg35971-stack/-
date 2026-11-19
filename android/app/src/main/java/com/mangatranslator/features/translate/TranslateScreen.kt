package com.mangatranslator.features.translate

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.work.WorkInfo
import coil.compose.AsyncImage
import com.mangatranslator.R
import com.mangatranslator.core.common.Formatters
import com.mangatranslator.core.common.AppException
import com.mangatranslator.core.common.messageRes
import com.mangatranslator.core.database.DraftEntity
import com.mangatranslator.core.database.DraftPageEntity
import com.mangatranslator.core.designsystem.ButtonVariant
import com.mangatranslator.core.designsystem.EmptyState
import com.mangatranslator.core.designsystem.LimitReachedDialog
import com.mangatranslator.core.designsystem.LocalSnackbar
import com.mangatranslator.core.designsystem.MtButton
import com.mangatranslator.core.designsystem.MtCard
import com.mangatranslator.core.designsystem.MtProgressBar
import com.mangatranslator.core.designsystem.MtTextField
import com.mangatranslator.core.designsystem.MtTopBar
import com.mangatranslator.core.designsystem.SectionHeader
import com.mangatranslator.core.theme.MtTheme
import com.mangatranslator.core.theme.Radii
import com.mangatranslator.core.theme.Spacing
import com.mangatranslator.data.models.LibraryWork
import com.mangatranslator.data.repositories.AccountRepository
import com.mangatranslator.data.repositories.LibraryRepository
import com.mangatranslator.data.repositories.TranslationRepository
import com.mangatranslator.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TranslateViewModel @Inject constructor(
    private val repo: TranslationRepository,
    private val library: LibraryRepository,
    account: AccountRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    val me = account.me
    private val settings = account.settings
    private val draftId = MutableStateFlow(saved.get<String>("draft"))

    val drafts = repo.openDrafts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val draft = draftId.flatMapLatest { id -> id?.let { repo.observeDraft(it) } ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val pages = draftId.flatMapLatest { id -> id?.let { repo.observePages(it) } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val submission = draftId.flatMapLatest { id -> id?.let { repo.observeSubmission(it) } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var works by mutableStateOf<List<LibraryWork>>(emptyList())
        private set
    var importing by mutableStateOf(false)
        private set
    var message by mutableStateOf<Int?>(null)

    init {
        viewModelScope.launch { works = runCatching { library.works() }.getOrDefault(emptyList()) }
    }

    private fun select(id: String?) {
        saved["draft"] = id
        draftId.value = id
    }

    fun openDraft(id: String) = select(id)
    fun closeDraft() = select(null)

    private suspend fun ensureDraft(): String =
        draftId.value ?: repo.newDraft(settings.value.translationQuality).id.also { select(it) }

    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            importing = true
            val skipped = repo.addImages(ensureDraft(), uris)
            if (skipped > 0) message = R.string.translate_import_failed
            importing = false
        }
    }

    fun addPdf(uri: Uri?) {
        uri ?: return
        viewModelScope.launch {
            importing = true
            runCatching { repo.addPdf(ensureDraft(), uri) }.onFailure { message = R.string.translate_import_failed }
            importing = false
        }
    }

    fun pasteFromClipboard(context: Context) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val clip = cm.primaryClip
        val uris = (0 until (clip?.itemCount ?: 0)).mapNotNull { clip?.getItemAt(it)?.uri }
        if (uris.isEmpty()) message = R.string.translate_clipboard_empty else addImages(uris)
    }

    fun update(transform: (DraftEntity) -> DraftEntity) {
        val d = draft.value ?: return
        viewModelScope.launch { repo.updateDraft(transform(d)) }
    }

    fun move(index: Int, delta: Int) {
        val list = pages.value.toMutableList()
        val target = index + delta
        if (target !in list.indices) return
        list.add(target, list.removeAt(index))
        viewModelScope.launch { repo.setPageOrder(list.first().draftId, list) }
    }

    fun remove(page: DraftPageEntity) {
        viewModelScope.launch { repo.setPageOrder(page.draftId, pages.value.filter { it.position != page.position }) }
    }

    fun sortByName() {
        val list = pages.value
        if (list.isEmpty()) return
        val natural = compareBy<DraftPageEntity> { naturalKey(it.displayName) }
        viewModelScope.launch { repo.setPageOrder(list.first().draftId, list.sortedWith(natural)) }
    }

    fun submit(): Boolean {
        val d = draft.value ?: return false
        if (d.chapterTitle.isBlank()) {
            message = R.string.translate_name_required
            return false
        }
        repo.submit(d.id)
        return true
    }

    fun discard() {
        val id = draftId.value ?: return
        viewModelScope.launch {
            repo.discardDraft(id)
            select(null)
        }
    }

    /** "page 2" < "page 10" (natural sort), so chapters keep file-name order. */
    private fun naturalKey(name: String): String =
        Regex("\\d+").replace(name.lowercase()) { it.value.padStart(8, '0') }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TranslateScreen(nav: NavController, vm: TranslateViewModel = hiltViewModel()) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    val pages by vm.pages.collectAsStateWithLifecycle()
    val drafts by vm.drafts.collectAsStateWithLifecycle()
    val submission by vm.submission.collectAsStateWithLifecycle()
    val me by vm.me.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { vm.addImages(it) }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.addPdf(it) }
    var limitDialog by remember { mutableStateOf<String?>(null) }

    val message = vm.message
    val messageText = message?.let { stringResource(it) }
    LaunchedEffect(message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            vm.message = null
        }
    }

    // React to the background submission.
    val work = submission.firstOrNull()
    val errorCode = work?.outputData?.getString(UploadChapterWorker.KEY_ERROR)
    val errorText = errorCode?.let { stringResource(AppException(it).messageRes()) }
    LaunchedEffect(work?.state) {
        when (work?.state) {
            WorkInfo.State.SUCCEEDED -> work.outputData.getString(UploadChapterWorker.KEY_JOB)?.let { jobId ->
                vm.closeDraft()
                nav.navigate(Routes.job(jobId))
            }
            WorkInfo.State.FAILED -> when (errorCode) {
                "daily_limit_reached", "fair_use_review" -> limitDialog = errorCode
                else -> errorText?.let { snackbar.showSnackbar(it) }
            }
            else -> Unit
        }
    }
    limitDialog?.let { code ->
        LimitReachedDialog(
            fairUse = code == "fair_use_review",
            onUpgrade = { limitDialog = null; nav.navigate(Routes.PLANS) },
            onDismiss = { limitDialog = null },
        )
    }

    val submitting = work?.state == WorkInfo.State.RUNNING || work?.state == WorkInfo.State.ENQUEUED || draft?.state == "submitting"

    Column(Modifier.fillMaxSize()) {
        MtTopBar(stringResource(R.string.translate_title), onBack = draft?.let { { vm.closeDraft() } })
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = Spacing.screen, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (draft == null) {
                item {
                    EmptyState(Icons.Outlined.AddPhotoAlternate, stringResource(R.string.translate_empty))
                }
                item { PickButtons(pickImages = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, pickPdf = { pickPdf.launch(arrayOf("application/pdf")) }, paste = { vm.pasteFromClipboard(context) }, loading = vm.importing) }
                item { Text(stringResource(R.string.translate_rights_note), style = MaterialTheme.typography.bodySmall, color = MtTheme.colors.textSecondary) }
                if (drafts.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.translate_drafts)) }
                    items(drafts, key = { it.id }) { d ->
                        MtCard(Modifier.fillMaxWidth(), onClick = { vm.openDraft(d.id) }) {
                            Text(d.chapterTitle.ifBlank { "—" }, style = MaterialTheme.typography.titleSmall)
                            Text(Formatters.dateTime(java.time.Instant.ofEpochMilli(d.createdAt).toString()), style = MaterialTheme.typography.bodySmall, color = MtTheme.colors.textSecondary)
                        }
                    }
                }
            } else {
                val d = draft!!
                item {
                    MtTextField(d.chapterTitle, { v -> vm.update { it.copy(chapterTitle = v.take(200)) } }, stringResource(R.string.translate_chapter_name))
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        MtTextField(
                            d.chapterNumber?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "",
                            { v -> vm.update { it.copy(chapterNumber = v.toDoubleOrNull()) } },
                            stringResource(R.string.translate_chapter_number),
                            keyboardType = KeyboardType.Decimal,
                            modifier = Modifier.weight(1f),
                        )
                        WorkPicker(vm.works, d.workId, Modifier.weight(1.4f)) { id -> vm.update { it.copy(workId = id) } }
                    }
                }
                item {
                    val allowed = me?.entitlements?.features?.translationQuality ?: listOf("standard")
                    Text(stringResource(R.string.translate_quality), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        listOf("standard" to R.string.quality_standard, "high" to R.string.quality_high, "best" to R.string.quality_best).forEach { (q, label) ->
                            FilterChip(
                                selected = d.quality == q,
                                enabled = q in allowed,
                                onClick = { vm.update { it.copy(quality = q) } },
                                label = { Text(stringResource(label)) },
                            )
                        }
                    }
                }
                item {
                    SectionHeader(stringResource(R.string.translate_pages_count, pages.size, Formatters.bytes(pages.sumOf { it.byteSize }))) {
                        IconButton(onClick = vm::sortByName) { Icon(Icons.AutoMirrored.Outlined.Sort, stringResource(R.string.translate_sort_name)) }
                    }
                }
                itemsIndexed(pages, key = { _, p -> p.localPath }) { i, p ->
                    PageRow(p, i, pages.size, onUp = { vm.move(i, -1) }, onDown = { vm.move(i, 1) }, onRemove = { vm.remove(p) }, enabled = !submitting)
                }
                item {
                    MtButton(
                        stringResource(R.string.translate_add_pages),
                        { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        variant = ButtonVariant.Secondary,
                        icon = Icons.Outlined.AddPhotoAlternate,
                        loading = vm.importing,
                        enabled = !submitting,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    MtButton(stringResource(R.string.action_delete), vm::discard, variant = ButtonVariant.Ghost, enabled = !submitting, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        if (draft != null) {
            Column(Modifier.padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (submitting) {
                    val up = work?.progress?.getInt(UploadChapterWorker.KEY_UPLOADED, 0) ?: 0
                    val total = work?.progress?.getInt(UploadChapterWorker.KEY_TOTAL, pages.size) ?: pages.size
                    Text(stringResource(R.string.job_upload_progress, up, total), style = MaterialTheme.typography.bodySmall)
                    MtProgressBar(if (total == 0) 0f else up.toFloat() / total)
                }
                MtButton(
                    stringResource(R.string.translate_start),
                    { vm.submit() },
                    icon = Icons.Outlined.PlayArrow,
                    enabled = pages.isNotEmpty() && !vm.importing,
                    loading = submitting,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun PickButtons(pickImages: () -> Unit, pickPdf: () -> Unit, paste: () -> Unit, loading: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        MtButton(stringResource(R.string.translate_pick_images), pickImages, icon = Icons.Outlined.AddPhotoAlternate, loading = loading, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            MtButton(stringResource(R.string.translate_pick_pdf), pickPdf, variant = ButtonVariant.Secondary, icon = Icons.Outlined.PictureAsPdf, enabled = !loading, modifier = Modifier.weight(1f))
            MtButton(stringResource(R.string.translate_paste), paste, variant = ButtonVariant.Secondary, icon = Icons.Outlined.ContentPaste, enabled = !loading, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PageRow(p: DraftPageEntity, index: Int, total: Int, onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit, enabled: Boolean) {
    MtCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = File(p.localPath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 52.dp, height = 72.dp).clip(RoundedCornerShape(Radii.sm)),
            )
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text("${index + 1}", style = MaterialTheme.typography.titleSmall)
                Text(p.displayName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MtTheme.colors.textSecondary)
                Text(Formatters.bytes(p.byteSize), style = MaterialTheme.typography.labelSmall, color = MtTheme.colors.textSecondary)
            }
            IconButton(onClick = onUp, enabled = enabled && index > 0) { Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.translate_move_up)) }
            IconButton(onClick = onDown, enabled = enabled && index < total - 1) { Icon(Icons.Outlined.ArrowDownward, stringResource(R.string.translate_move_down)) }
            IconButton(onClick = onRemove, enabled = enabled) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.translate_remove_page), tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkPicker(works: List<LibraryWork>, selected: String?, modifier: Modifier, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val none = stringResource(R.string.translate_work_none)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = works.firstOrNull { it.id == selected }?.title ?: none,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.translate_work)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            shape = RoundedCornerShape(Radii.md),
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(none) }, onClick = { onSelect(null); expanded = false })
            works.forEach { w -> DropdownMenuItem(text = { Text(w.title) }, onClick = { onSelect(w.id); expanded = false }) }
        }
    }
}
