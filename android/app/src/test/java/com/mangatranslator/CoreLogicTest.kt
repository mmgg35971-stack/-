package com.mangatranslator

import com.mangatranslator.core.common.AppException
import com.mangatranslator.core.common.Formatters
import com.mangatranslator.core.network.AppJson
import com.mangatranslator.core.network.parseError
import com.mangatranslator.data.models.Entitlements
import com.mangatranslator.data.models.MeResponse
import com.mangatranslator.data.models.SegmentRow
import com.mangatranslator.features.reader.decodeWidthFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ParseErrorTest {
    @Test fun edgeFunctionCodeWins() {
        val e = parseError(429, """{"error":"daily_limit_reached","message":"limit","daily_limit":"10"}""")
        assertEquals("daily_limit_reached", e.code)
        assertEquals("10", e.extras["daily_limit"])
    }

    @Test fun postgrestPermissionDeniedMapsToForbidden() {
        assertEquals("forbidden", parseError(400, """{"code":"42501","message":"permission denied"}""").code)
    }

    @Test fun statusFallbacks() {
        assertEquals("session_expired", parseError(401, "").code)
        assertEquals("rate_limited", parseError(429, "<html>").code)
        assertEquals(AppException.UNKNOWN, parseError(500, "oops").code)
    }

    @Test fun invalidGrantIsTreatedAsExpiredSession() {
        assertEquals("session_expired", parseError(401, """{"error":"invalid_grant"}""").code)
    }
}

class ModelsTest {
    @Test fun userEditAlwaysWinsOverMachineTranslation() {
        val seg = SegmentRow("s", "p", 0, "dialogue", 0f, 0f, 0.1f, 0.1f, originalText = "Hi", translatedText = "مرحبا", userEditedText = "أهلاً")
        assertEquals("أهلاً", seg.displayText)
        assertEquals("مرحبا", seg.copy(userEditedText = null).displayText)
        assertEquals("", seg.copy(userEditedText = null, translatedText = null).displayText)
    }

    @Test fun entitlementsProgressAndUnlimited() {
        val e = Entitlements(planId = "free", planName = "Free", dailyLimit = 10, usedToday = 4, usageDate = "2026-11-19")
        assertEquals(0.4f, e.progress, 0.0001f)
        assertTrue(e.copy(dailyLimit = null).isUnlimited)
        assertEquals(0f, e.copy(dailyLimit = null).progress, 0f)
        assertEquals(1f, e.copy(usedToday = 15).progress, 0f)
    }

    @Test fun meResponseToleratesUnknownFields() {
        val json = """{"profile":{"id":"u","email":"a@b.c","new_field":1},
            "entitlements":{"plan_id":"pro","plan_name":"Pro","daily_limit":100,"used_today":3,"usage_date":"2026-11-19","is_admin":true,"extra":"x"}}"""
        val me = AppJson.decodeFromString(MeResponse.serializer(), json)
        assertEquals("pro", me.entitlements.planId)
        assertTrue(me.entitlements.isAdmin)
    }
}

class FormattersTest {
    @Test fun bytesAndSeconds() {
        Locale.setDefault(Locale.US)
        assertEquals("512 B", Formatters.bytes(512))
        assertEquals("1.5 KB", Formatters.bytes(1536))
        assertEquals("2 MB", Formatters.bytes(2L * 1024 * 1024))
        assertEquals("1.2", Formatters.seconds(1234))
        assertEquals("—", Formatters.seconds(null))
    }

    @Test fun badDatesFallBackInsteadOfCrashing() {
        assertEquals("2026-11-19", Formatters.date("2026-11-19-garbage"))
        assertEquals("", Formatters.date(null))
    }
}

class ReaderSettingsTest {
    @Test fun imageQualityMapsToDecodeWidth() {
        assertEquals(720, decodeWidthFor("data_saver"))
        assertEquals(1080, decodeWidthFor("balanced"))
        assertEquals(1440, decodeWidthFor("high"))
        assertEquals(2000, decodeWidthFor("original"))
        assertEquals(1440, decodeWidthFor("unknown"))
    }
}
