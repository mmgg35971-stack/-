package com.mangatranslator.features.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.mangatranslator.R
import com.mangatranslator.core.common.AppException
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
import com.mangatranslator.data.models.GlossaryEntry
import com.mangatranslator.data.models.StoryCharacter
import com.mangatranslator.data.repositories.AccountRepository
import com.mangatranslator.data.repositories.GLOSSARY_TYPES
import com.mangatranslator.data.repositories.LibraryRepository
import com.mangatranslator.data.repositories.StoryMemoryRepository
import com.mangatranslator.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

fun glossaryTypeRes(type: String) = when (type) {
    "character" -> R.string.glossary_type_character
    "skill" -> R.string.glossary_type_skill
    "weapon" -> R.string.glossary_type_weapon
    "place" -> R.string.glossary_type_place
    "kingdom" -> R.string.glossary_type_kingdom
    "clan" -> R.string.glossary_type_clan
    "rank" -> R.string.glossary_type_rank
    "ability" -> R.string.glossary_type_ability
    "military" -> R.string.glossary_type_military
    "magic" -> R.string.glossary_type_magic
    else -> R.string.glossary_type_term
}

@HiltViewModel
class StoryMemoryViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: StoryMemoryRepository,
    private val library: LibraryRepository,
    account: AccountRepository,
) : ViewModel() {
    val workId: String = checkNotNull(savedState["workId"])
    val me = account.me

    var title by mutableStateOf("")
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<AppException?>(null)
        private set

    // Memory being edited (saved only when the user taps Save).
    private var exists = false
    var summary by mutableStateOf("")
    var style by mutableStateOf("")
    var frozen by mutableStateOf(false)
    val characters = mutableStateListOf<StoryCharacter>()
    var dirty by mutableStateOf(false)
    var saving by mutableStateOf(false)
        private set

    var terms by mutableStateOf<List<GlossaryEntry>>(emptyList())
        private set

    init { load() }

    fun load() {
        loading = true
        viewModelScope.launch {
            try {
                title = library.works().firstOrNull { it.id == workId }?.title.orEmpty()
                val ctx = repo.context(workId)
                exists = ctx != null
                summary = ctx?.storySummary.orEmpty()
                style = ctx?.dialogueStyle.orEmpty()
                frozen = ctx?.memoryFrozen ?: false
                characters.clear(); characters.addAll(ctx?.characters.orEmpty())
                terms = repo.glossary(workId)
                dirty = false
                error = null
            } catch (e: Exception) {
                error = e.asAppException()
            } finally {
                loading = false
            }
        }
    }

    suspend fun save() {
        saving = true
        try {
            repo.saveContext(workId, exists, summary, characters.toList(), style, frozen)
            exists = true
            dirty = false
        } finally {
            saving = false
        }
    }

    /** Empties the memory now; the next chapter starts a new one unless the memory is frozen. */
    suspend fun clearMemory() {
        summary = ""; style = ""; characters.clear()
        save()
    }

    suspend fun setFrozen(value: Boolean) {
        val before = frozen
        frozen = value
        try { save() } catch (e: Exception) { frozen = before; throw e }
    }

    suspend fun confirm(t: GlossaryEntry) {
        repo.confirmTerm(t.id)
        terms = terms.map { if (it.id == t.id) it.copy(status = "confirmed") else it }
    }

    suspend fun confirmAll() {
        terms.filter { it.isSuggested }.forEach { repo.confirmTerm(it.id) }
        terms = terms.map { it.copy(status = "confirmed") }
    }

    suspend fun delete(t: GlossaryEntry) {
        repo.deleteTerm(t.id)
        terms = terms.filterNot { it.id == t.id }
    }

    suspend fun saveTerm(existing: GlossaryEntry?, source: String, target: String, type: String) {
        if (existing == null) {
            terms = listOf(repo.addTerm(workId, source, target, type)) + terms
        } else {
            repo.updateTerm(existing.id, source, target, type)
            terms = terms.map {
                if (it.id == existing.id) it.copy(sourceTerm = source.trim(), targetTerm = target.trim(), termType = type, status = "confirmed") else it
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryMemoryScreen(nav: NavController, vm: StoryMemoryViewModel = hiltViewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val pending = vm.terms.count { it.isSuggested }
    Column(Modifier.fillMaxSize()) {
        MtTopBar(
            vm.title.ifBlank { stringResource(R.string.memory_title) },
            onBack = { nav.popBackStack() },
        )
        PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.memory_tab_memory)) })
            Tab(
                selected = tab == 1, onClick = { tab = 1 },
                text = {
                    Text(
                        if (pending > 0) stringResource(R.string.memory_tab_glossary_pending, pending) else stringResource(R.string.memory_tab_glossary),
                    )
                },
            )
        }
        when {
            vm.loading -> LoadingState()
            vm.error != null -> ErrorState(vm.error!!, onRetry = vm::load)
            tab == 0 -> MemoryTab(vm, nav)
            else -> GlossaryTab(vm)
        }
    }
}

