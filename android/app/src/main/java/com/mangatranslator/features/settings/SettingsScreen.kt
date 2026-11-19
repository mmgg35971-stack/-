package com.mangatranslator.features.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.core.os.LocaleListCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import com.mangatranslator.R
import com.mangatranslator.core.common.AppException
import com.mangatranslator.core.common.Formatters
import com.mangatranslator.core.common.asAppException
import com.mangatranslator.core.common.messageRes
import com.mangatranslator.core.designsystem.EmptyState
import com.mangatranslator.core.designsystem.ErrorState
import com.mangatranslator.core.designsystem.LoadingState
import com.mangatranslator.core.designsystem.LocalSnackbar
import com.mangatranslator.core.designsystem.MtButton
import com.mangatranslator.core.designsystem.ButtonVariant
import com.mangatranslator.core.designsystem.MtCard
import com.mangatranslator.core.designsystem.MtTopBar
import com.mangatranslator.core.designsystem.SectionHeader
import com.mangatranslator.core.theme.MtTheme
import com.mangatranslator.core.theme.Spacing
import com.mangatranslator.data.models.AppNotification
import com.mangatranslator.data.models.UserSettings
import com.mangatranslator.data.repositories.AccountRepository
import com.mangatranslator.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val account: AccountRepository) : ViewModel() {
    val settings = account.settings
    val me = account.me

    init { viewModelScope.launch { runCatching { account.loadSettings() } } }

    /** Optimistic update; the repository rolls back and rethrows if the server refuses. */
    fun update(onError: (AppException) -> Unit, transform: (UserSettings) -> UserSettings) {
        viewModelScope.launch {
            runCatching { account.updateSettings(transform) }.onFailure { onError(it.asAppException()) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(nav: NavController, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val me by vm.me.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val onError: (AppException) -> Unit = { e -> scope.launch { snackbar.showSnackbar(ctx.getString(e.messageRes())) } }
    fun set(t: (UserSettings) -> UserSettings) = vm.update(onError, t)
    val allowedQualities = me?.entitlements?.features?.translationQuality ?: listOf("standard")

    Column(Modifier.fillMaxSize()) {
        MtTopBar(stringResource(R.string.settings_title), onBack = { nav.popBackStack() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            // ---- Reading ----
            SettingsGroup(stringResource(R.string.settings_reading)) {
                Label(R.string.settings_reading_direction)
                Choices(
                    listOf("vertical" to R.string.dir_vertical, "rtl" to R.string.dir_rtl, "ltr" to R.string.dir_ltr),
                    s.readingDirection,
                ) { v -> set { it.copy(readingDirection = v) } }
                Toggle(R.string.settings_fullscreen, s.readerFullscreen) { v -> set { it.copy(readerFullscreen = v) } }
                Toggle(R.string.settings_show_original, s.showOriginalText) { v -> set { it.copy(showOriginalText = v) } }
                Label(R.string.settings_image_quality)
                Choices(
                    listOf("data_saver" to R.string.iq_data_saver, "balanced" to R.string.iq_balanced, "high" to R.string.iq_high, "original" to R.string.iq_original),
                    s.imageQuality,
                ) { v -> set { it.copy(imageQuality = v) } }
                Label(R.string.settings_brightness)
                SliderSetting(s.readerBrightness, 0.1f..1f) { v -> set { it.copy(readerBrightness = v) } }
                Label(R.string.settings_arabic_font)
                Choices(
                    listOf("cairo" to R.string.font_cairo, "ibm_plex_sans_arabic" to R.string.font_plex, "noto_sans_arabic" to R.string.font_noto),
                    s.arabicFont,
                ) { v -> set { it.copy(arabicFont = v) } }
                Label(R.string.settings_font_scale)
                SliderSetting(s.fontScale, 0.6f..1.8f) { v -> set { it.copy(fontScale = v) } }
            }

            // ---- Translation ----
            SettingsGroup(stringResource(R.string.settings_translation)) {
                Label(R.string.settings_quality)
                // Only qualities included in the user's plan; the server enforces this again.
                Choices(
                    listOf("standard" to R.string.quality_standard, "high" to R.string.quality_high, "best" to R.string.quality_best)
                        .filter { it.first in allowedQualities },
                    s.translationQuality,
                ) { v -> set { it.copy(translationQuality = v) } }
                Toggle(R.string.settings_use_glossary, s.useGlossary) { v -> set { it.copy(useGlossary = v) } }
                Toggle(R.string.settings_training_consent, s.allowTrainingUse) { v -> set { it.copy(allowTrainingUse = v) } }
            }

            // ---- App ----
            SettingsGroup(stringResource(R.string.settings_app)) {
                Label(R.string.settings_language)
                Choices(listOf("ar" to R.string.lang_ar, "en" to R.string.lang_en), s.appLanguage) { v ->
                    set { it.copy(appLanguage = v) }
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(v))
                }
                Label(R.string.settings_theme)
                Choices(
                    listOf("dark" to R.string.theme_dark, "light" to R.string.theme_light, "system" to R.string.theme_system),
                    s.theme,
                ) { v -> set { it.copy(theme = v) } }
                Toggle(R.string.settings_notifications, s.notificationsEnabled) { v -> set { it.copy(notificationsEnabled = v) } }
                MtButton(stringResource(R.string.settings_clear_cache), {
                    scope.launch {
                        val freed = clearCache(ctx)
                        snackbar.showSnackbar(ctx.getString(R.string.settings_cache_cleared, Formatters.bytes(freed)))
                    }
                }, variant = ButtonVariant.Secondary, modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm))
            }

            MtCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = Spacing.xs)) {
                LinkRow(R.string.settings_privacy) { nav.navigate(Routes.legal("privacy")) }
                LinkRow(R.string.settings_terms) { nav.navigate(Routes.legal("terms")) }
                LinkRow(R.string.settings_about) { nav.navigate(Routes.legal("about")) }
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

/** Clears image and temp caches. Drafts live in Room and app files, so they are not touched. */
@OptIn(ExperimentalCoilApi::class)
private suspend fun clearCache(ctx: Context): Long = withContext(Dispatchers.IO) {
    fun size(f: File): Long = if (f.isDirectory) f.listFiles()?.sumOf(::size) ?: 0 else f.length()
    val before = size(ctx.cacheDir)
    ctx.imageLoader.memoryCache?.clear()
    ctx.imageLoader.diskCache?.clear()
    ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    (before - size(ctx.cacheDir)).coerceAtLeast(0)
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader(title)
        MtCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) { content() }
        }
    }
}

@Composable
private fun Label(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.labelLarge, color = MtTheme.colors.textSecondary)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choices(options: List<Pair<String, Int>>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        options.forEach { (v, res) -> FilterChip(selected == v, { if (selected != v) onSelect(v) }, { Text(stringResource(res)) }) }
    }
}

@Composable
private fun Toggle(res: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(res), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

/** Saves on release, not on every drag frame. */
@Composable
private fun SliderSetting(value: Float, range: ClosedFloatingPointRange<Float>, onCommit: (Float) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(local, { local = it }, valueRange = range, onValueChangeFinished = { onCommit((local * 100).toInt() / 100f) }, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(Spacing.sm))
        Text("${(local * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LinkRow(res: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Spacing.lg, vertical = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(res), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MtTheme.colors.textSecondary)
    }
}

// ---------------- Legal / About ----------------

@Composable
fun LegalScreen(nav: NavController) {
    val kind = nav.currentBackStackEntry?.arguments?.getString("kind") ?: "about"
    val (title, body) = when (kind) {
        "privacy" -> R.string.settings_privacy to R.string.privacy_body
        "terms" -> R.string.settings_terms to R.string.terms_body
        else -> R.string.settings_about to R.string.about_body
    }
    Column(Modifier.fillMaxSize()) {
        MtTopBar(stringResource(title), onBack = { nav.popBackStack() })
        Text(
            stringResource(body),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.screen),
        )
    }
}

// ---------------- Notifications ----------------

@HiltViewModel
class NotificationsViewModel @Inject constructor(private val account: AccountRepository) : ViewModel() {
    var items by mutableStateOf<List<AppNotification>>(emptyList())
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
                items = account.notifications(); error = null
                if (items.any { it.readAt == null }) runCatching { account.markAllRead() }
            } catch (e: Exception) {
                error = e.asAppException()
            } finally {
                loading = false
            }
        }
    }
}

@Composable
fun NotificationsContent(nav: NavController, vm: NotificationsViewModel) {
    Column(Modifier.fillMaxSize()) {
        MtTopBar(stringResource(R.string.notifications), onBack = { nav.popBackStack() })
        when {
            vm.loading -> LoadingState()
            vm.error != null -> ErrorState(vm.error!!, onRetry = vm::load)
            vm.items.isEmpty() -> EmptyState(Icons.Outlined.NotificationsNone, stringResource(R.string.notifications_empty))
            else -> LazyColumn(contentPadding = PaddingValues(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items(vm.items, key = { it.id }) { n ->
                    val jobId = (n.data as? kotlinx.serialization.json.JsonObject)?.get("job_id")
                        ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    MtCard(
                        Modifier.fillMaxWidth(),
                        highlighted = n.readAt == null,
                        onClick = jobId?.let { id -> { nav.navigate(Routes.job(id)) } },
                    ) {
                        Text(n.title, style = MaterialTheme.typography.titleSmall)
                        Text(n.body, style = MaterialTheme.typography.bodyMedium)
                        Text(Formatters.dateTime(n.createdAt), style = MaterialTheme.typography.labelSmall, color = MtTheme.colors.textSecondary)
                    }
                }
            }
        }
    }
}
