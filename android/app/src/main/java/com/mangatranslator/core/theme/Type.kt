@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.mangatranslator.core.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.mangatranslator.R

/** Cairo covers Arabic and Latin with one consistent design (variable font). */
private fun cairo(weight: FontWeight) = Font(
    R.font.cairo,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Cairo = FontFamily(
    cairo(FontWeight.Normal),
    cairo(FontWeight.Medium),
    cairo(FontWeight.SemiBold),
    cairo(FontWeight.Bold),
)

/** Fonts the user can pick for text rendered onto manga pages. */
object PageFonts {
    val cairo = Cairo
    val plex = FontFamily(
        Font(R.font.ibm_plex_sans_arabic_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_sans_arabic_bold, FontWeight.Bold),
    )
    val noto = FontFamily(
        Font(R.font.noto_sans_arabic, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(R.font.noto_sans_arabic, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    )

    fun byId(id: String): FontFamily = when (id) {
        "ibm_plex_sans_arabic" -> plex
        "noto_sans_arabic" -> noto
        else -> cairo
    }
}

// Arabic glyphs need generous line height; trim keeps vertical rhythm tight.
private val lh = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(size: Int, line: Int, weight: FontWeight) =
    TextStyle(fontFamily = Cairo, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, lineHeightStyle = lh)

val MtTypography = Typography(
    displaySmall = style(32, 44, FontWeight.Bold),
    headlineMedium = style(26, 36, FontWeight.Bold),
    headlineSmall = style(22, 32, FontWeight.SemiBold),
    titleLarge = style(20, 28, FontWeight.SemiBold),
    titleMedium = style(16, 24, FontWeight.SemiBold),
    titleSmall = style(14, 22, FontWeight.SemiBold),
    bodyLarge = style(16, 26, FontWeight.Normal),
    bodyMedium = style(14, 22, FontWeight.Normal),
    bodySmall = style(12, 18, FontWeight.Normal),
    labelLarge = style(14, 20, FontWeight.SemiBold),
    labelMedium = style(12, 16, FontWeight.Medium),
    labelSmall = style(11, 14, FontWeight.Medium),
)