@Composable
private fun MemoryTab(vm: StoryMemoryViewModel, nav: NavController) {
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val me by vm.me.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Int?>(null) } // index, or -1 for a new character
    var confirmClear by remember { mutableStateOf(false) }
    val usedInTranslation = me?.entitlements?.features?.contextManager == true

    fun act(block: suspend () -> Unit, done: Int? = null) {
        scope.launch {
            runCatching { block() }
                .onSuccess { done?.let { snackbar.showSnackbar(ctx.getString(it)) } }
                .onFailure { snackbar.showSnackbar(ctx.getString(it.asAppException().messageRes())) }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (!usedInTranslation) {
            item {
                MtCard(Modifier.fillMaxWidth(), highlighted = true, onClick = { nav.navigate(Routes.PLANS) }) {
                    Text(stringResource(R.string.memory_plan_note), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            MtCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().clickable { act({ vm.setFrozen(!vm.frozen) }) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.memory_freeze), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.memory_freeze_hint), style = MaterialTheme.typography.bodySmall, color = MtTheme.colors.textSecondary)
                    }
                    Switch(vm.frozen, { v -> act({ vm.setFrozen(v) }) })
                }
            }
        }
        item {
            MtTextField(
                vm.summary, { vm.summary = it.take(4000); vm.dirty = true }, stringResource(R.string.memory_summary),
                singleLine = false, minLines = 4, placeholder = stringResource(R.string.memory_summary_empty),
            )
        }
        item {
            MtTextField(
                vm.style, { vm.style = it.take(1000); vm.dirty = true }, stringResource(R.string.memory_style),
                singleLine = false, minLines = 2, placeholder = stringResource(R.string.memory_style_hint),
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.memory_characters, vm.characters.size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (vm.characters.size < 60) {
                    IconButton(onClick = { editing = -1 }) { Icon(Icons.Outlined.Add, stringResource(R.string.memory_add_character)) }
                }
            }
        }
        if (vm.characters.isEmpty()) {
            item {
                Text(stringResource(R.string.memory_no_characters), style = MaterialTheme.typography.bodySmall, color = MtTheme.colors.textSecondary)
            }
        }
        items(vm.characters.size) { i ->
            val c = vm.characters[i]
            MtCard(Modifier.fillMaxWidth(), onClick = { editing = i }, contentPadding = PaddingValues(Spacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (c.arabic.isBlank()) c.name else "${c.arabic} · ${c.name}",
                            style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (c.notes.isNotBlank()) {
                            Text(c.notes, style = MaterialTheme.typography.bodySmall, color = MtTheme.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    IconButton(onClick = { vm.characters.removeAt(i); vm.dirty = true }) {
                        Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete), tint = MtTheme.colors.textSecondary)
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                MtButton(
                    stringResource(R.string.action_save), { act({ vm.save() }, R.string.memory_saved) },
                    enabled = vm.dirty, loading = vm.saving, modifier = Modifier.fillMaxWidth(),
                )
                MtButton(
                    stringResource(R.string.memory_clear), { confirmClear = true },
                    variant = ButtonVariant.Danger, enabled = !vm.saving, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item { Spacer(Modifier.height(Spacing.xl)) }
    }

    editing?.let { index ->
        CharacterDialog(
            initial = vm.characters.getOrNull(index),
            onDismiss = { editing = null },
            onSave = { c ->
                if (index in vm.characters.indices) vm.characters[index] = c else vm.characters.add(c)
                vm.dirty = true
                editing = null
            },
        )
    }
    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.memory_clear),
            text = stringResource(R.string.memory_clear_confirm),
            confirm = stringResource(R.string.memory_clear),
            danger = true,
            onDismiss = { confirmClear = false },
            onConfirm = { confirmClear = false; act({ vm.clearMemory() }, R.string.memory_cleared) },
        )
    }
}

