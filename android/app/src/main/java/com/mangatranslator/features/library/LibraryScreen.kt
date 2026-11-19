package com.mangatranslator.features.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.mangatranslator.R
import com.mangatranslator.core.common.AppException
import com.mangatranslator.core.common.Formatters
import com.mangatranslator.core.common.asAppException
import com.mangatranslator.core.common.messageRes
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
import com.mangatranslator.core.theme.MtTheme
import com.mangatranslator.core.theme.Radii
import com.mangatranslator.core.theme.Spacing
import com.mangatranslator.data.models.HistoryItem
import com.mangatranslator.data.models.LibraryWork
import com.mangatranslator.data.repositories.LibraryRepository
import com.mangatranslator.data.repositories.TranslationRepository
import com.mangatranslator.features.home.ChapterCard
import com.mangatranslator.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

val CONTENT_TYPES = listOf("manga", "manhwa", "manhua", "webtoon", "comic")

fun contentTypeRes(type: String) = when (type) {
    "manga" -> R.string.type_manga
    "manhua" -> R.string.type_manhua
    "webtoon" -> R.string.type_webtoon
    "comic" -> R.string.type_comic
    else -> R.string.type_manhwa
}

enum class LibrarySort { Recent, Title }

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val translation: TranslationRepository,
) : ViewModel() {
    var works by mutableStateOf<List<LibraryWork>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<AppException?>(null)
        private set
    var query by mutableStateOf("")
    var sort by mutableStateOf(LibrarySort.Recent)
    var favoritesOnly by mutableStateOf(false)

    init { load() }

    fun load(pull: Boolean = false) {
        if (pull) refreshing = true
        viewModelScope.launch {
            try {
                works = library.works(); error = null
            } catch (e: Exception) {
                error = e.asAppException()
            } finally {
                loading = false; refreshing = false
            }
        }
    }

    val visible: List<LibraryWork>
        get() = works
            .filter { !favoritesOnly || it.isFavorite }
            .filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
            .let { list ->
                when (sort) {
                    LibrarySort.Recent -> list.sortedByDescending { it.lastReadAt ?: it.updatedAt ?: it.createdAt }
                    LibrarySort.Title -> list.sortedBy { it.title.lowercase() }
                }
            }

    fun toggleFavorite(w: LibraryWork, onError: (AppException) -> Unit) {
        works = works.map { if (it.id == w.id) it.copy(isFavorite = !w.isFavorite) else it }
        viewModelScope.launch {
            runCatching { library.setFavorite(w.id, !w.isFavorite) }.onFailure {
                works = works.map { if (it.id == w.id) it.copy(isFavorite = w.isFavorite) else it }
                onError(it.asAppException())
            }
        }
    }

    suspend fun create(title: String, type: String) {
        val w = library.create(title, type)
        works = listOf(w) + works
    }

    suspend fun delete(w: LibraryWork) {
        library.delete(w.id)
        works = works.filterNot { it.id == w.id }
    }

    fun coverUrl(path: String?) = path?.let { translation.imageUrl(it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(nav: NavController, vm: LibraryViewModel = hiltViewModel()) {
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<LibraryWork?>(null) }
    val errorText = { e: AppException -> e.messageRes() }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    Column(Modifier.fillMaxSize()) {
        MtTopBar(stringResource(R.string.library_title), actions = {
            IconButton(onClick = { showAdd = true }) { Icon(Icons.Outlined.Add, stringResource(R.string.library_add)) }
        })
        when {
            vm.loading -> LoadingState()
            vm.error != null && vm.works.isEmpty() -> ErrorState(vm.error!!, onRetry = { vm.load() })
            else -> PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.load(pull = true) }) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(Spacing.screen),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            MtTextField(vm.query, { vm.query = it }, stringResource(R.string.action_search))
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                FilterChip(vm.sort == LibrarySort.Recent, { vm.sort = LibrarySort.Recent }, { Text(stringResource(R.string.library_sort_recent)) })
                                FilterChip(vm.sort == LibrarySort.Title, { vm.sort = LibrarySort.Title }, { Text(stringResource(R.string.library_sort_title)) })
                                FilterChip(vm.favoritesOnly, { vm.favoritesOnly = !vm.favoritesOnly }, { Text(stringResource(R.string.library_favorites)) })
                            }
                        }
                    }
                    val list = vm.visible
                    if (list.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState(Icons.Outlined.CollectionsBookmark, stringResource(R.string.library_empty)) {
                                MtButton(stringResource(R.string.library_add), { showAdd = true }, icon = Icons.Outlined.Add)
                            }
                        }
                    } else {
                        items(list, key = { it.id }) { w ->
                            WorkTile(
                                w, vm.coverUrl(w.coverPath),
                                onClick = { nav.navigate(Routes.work(w.id)) },
                                onFavorite = { vm.toggleFavorite(w) { e -> scope.launch { snackbar.showSnackbar(ctx.getString(errorText(e))) } } },
                                onDelete = { toDelete = w },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddWorkDialog(
            onDismiss = { showAdd = false },
            onCreate = { title, type ->
                vm.create(title, type)
                showAdd = false
            },
        )
    }
    toDelete?.let { w ->
        ConfirmDialog(
            title = stringResource(R.string.action_delete),
            text = stringResource(R.string.library_delete_confirm, w.title),
            confirm = stringResource(R.string.action_delete),
            danger = true,
            onDismiss = { toDelete = null },
            onConfirm = {
                toDelete = null
                scope.launch {
                    runCatching { vm.delete(w) }.onFailure { snackbar.showSnackbar(ctx.getString(it.asAppException().messageRes())) }
                }
            },
        )
    }
}

@Composable
private fun WorkTile(w: LibraryWork, cover: String?, onClick: () -> Unit, onFavorite: () -> Unit, onDelete: () -> Unit) {
    MtCard(onClick = onClick, contentPadding = PaddingValues(0.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.72f).background(MtTheme.colors.border)) {
            if (cover != null) {
                AsyncImage(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Outlined.ImageNotSupported, null, tint = MtTheme.colors.textSecondary, modifier = Modifier.align(Alignment.Center).size(32.dp))
            }
            IconButton(onClick = onFavorite, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(
                    if (w.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    stringResource(R.string.library_favorites),
                    tint = if (w.isFavorite) MaterialTheme.colorScheme.primary else MtTheme.colors.textSecondary,
                )
            }
        }
        Column(Modifier.padding(Spacing.md)) {
            Text(w.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(contentTypeRes(w.contentType)) + " · " + stringResource(R.string.library_chapters, w.chapterCount),
                style = MaterialTheme.typography.bodySmall, color = MtTheme.colors.textSecondary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (w.lastReadAt != null) stringResource(R.string.library_last_read, Formatters.date(w.lastReadAt)) else stringResource(R.string.library_never_read),
                    style = MaterialTheme.typography.labelSmall, color = MtTheme.colors.textSecondary, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete), tint = MtTheme.colors.textSecondary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
fun AddWorkDialog(onDismiss: () -> Unit, onCreate: suspend (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("manhwa") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.library_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                MtTextField(title, { title = it; error = null }, stringResource(R.string.library_new_title), error = error)
                Text(stringResource(R.string.library_type), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.fillMaxWidth()) {
                    CONTENT_TYPES.take(3).forEach { t -> FilterChip(type == t, { type = t }, { Text(stringResource(contentTypeRes(t))) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    CONTENT_TYPES.drop(3).forEach { t -> FilterChip(type == t, { type = t }, { Text(stringResource(contentTypeRes(t))) }) }
                }
            }
        },
        confirmButton = {
            MtButton(stringResource(R.string.action_save), loading = busy, onClick = {
                if (title.isBlank()) { error = ctx.getString(R.string.translate_name_required); return@MtButton }
                busy = true
                scope.launch {
                    try { onCreate(title, type) } catch (e: Exception) { error = ctx.getString(e.asAppException().messageRes()) } finally { busy = false }
                }
            })
        },
        dismissButton = { MtButton(stringResource(R.string.action_cancel), onDismiss, variant = ButtonVariant.Ghost, enabled = !busy) },
        containerColor = MtTheme.colors.card,
        shape = RoundedCornerShape(Radii.xl),
    )
}

// ---------------- Work detail ----------------

@HiltViewModel
class WorkViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val library: LibraryRepository,
    private val translation: TranslationRepository,
) : ViewModel() {
    val workId: String = checkNotNull(savedState["workId"])
    var work by mutableStateOf<LibraryWork?>(null)
        private set
    var chapters by mutableStateOf<List<HistoryItem>>(emptyList())
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
                work = library.works().firstOrNull { it.id == workId }
                chapters = library.chapters(workId)
                error = null
            } catch (e: Exception) {
                error = e.asAppException()
            } finally {
                loading = false
            }
        }
    }

    fun coverUrl(path: String?) = path?.let { translation.imageUrl(it) }
}

@Composable
fun WorkScreen(nav: NavController, vm: WorkViewModel = hiltViewModel()) {
    Column(Modifier.fillMaxSize()) {
        MtTopBar(vm.work?.title ?: stringResource(R.string.library_title), onBack = { nav.popBackStack() })
        when {
            vm.loading -> LoadingState()
            vm.error != null -> ErrorState(vm.error!!, onRetry = vm::load)
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.screen),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                val w = vm.work
                if (w?.lastReadJobId != null) {
                    item {
                        MtButton(
                            stringResource(R.string.library_resume),
                            onClick = { nav.navigate(Routes.reader(w.lastReadJobId)) },
                            icon = Icons.Outlined.PlayArrow,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (vm.chapters.isEmpty()) {
                    item {
                        EmptyState(Icons.Outlined.CollectionsBookmark, stringResource(R.string.history_empty)) {
                            MtButton(stringResource(R.string.home_start_translating), { nav.navigate(Routes.TRANSLATE) })
                        }
                    }
                } else {
                    items(vm.chapters, key = { it.id }) { ch ->
                        ChapterCard(ch, vm.coverUrl(ch.coverPath)) {
                            if (ch.status in listOf("completed", "partially_failed")) nav.navigate(Routes.reader(ch.id)) else nav.navigate(Routes.job(ch.id))
                        }
                    }
                }
                item { Spacer(Modifier.height(Spacing.xl)) }
            }
        }
    }
}
