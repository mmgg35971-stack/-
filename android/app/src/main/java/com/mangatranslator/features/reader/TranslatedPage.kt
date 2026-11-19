package com.mangatranslator.features.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.mangatranslator.R
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Dimension
import coil.size.Size
import com.mangatranslator.data.models.PageRow
import com.mangatranslator.data.models.SegmentRow
import com.mangatranslator.core.theme.MtColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Translated / Original, Compare (swipe reveal), SideBySide (the reader shows two
 * copies) and Overlay (translation drawn with adjustable opacity).
 */
enum class ViewMode { Translated, Original, Compare, SideBySide, Overlay }

/**
 * Arabic Text Rendering engine (on-device). The original image is never
 * modified: translations are drawn as an overlay in each OCR box, so the user
 * can compare and edit at any time. Android's text stack handles Arabic
 * shaping, ligatures and RTL natively.
 *
 * Bubble colours are sampled from the image (not assumed white), and text
 * colour is chosen for contrast.
 */
@Composable
fun TranslatedPage(
    page: PageRow,
    imageUrl: String,
    segments: List<SegmentRow>,
    mode: ViewMode,
    fontFamily: FontFamily,
    fontScale: Float,
    editMode: Boolean,
    onSegmentClick: (SegmentRow) -> Unit,
    modifier: Modifier = Modifier,
    decodeWidth: Int = 1080,
    overlayAlpha: Float = 1f,
) {
    val context = LocalContext.current
    var bitmap by remember(imageUrl, decodeWidth) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(imageUrl, decodeWidth) { mutableStateOf(false) }

    LaunchedEffect(imageUrl, decodeWidth) {
        // Downsampled software bitmap: lets us sample colours and keeps memory bounded on long webtoon strips.
        val req = ImageRequest.Builder(context).data(imageUrl).allowHardware(false)
            .size(Size(Dimension(decodeWidth), Dimension.Undefined)).build()
        val result = context.imageLoader.execute(req)
        bitmap = ((result as? SuccessResult)?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        failed = bitmap == null
    }

    val ratio = when {
        bitmap != null -> bitmap!!.width.toFloat() / bitmap!!.height
        page.width != null && page.height != null && page.height > 0 -> page.width.toFloat() / page.height
        else -> 0.7f
    }

    val bubbleColors = remember(bitmap, segments) {
        bitmap?.let { bmp -> segments.associate { it.id to dominantColor(bmp, it) } } ?: emptyMap()
    }
    var split by remember { mutableFloatStateOf(0.5f) }
    val handleLabel = stringResource(R.string.reader_compare_handle)

    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.05f, 5f)).clipToBounds()) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val density = LocalDensity.current

        if (bitmap != null) {
            Image(bitmap!!.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        } else if (!failed) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 2.dp)
        }

        if (mode != ViewMode.Original) {
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (mode == ViewMode.Overlay) Modifier.graphicsLayer { alpha = overlayAlpha } else Modifier)
                    .then(
                        if (mode == ViewMode.Compare) {
                            Modifier
                                .pointerInput(Unit) {
                                    detectHorizontalDragGestures { change, drag ->
                                        change.consume()
                                        split = (split + drag / wPx).coerceIn(0f, 1f)
                                    }
                                }
                                .drawWithContent { clipRect(left = size.width * split) { this@drawWithContent.drawContent() } }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                segments.forEach { seg ->
                    val text = seg.displayText
                    if (text.isBlank() && !editMode) return@forEach
                    val bg = parseColor(seg.style.background) ?: bubbleColors[seg.id] ?: Color.White
                    val fg = parseColor(seg.style.color) ?: if (bg.luminance() > 0.45f) Color(0xFF111111) else Color.White
                    with(density) {
                        Box(
                            Modifier
                                .offset(((seg.x + seg.style.offsetX) * wPx).toDp(), ((seg.y + seg.style.offsetY) * hPx).toDp())
                                .width((seg.w * wPx).toDp())
                                .height((seg.h * hPx).toDp())
                                .background(if (seg.style.background == "none") Color.Transparent else bg, RoundedCornerShape(6.dp))
                                .then(
                                    if (editMode) {
                                        Modifier.border(
                                            1.5.dp,
                                            if (seg.needsReview || text.isBlank()) MtColors.Warning else MaterialTheme.colorScheme.primary,
                                            RoundedCornerShape(6.dp),
                                        )
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable(enabled = editMode) { onSegmentClick(seg) }
                                .padding(2.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            FittedArabicText(
                                text = text,
                                color = fg,
                                fontFamily = fontFamily,
                                maxSizeSp = (seg.h * hPx / density.density / 2.2f * fontScale * (seg.style.fontScale ?: 1f)).coerceIn(8f, 34f),
                                align = when (seg.style.align) { "start" -> TextAlign.Start; "end" -> TextAlign.End; else -> TextAlign.Center },
                                bold = seg.category == "sound_effect" || seg.category == "title",
                            )
                        }
                    }
                }
            }
        }

        if (mode == ViewMode.Compare) {
            with(density) {
                val x = (split * wPx).toDp()
                Box(Modifier.offset(x - 1.dp, 0.dp).width(2.dp).fillMaxSize().background(MtColors.Purple))
                // Handle: a big round target so the divider is easy to grab with one thumb.
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset(x - 18.dp, 0.dp)
                        .size(36.dp)
                        .background(MtColors.Purple, CircleShape)
                        .semantics { contentDescription = handleLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.SwapHoriz, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/**
 * Shrinks the font step by step until the Arabic text fits its box. Line
 * breaking redistributes the text first; we only go down to 7sp.
 */
@Composable
private fun FittedArabicText(text: String, color: Color, fontFamily: FontFamily, maxSizeSp: Float, align: TextAlign, bold: Boolean) {
    var size by remember(text, maxSizeSp) { mutableFloatStateOf(maxSizeSp) }
    var ready by remember(text, maxSizeSp) { mutableStateOf(false) }
    BasicText(
        text = text,
        style = TextStyle(
            color = if (ready) color else Color.Transparent,
            fontFamily = fontFamily,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
            fontSize = size.sp,
            lineHeight = (size * 1.25f).sp,
            textAlign = align,
            textDirection = TextDirection.Rtl,
        ),
        onTextLayout = { r ->
            if (r.hasVisualOverflow && size > 7f) size *= 0.9f else ready = true
        },
    )
}

private fun parseColor(hex: String?): Color? {
    if (hex == null || hex == "none" || !hex.startsWith("#") || hex.length != 7) return null
    return runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
}

/** Most frequent (quantized) colour inside the box: the bubble background, since text covers fewer pixels. */
private fun dominantColor(bmp: Bitmap, seg: SegmentRow): Color {
    val x0 = (seg.x * bmp.width).toInt().coerceIn(0, bmp.width - 1)
    val y0 = (seg.y * bmp.height).toInt().coerceIn(0, bmp.height - 1)
    val x1 = ((seg.x + seg.w) * bmp.width).toInt().coerceIn(x0 + 1, bmp.width)
    val y1 = ((seg.y + seg.h) * bmp.height).toInt().coerceIn(y0 + 1, bmp.height)
    val counts = HashMap<Int, Int>()
    val stepX = ((x1 - x0) / 24).coerceAtLeast(1)
    val stepY = ((y1 - y0) / 24).coerceAtLeast(1)
    var y = y0
    while (y < y1) {
        var x = x0
        while (x < x1) {
            val p = bmp.getPixel(x, y)
            val q = (p and 0xF0F0F0) // 4-bit per channel buckets
            counts[q] = (counts[q] ?: 0) + 1
            x += stepX
        }
        y += stepY
    }
    val best = counts.maxByOrNull { it.value }?.key ?: return Color.White
    return Color(0xFF000000.toInt() or best or 0x080808)
}
