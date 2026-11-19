package com.mangatranslator.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import coil.compose.AsyncImage
import com.mangatranslator.R
import com.mangatranslator.core.common.AppException
import com.mangatranslator.core.common.messageRes
import com.mangatranslator.core.theme.MtColors
import com.mangatranslator.core.theme.MtTheme
import com.mangatranslator.core.theme.Radii
import com.mangatranslator.core.theme.Spacing

/** App-wide toast/snackbar channel. */
val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

enum class ButtonVariant { Primary, Secondary, Ghost, Danger }

/**
 * The one button of the app. States: normal, pressed (scale + colour),
 * loading (spinner, not clickable), disabled.
 */
@Composable
fun MtButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(120), label = "btnScale")
    val active = enabled && !loading
    val colors = MaterialTheme.colorScheme
    val (bg, fg, border) = when (variant) {
        ButtonVariant.Primary -> Triple(if (pressed) MtColors.PurpleLight else colors.primary, Color.White, null)
        ButtonVariant.Secondary -> Triple(MtTheme.colors.card, colors.onSurface, BorderStroke(1.dp, MtTheme.colors.border))
        ButtonVariant.Ghost -> Triple(Color.Transparent, colors.primary, null)
        ButtonVariant.Danger -> Triple(colors.error.copy(alpha = 0.12f), colors.error, BorderStroke(1.dp, colors.error.copy(alpha = 0.4f)))
    }
    val bgAnimated by animateColorAsState(if (active) bg else bg.copy(alpha = 0.4f), label = "btnBg")

    Surface(
        modifier = modifier
            .heightIn(min = 48.dp)
            .scale(scale)
            .clip(RoundedCornerShape(Radii.md))
            .clickable(interactionSource = interaction, indication = null, enabled = active, role = Role.Button, onClick = onClick),
        color = bgAnimated,
        shape = RoundedCornerShape(Radii.md),
        border = border,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.md),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = fg, strokeWidth = 2.dp)
            } else {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = fg.copy(alpha = if (active) 1f else 0.5f), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Spacing.sm))
                }
                Text(text, style = MaterialTheme.typography.labelLarge, color = fg.copy(alpha = if (active) 1f else 0.5f), maxLines = 1)
            }
        }
    }
}

@Composable
fun MtTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = MtTheme.colors.textSecondary) } },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(Radii.md),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MtTheme.colors.border,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedContainerColor = MtTheme.colors.card,
            focusedContainerColor = MtTheme.colors.card,
            disabledContainerColor = MtTheme.colors.card.copy(alpha = 0.5f),
        ),
    )
}

@Composable
fun MtCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    highlighted: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(Spacing.lg),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Radii.lg)
    val borderBrush = if (highlighted) {
        Brush.linearGradient(listOf(MtColors.Purple, MtColors.Accent))
    } else {
        Brush.linearGradient(listOf(MtTheme.colors.border, MtTheme.colors.border))
    }
    Column(
        modifier = modifier
            .clip(shape)
            .background(MtTheme.colors.card)
            .border(1.dp, borderBrush, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun PlanBadge(planId: String, modifier: Modifier = Modifier) {
    val (label, bg, fg) = when (planId) {
        "premium" -> Triple(stringResource(R.string.plan_premium), Brush.linearGradient(listOf(MtColors.Purple, MtColors.Accent)), Color.White)
        "pro" -> Triple(stringResource(R.string.plan_pro), Brush.linearGradient(listOf(Color(0xFF2A1F4D), Color(0xFF2A1F4D))), MtColors.PurpleLight)
        else -> Triple(stringResource(R.string.plan_free), Brush.linearGradient(listOf(MtTheme.colors.border, MtTheme.colors.border)), MtTheme.colors.textSecondary)
    }
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = Spacing.md, vertical = Spacing.xxs),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Avatar(url: String?, name: String?, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val initial = name?.trim()?.firstOrNull()?.uppercase() ?: "؟"
        Text(initial, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        if (!url.isNullOrBlank()) {
            AsyncImage(model = url, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun MtProgressBar(progress: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(600), label = "progress")
    LinearProgressIndicator(
        progress = { animated },
        modifier = modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
        color = color,
        trackColor = MtTheme.colors.border,
        strokeCap = StrokeCap.Round,
        drawStopIndicator = {},
    )
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp)
    }
}

@Composable
fun EmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp)) }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MtTheme.colors.textSecondary, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

@Composable
fun ErrorState(error: AppException, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(
        icon = if (error.isNetwork) Icons.Outlined.CloudOff else Icons.Outlined.ErrorOutline,
        text = stringResource(error.messageRes()),
        modifier = modifier,
        action = { MtButton(stringResource(R.string.action_retry), onRetry, variant = ButtonVariant.Secondary) },
    )
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MtTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    CenterAlignedTopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    // AutoMirrored flips automatically in RTL.
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        // The root Scaffold already applies system-bar insets.
        windowInsets = WindowInsets(0, 0, 0, 0),
    )
}

@Composable
fun StatusChip(status: String, modifier: Modifier = Modifier) {
    val (label, color) = when (status) {
        "completed" -> R.string.status_completed to MtTheme.colors.success
        "partially_failed" -> R.string.status_partially_failed to MtTheme.colors.warning
        "failed" -> R.string.status_failed to MaterialTheme.colorScheme.error
        "cancelled" -> R.string.status_cancelled to MtTheme.colors.textSecondary
        "queued" -> R.string.status_queued to MtTheme.colors.accent
        "ocr" -> R.string.status_ocr to MtTheme.colors.accent
        "translating" -> R.string.status_translating to MtTheme.colors.accent
        "rendering" -> R.string.status_rendering to MtTheme.colors.accent
        "uploading" -> R.string.status_uploading to MtTheme.colors.accent
        else -> R.string.status_draft to MtTheme.colors.textSecondary
    }
    Row(
        modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(Spacing.xs))
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** "Daily Limit Reached" dialog from the spec, with Upgrade Plan. */
@Composable
fun LimitReachedDialog(onUpgrade: () -> Unit, onDismiss: () -> Unit, fairUse: Boolean = false) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (fairUse) R.string.fair_use_title else R.string.limit_reached_title)) },
        text = { Text(stringResource(if (fairUse) R.string.fair_use_body else R.string.limit_reached_body)) },
        confirmButton = { if (!fairUse) MtButton(stringResource(R.string.upgrade_plan), onUpgrade) },
        dismissButton = { MtButton(stringResource(R.string.action_close), onDismiss, variant = ButtonVariant.Ghost) },
        containerColor = MtTheme.colors.card,
        shape = RoundedCornerShape(Radii.xl),
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = false) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { MtButton(confirm, onConfirm, variant = if (danger) ButtonVariant.Danger else ButtonVariant.Primary) },
        dismissButton = { MtButton(stringResource(R.string.action_cancel), onDismiss, variant = ButtonVariant.Ghost) },
        containerColor = MtTheme.colors.card,
        shape = RoundedCornerShape(Radii.xl),
    )
}
