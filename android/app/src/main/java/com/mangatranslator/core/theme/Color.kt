package com.mangatranslator.core.theme

import androidx.compose.ui.graphics.Color

/** Brand palette from the product spec. Use Purple only for primary actions/selection. */
object MtColors {
    val BgPrimary = Color(0xFF0B0B12)
    val BgSecondary = Color(0xFF12121D)
    val Card = Color(0xFF191927)
    val Border = Color(0xFF29293A)
    val Purple = Color(0xFF8B5CF6)
    val PurpleLight = Color(0xFFA78BFA)
    val Accent = Color(0xFFC084FC)
    val TextPrimary = Color(0xFFF5F3FF)
    val TextSecondary = Color(0xFFA1A1B5)
    val Success = Color(0xFF34D399)
    val Error = Color(0xFFF87171)
    val Warning = Color(0xFFFBBF24)

    // Light theme counterparts (dark-first; light is a courtesy theme)
    val LightBg = Color(0xFFF7F6FB)
    val LightCard = Color(0xFFFFFFFF)
    val LightBorder = Color(0xFFE4E2EE)
    val LightText = Color(0xFF15131F)
    val LightTextSecondary = Color(0xFF5E5B70)
}
