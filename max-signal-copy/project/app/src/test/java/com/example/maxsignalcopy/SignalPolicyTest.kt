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

    @Test fun manualCodeWorksWithoutChatAndRejectsBadInput() {
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

    @Test fun freshTimestampAndChatMatchStrictly() {
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

    @Test fun fingerprintIsStable() {
        assertEquals(SignalPolicy.fingerprint("8438078", "A", LocalDate.of(2026,10,20)), SignalPolicy.fingerprint("8438078", "A", LocalDate.of(2026,10,20)))
        assertNotEquals(SignalPolicy.fingerprint("8438078", "A", LocalDate.of(2026,10,20)), SignalPolicy.fingerprint("8438079", "A", LocalDate.of(2026,10,20)))
        assertNotEquals(SignalPolicy.fingerprint("8438078", "A", LocalDate.of(2026,10,20)), SignalPolicy.fingerprint("8438078", "A", LocalDate.of(2026,10,21)))
    }
}