@Composable
private fun GlossaryTab(vm: StoryMemoryViewModel) {
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf<GlossaryEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    val suggested = vm.terms.filter { it.isSuggested }
    val confirmed = vm.terms.filterNot { it.isSuggested }

    fun act(block: suspend () -> Unit) {
        scope.launch { runCatching { block() }.onFailure { snackbar.showSnackbar(ctx.getString(it.asAppException().messageRes())) } }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item {
            MtButton(stringResource(R.string.glossary_add_term), { adding = true }, icon = Icons.Outlined.Add, modifier = Modifier.fillMaxWidth())
        }
        if (suggested.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.md)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.glossary_suggested, suggested.size), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.glossary_suggested_hint), style = MaterialTheme.typography.bodySmall, color = MtTheme.colors.textSecondary)
                    }
                    MtButton(stringResource(R.string.glossary_confirm_all), { act { vm.confirmAll() } }, variant = ButtonVariant.Ghost)
                }
            }
            items(suggested, key = { it.id }) { t ->
                TermRow(t, onEdit = { editing = t }) {
                    IconButton(onClick = { act { vm.confirm(t) } }) {
                        Icon(Icons.Outlined.Check, stringResource(R.string.glossary_confirm), tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { act { vm.delete(t) } }) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.glossary_reject), tint = MtTheme.colors.textSecondary)
                    }
                }
            }
        }
        item {
            Text(
                stringResource(R.string.glossary_confirmed, confirmed.size),
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.md),
            )
        }
        if (confirmed.isEmpty()) {
            item { EmptyState(Icons.Outlined.Translate, stringResource(R.string.glossary_empty)) }
        }
        items(confirmed, key = { it.id }) { t ->
            TermRow(t, onEdit = { editing = t }) {
                IconButton(onClick = { editing = t }) { Icon(Icons.Outlined.Edit, stringResource(R.string.action_edit), tint = MtTheme.colors.textSecondary) }
                IconButton(onClick = { act { vm.delete(t) } }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete), tint = MtTheme.colors.textSecondary) }
            }
        }
        item { Spacer(Modifier.height(Spacing.xl)) }
    }

    if (adding || editing != null) {
        TermDialog(
            initial = editing,
            onDismiss = { adding = false; editing = null },
            onSave = { s, t, type ->
                vm.saveTerm(editing, s, t, type)
                adding = false; editing = null
            },
        )
    }
}

@Composable
private fun TermRow(t: GlossaryEntry, onEdit: () -> Unit, actions: @Composable () -> Unit) {
    MtCard(Modifier.fillMaxWidth(), onClick = onEdit, contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.targetTerm, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.sourceTerm, style = MaterialTheme.typography.bodySmall, color = MtTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(glossaryTypeRes(t.termType)) + if (t.workId == null) " · " + stringResource(R.string.glossary_general) else "",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                )
            }
            actions()
        }
    }
}

@Composable
private fun CharacterDialog(initial: StoryCharacter?, onDismiss: () -> Unit, onSave: (StoryCharacter) -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var arabic by remember { mutableStateOf(initial?.arabic.orEmpty()) }
    var notes by remember { mutableStateOf(initial?.notes.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.memory_add_character else R.string.memory_edit_character)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                MtTextField(name, { name = it.take(80) }, stringResource(R.string.memory_character_name))
                MtTextField(arabic, { arabic = it.take(80) }, stringResource(R.string.memory_character_arabic))
                MtTextField(notes, { notes = it.take(300) }, stringResource(R.string.memory_character_notes), singleLine = false, minLines = 2)
            }
        },
        confirmButton = {
            MtButton(stringResource(R.string.action_save), { onSave(StoryCharacter(name.trim(), arabic.trim(), notes.trim())) }, enabled = name.isNotBlank())
        },
        dismissButton = { MtButton(stringResource(R.string.action_cancel), onDismiss, variant = ButtonVariant.Ghost) },
        containerColor = MtTheme.colors.card,
        shape = RoundedCornerShape(Radii.xl),
    )
}

@Composable
private fun TermDialog(initial: GlossaryEntry?, onDismiss: () -> Unit, onSave: suspend (String, String, String) -> Unit) {
    var source by remember { mutableStateOf(initial?.sourceTerm.orEmpty()) }
    var target by remember { mutableStateOf(initial?.targetTerm.orEmpty()) }
    var type by remember { mutableStateOf(initial?.termType ?: "term") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (initial == null) R.string.glossary_add_term else R.string.glossary_edit_term)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                MtTextField(source, { source = it.take(120); error = null }, stringResource(R.string.glossary_source), error = error)
                MtTextField(target, { target = it.take(120) }, stringResource(R.string.glossary_target))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    GLOSSARY_TYPES.forEach { t -> FilterChip(type == t, { type = t }, { Text(stringResource(glossaryTypeRes(t))) }) }
                }
            }
        },
        confirmButton = {
            MtButton(
                stringResource(R.string.action_save), loading = busy,
                enabled = source.isNotBlank() && target.isNotBlank(),
                onClick = {
                    busy = true
                    scope.launch {
                        try { onSave(source, target, type) } catch (e: Exception) { error = ctx.getString(e.asAppException().messageRes()) } finally { busy = false }
                    }
                },
            )
        },
        dismissButton = { MtButton(stringResource(R.string.action_cancel), onDismiss, variant = ButtonVariant.Ghost, enabled = !busy) },
        containerColor = MtTheme.colors.card,
        shape = RoundedCornerShape(Radii.xl),
    )
}

/** Entry card on the work screen. */
@Composable
fun StoryMemoryEntry(onClick: () -> Unit) {
    MtCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoStories, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = Spacing.md)) {
                Text(stringResource(R.string.memory_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.memory_entry_hint), style = MaterialTheme.typography.bodySmall, color = MtTheme.colors.textSecondary)
            }
        }
    }
}
