package com.mangatranslator.features.reader

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.mangatranslator.R
import com.mangatranslator.core.common.messageRes
import com.mangatranslator.core.common.AppException
import com.mangatranslator.core.common.asAppException
import com.mangatranslator.core.designsystem.ButtonVariant
import com.mangatranslator.core.designsystem.ErrorState
import com.mangatranslator.core.designsystem.LoadingState
import com.mangatranslator.core.designsystem.LocalSnackbar
import com.mangatranslator.core.designsystem.MtButton
import com.mangatranslator.core.designsystem.MtTextField
import com.mangatranslator.core.designsystem.MtTopBar
import com.mangatranslator.core.theme.MtTheme
import com.mangatranslator.core.theme.PageFonts
import com.mangatranslator.core.theme.Spacing
import com.mangatranslator.data.models.JobRow
import com.mangatranslator.data.models.PageRow
import com.mangatranslator.data.models.SegmentRow
import com.mangatranslator.data.repositories.AccountRepository
import com.mangatranslator.data.repositories.LibraryRepository
import com.mangatranslator.data.repositories.TranslationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val repo: TranslationRepository,
    private val library: LibraryRepository,
    account: AccountRepository,
    saved: SavedStateHandle,
) : ViewModel() {
    val jobId: String = checkNotNull(saved["jobId"])
    val settings = account.settings

    var job by mutableStateOf<JobRow?>(null)
        private set
    var pages by mutableStateOf<List<PageRow>>(emptyList())
        private set
    var segments by mutableStateOf<Map<String, List<SegmentRow>>>(emptyMap())
        private set
    var error by mutableStateOf<AppException?>(null)
        private set

    init { load() }

    fun load() = viewModelScope.launch {
        try {
            job = repo.job(jobId)
            pages = repo.pages(jobId)
            segments = repo.segments(jobId).groupBy { it.pageId }
            error = null
        } catch (e: Exception) {
            error = e.asAppException()
        }
    }

    fun imageUrl(p: PageRow) = repo.imageUrl(p.originalPath)
    val needsReview get() = segments.values.sumOf { list -> list.count { it.needsReview || it.displayText.isBlank() } }

    /** Saves the edit on the server; the UI updates immediately so nothing is lost on exit. */
    suspend fun saveEdit(seg: SegmentRow, text: String) {
        segments = segments.mapValues { (_, list) -> list.map { if (it.id == seg.id) it.copy(userEditedText = text, needsReview = false) else it } }
        repo.saveSegmentEdit(seg.id, text)
    }

    /**
     * Asks the server for a fresh translation of one bubble. A user edit is kept unless
     * [replaceEdit] is true; the returned segment replaces the local one.
     */
    suspend fun retranslate(seg: SegmentRow, hint: String?, replaceEdit: Boolean): SegmentRow {
        val res = repo.retranslateSegment(seg.id, hint, replaceEdit)
        val updated = seg.copy(
            translatedText = res.translatedText,
            userEditedText = if (replaceEdit) null else seg.userEditedText,
            needsReview = false,
        )
        segments = segments.mapValues { (_, list) -> list.map { if (it.id == seg.id) updated else it } }
        return updated
    }

    suspend fun addToGlossary(source: String, target: String) = repo.addGlossary(job?.workId, source, target)
    suspend fun report(seg: SegmentRow) = repo.reportSegment(seg.id, "user_report")

    fun recordPage(index: Int) {
        val workId = job?.workId ?: return
        viewModelScope.launch { runCatching { library.recordRead(workId, jobId, index) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ReaderScreen(nav: NavController, vm: ReaderViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(if (settings.showOriginalText) ViewMode.Compare else ViewMode.Translated) }
    var editMode by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<SegmentRow?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    val fontFamily = PageFonts.byId(settings.arabicFont)

    FullscreenAndBrightness(enabled = settings.readerFullscreen && !controls, brightness = settings.readerBrightness)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            vm.error != null && vm.pages.isEmpty() -> ErrorState(vm.error!!, onRetry = { vm.load() })
            vm.pages.isEmpty() -> LoadingState()
            else -> {
                val zoom = rememberTransformableState { z, _, _ -> scale = (scale * z).coerceIn(1f, 3f) }
                val content = Modifier
                    .fillMaxSize()
                    .transformable(zoom)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                val tap = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { controls = !controls }

                if (settings.readingDirection == "vertical") {
                    val list = rememberLazyListState()
                    LaunchedEffect(list) { snapshotFlow { list.firstVisibleItemIndex }.debounce(1500).collect { vm.recordPage(it) } }
                    LazyColumn(content.then(tap), state = list) {
                        itemsIndexed(vm.pages, key = { _, p -> p.id }) { _, p ->
                            TranslatedPage(p, vm.imageUrl(p), vm.segments[p.id].orEmpty(), mode, fontFamily, settings.fontScale, editMode, { editing = it }, decodeWidth = decodeWidthFor(settings.imageQuality))
                        }
                    }
                } else {
                    val pager = rememberPagerState { vm.pages.size }
                    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.debounce(1500).collect { vm.recordPage(it) } }
                    HorizontalPager(
                        state = pager,
                        modifier = content.then(tap),
                        reverseLayout = settings.readingDirection == "rtl",
                        verticalAlignment = Alignment.CenterVertically,
                    ) { i ->
                        val p = vm.pages[i]
                        TranslatedPage(p, vm.imageUrl(p), vm.segments[p.id].orEmpty(), mode, fontFamily, settings.fontScale, editMode, { editing = it }, decodeWidth = decodeWidthFor(settings.imageQuality))
                    }
                }
            }
        }

        AnimatedVisibility(controls, modifier = Modifier.align(Alignment.TopCenter)) {
            Column(Modifier.background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f))) {
                MtTopBar(vm.job?.chapterTitle ?: "", onBack = { nav.popBackStack() }) {
                    IconButton(onClick = { editMode = !editMode }) {
                        Icon(Icons.Outlined.Edit, stringResource(R.string.reader_edit_mode), tint = if (editMode) MaterialTheme.colorScheme.primary else MtTheme.colors.textSecondary)
                    }
                }
                if (editMode && vm.needsReview > 0) {
                    Text(
                        stringResource(R.string.reader_needs_review, vm.needsReview),
                        color = MtTheme.colors.warning,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.xs),
                    )
                }
            }
        }
        AnimatedVisibility(controls, modifier = Modifier.align(Alignment.BottomCenter)) {
            SingleChoiceSegmentedButtonRow(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f)).padding(Spacing.md),
            ) {
                val options = listOf(ViewMode.Original to R.string.reader_original, ViewMode.Compare to R.string.reader_compare, ViewMode.Translated to R.string.reader_translated)
                options.forEachIndexed { i, (m, label) ->
                    SegmentedButton(selected = mode == m, onClick = { mode = m }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) {
                        Text(stringResource(label))
                    }
                }
            }
        }
    }

    editing?.let { seg -> SegmentEditorSheet(seg, vm, onDismiss = { editing = null }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SegmentEditorSheet(seg: SegmentRow, vm: ReaderViewModel, onDismiss: () -> Unit) {
    var text by remember(seg.id) { mutableStateOf(seg.displayText) }
    var glossary by remember { mutableStateOf(false) }
    var source by remember(seg.id) { mutableStateOf(seg.originalText) }
    var busy by remember { mutableStateOf(false) }
    var retranslating by remember { mutableStateOf(false) }
    var showHint by remember { mutableStateOf(false) }
    var hint by remember(seg.id) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val ctx = LocalContext.current
    val saved = stringResource(R.string.editor_saved)
    val retranslated = stringResource(R.string.editor_retranslated)
    val added = stringResource(R.string.glossary_added)
    val reported = stringResource(R.string.editor_reported)
    val failed = stringResource(R.string.error_generic)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MtTheme.colors.card) {
        Column(Modifier.padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(stringResource(R.string.editor_original), style = MaterialTheme.typography.labelLarge, color = MtTheme.colors.textSecondary)
            Text(seg.originalText.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge)
            if (seg.needsReview) Text(stringResource(R.string.editor_unreadable), color = MtTheme.colors.warning, style = MaterialTheme.typography.bodySmall)
            MtTextField(text, { text = it }, stringResource(R.string.editor_translation), singleLine = false, minLines = 2)
            if (glossary) {
                MtTextField(source, { source = it }, stringResource(R.string.glossary_source))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                MtButton(stringResource(R.string.action_save), {
                    scope.launch {
                        busy = true
                        runCatching {
                            vm.saveEdit(seg, text)
                            if (glossary && source.isNotBlank() && text.isNotBlank()) vm.addToGlossary(source, text)
                        }.onSuccess { snackbar.showSnackbar(if (glossary) added else saved); onDismiss() }
                            .onFailure { snackbar.showSnackbar(failed) }
                        busy = false
                    }
                }, loading = busy, modifier = Modifier.weight(1f))
                MtButton(stringResource(R.string.editor_add_glossary), { glossary = !glossary }, variant = if (glossary) ButtonVariant.Primary else ButtonVariant.Secondary, modifier = Modifier.weight(1f))
            }
            if (seg.originalText.isNotBlank()) {
                if (showHint) {
                    MtTextField(hint, { hint = it.take(300) }, stringResource(R.string.editor_retranslate_hint))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    MtButton(stringResource(R.string.editor_retranslate), {
                        scope.launch {
                            retranslating = true
                            // If the user typed their own wording, the new translation goes into the
                            // field for review instead of silently overwriting their edit.
                            val hadEdit = seg.userEditedText != null
                            runCatching { vm.retranslate(seg, hint.takeIf { showHint }, replaceEdit = !hadEdit) }
                                .onSuccess { text = it.translatedText ?: text; snackbar.showSnackbar(retranslated) }
                                .onFailure { snackbar.showSnackbar(ctx.getString(it.asAppException().messageRes())) }
                            retranslating = false
                        }
                    }, loading = retranslating, enabled = !busy, variant = ButtonVariant.Secondary, modifier = Modifier.weight(1f))
                    MtButton(stringResource(R.string.editor_retranslate_with_hint), { showHint = !showHint }, variant = if (showHint) ButtonVariant.Primary else ButtonVariant.Ghost, modifier = Modifier.weight(1f))
                }
            }
            MtButton(stringResource(R.string.editor_report), {
                scope.launch {
                    runCatching { vm.report(seg) }.onSuccess { snackbar.showSnackbar(reported) }.onFailure { snackbar.showSnackbar(failed) }
                }
            }, variant = ButtonVariant.Ghost, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(Spacing.lg))
        }
    }
}

@Composable
private fun FullscreenAndBrightness(enabled: Boolean, brightness: Float) {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(enabled, brightness) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        val attrs = window.attributes
        attrs.screenBrightness = brightness.coerceIn(0.1f, 1f)
        window.attributes = attrs
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            val a = window.attributes
            a.screenBrightness = -1f // back to system brightness
            window.attributes = a
        }
    }
}

/** Image quality setting → decode width. Uploaded originals are at most 2000px wide (PageImporter). */
fun decodeWidthFor(quality: String): Int = when (quality) {
    "data_saver" -> 720
    "balanced" -> 1080
    "original" -> 2000
    else -> 1440
}
