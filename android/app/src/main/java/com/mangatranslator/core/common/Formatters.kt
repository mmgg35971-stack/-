package com.mangatranslator.core.common

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

object Formatters {
    private fun locale(): Locale = Locale.getDefault()

    fun date(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        return runCatching {
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale())
                .format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
        }.getOrElse { iso.take(10) }
    }

    fun dateTime(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        return runCatching {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale())
                .format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
        }.getOrElse { iso }
    }

    fun bytes(n: Long): String {
        val nf = NumberFormat.getNumberInstance(locale()).apply { maximumFractionDigits = 1 }
        return when {
            n >= 1L shl 30 -> nf.format(n / (1L shl 30).toDouble()) + " GB"
            n >= 1L shl 20 -> nf.format(n / (1L shl 20).toDouble()) + " MB"
            n >= 1L shl 10 -> nf.format(n / (1L shl 10).toDouble()) + " KB"
            else -> "$n B"
        }
    }

    fun seconds(ms: Int?): String = if (ms == null) "—" else NumberFormat.getNumberInstance(locale())
        .apply { maximumFractionDigits = 1 }.format(ms / 1000.0)
}
