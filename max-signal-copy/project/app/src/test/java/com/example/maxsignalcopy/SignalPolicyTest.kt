package com.example.maxsignalcopy

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class SignalPolicyTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private fun time(t: String): Long = LocalDateTime.parse("2026-10-20T$t")
        .atZone(zone).toInstant().toEpochMilli()

    @Test fun manualCodeWorksWithoutMaxChatAndRejectsBadInput() {
        assertTrue(SignalPolicy.valid(SignalSettings("", "00:00", "00:00", 7, armed = false)))
        assertFalse(SignalPolicy.valid(SignalSettings("", "00:00", "00:00", 7, armed = true)))
        assertTrue(SignalPolicy.validManualCode("0012345", 7))
        assertFalse(SignalPolicy.validManualCode("123456", 7))
        assertFalse(SignalPolicy.validManualCode("1234abc", 7))
        assertFalse(SignalPolicy.validManualCode("１２３４５６７", 7))
    }

    @Test fun extractsExactlyOneSevenDigitCodeWithoutDroppingZeroes() {
        assertEquals("0012345", SignalPolicy.extractCode("Сигнал: 0012345", 7))
        assertNull(SignalPolicy.extractCode("Сигналы 0012345 1111111", 7))
        assertNull(SignalPolicy.extractCode("Это 12345678", 7))
    }

    @Test fun onlyConfiguredChatAndMaxAppAllowed() {
        val cfg = SignalSettings("Сигналы VIP", "19:00", "21:00", 7, armed = true)
        assertEquals("8438078", SignalPolicy.fromNotification("ru.oneme.app", listOf("Сигналы VIP"),
            "Код 8438078", time("20:00:00"), cfg, zone))
        assertNull(SignalPolicy.fromNotification("ru.oneme.app", listOf("Чужой чат"),
            "Код 8438078", time("20:00:00"), cfg, zone))
        assertNull(SignalPolicy.fromNotification("other.app", listOf("Сигналы VIP"),
            "Код 8438078", time("20:00:00"), cfg, zone))
    }

    @Test fun lumenChatAcceptsOnlyExactConversationAndValidCode() {
        val cfg = SignalSettings("ЛюменХ", "00:00", "00:00", 7, armed = true)
        assertEquals("0123456", SignalPolicy.fromNotification(
            "ru.oneme.app", listOf("ЛюменХ"), "Код: 0123456", time("20:00:00"), cfg, zone))
        assertEquals("0123456", SignalPolicy.fromNotification(
            "ru.oneme.app", listOf(" люменх "), "Код: 0123456", time("20:00:00"), cfg, zone))
        assertNull(SignalPolicy.fromNotification(
            "ru.oneme.app", listOf("ЛюменХ новости"), "Код: 0123456", time("20:00:00"), cfg, zone))
        assertNull(SignalPolicy.fromNotification(
            "ru.oneme.app", listOf("ЛюменХ"), "Код: 0123456 и 2345678", time("20:00:00"), cfg, zone))
        assertNull(SignalPolicy.fromNotification(
            "other.app", listOf("ЛюменХ"), "Код: 0123456", time("20:00:00"), cfg, zone))
        assertNull(SignalPolicy.fromNotification(
            "ru.oneme.app", listOf("ЛюменХ"), "Код: 0123456", time("20:00:00"),
            cfg.copy(armed = false), zone))
    }

    @Test fun timeWindowBoundariesAndOvernight() {
        val standard = SignalSettings("A", "19:00", "21:00")
        assertFalse(SignalPolicy.withinWindow(standard, time("18:59:59"), zone))
        assertTrue(SignalPolicy.withinWindow(standard, time("19:00:00"), zone))
        assertFalse(SignalPolicy.withinWindow(standard, time("21:00:00"), zone))
        val overnight = SignalSettings("A", "22:00", "02:00")
        assertTrue(SignalPolicy.withinWindow(overnight, time("23:00:00"), zone))
        assertTrue(SignalPolicy.withinWindow(overnight, time("01:00:00"), zone))
        assertFalse(SignalPolicy.withinWindow(overnight, time("12:00:00"), zone))
    }

    @Test fun freshNotificationAndChatMatchStrictly() {
        val now = 1_800_000_000_000L
        assertTrue(SignalPolicy.isFresh(now, now))
        assertTrue(SignalPolicy.isFresh(now - 120_000, now))
        assertFalse(SignalPolicy.isFresh(now - 120_001, now))
        assertFalse(SignalPolicy.isFresh(now + 5_001, now))
        assertFalse(SignalPolicy.isFresh(0, now))
        assertTrue(SignalPolicy.matchesChat(" Сигналы VIP ", "сигналы vip"))
        assertFalse(SignalPolicy.matchesChat("Сигналы VIP резерв", "Сигналы VIP"))
    }

    @Test fun onlyOneCodeEvenIfRepeatedAcrossNotificationFields() {
        assertEquals("0074103", SignalPolicy.extractCode("Код 0074103\n0074103", 7))
        assertNull(SignalPolicy.extractCode("Код 0074103 и 0074104", 7))
    }

    @Test fun disabledAppDoesNotCaptureAndFingerprintIsStable() {
        val settings = SignalSettings("A", "00:00", "00:00", 7, armed = false)
        assertNull(SignalPolicy.fromNotification("ru.oneme.app", listOf("A"),
            "8438078", time("20:00:00"), settings, zone))
        assertEquals(SignalPolicy.fingerprint("8438078", "A", LocalDate.of(2026,10,20)), SignalPolicy.fingerprint("8438078", "A", LocalDate.of(2026,10,20)))
        assertNotEquals(SignalPolicy.fingerprint("8438078", "A", LocalDate.of(2026,10,20)), SignalPolicy.fingerprint("8438079", "A", LocalDate.of(2026,10,20)))
        assertNotEquals(SignalPolicy.fingerprint("8438078", "A", LocalDate.of(2026,10,20)), SignalPolicy.fingerprint("8438078", "A", LocalDate.of(2026,10,21)))
    }
}
