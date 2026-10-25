package com.example.maxsignalcopy

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class MaxHistoryPolicyTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private fun time(day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private val settings = SignalSettings("ЛюменХ", "10:00", "12:00", 7, true, false)

    @Test fun selectsOneRecentCodeWithinPeriod() {
        val now = time(22, 11, 35)
        val w = MaxHistoryPolicy.todayWindow(settings, now, zone)!!
        val lines = listOf(ChatLine("one", time(22, 11, 33), "Динамический пароль: 1234567"),
            ChatLine("old", time(22, 9, 50), "Код 9999999"),
            ChatLine("bad", time(22, 11, 34), "чат открыт"))
        assertEquals(HistoryChoice.Found("1234567", "one", time(22,11,33)),
            MaxHistoryPolicy.choose(lines, w, 7, now, false))
    }

    @Test fun competingCodesDoNotTriggerOrder() {
        val now = time(22, 11, 35)
        val w = MaxHistoryPolicy.todayWindow(settings, now, zone)!!
        val messages = listOf(ChatLine("a", time(22,11,34), "1111111"), ChatLine("b", time(22,11,33), "2222222"))
        assertEquals(HistoryChoice.Ambiguous, MaxHistoryPolicy.choose(messages,w,7,now,false))
    }

    @Test fun staleCodeCannotTriggerOrder() {
        val now = time(22, 11, 35)
        val w = MaxHistoryPolicy.todayWindow(settings, now, zone)!!
        assertEquals(HistoryChoice.TooOld,
            MaxHistoryPolicy.choose(listOf(ChatLine("old",time(22,11,1),"1234567")),w,7,now,false))
    }

    @Test fun fullPageDoesNotPretendHistoryIsExhaustive() {
        val now = time(22, 11, 35)
        val w = MaxHistoryPolicy.todayWindow(settings, now, zone)!!
        assertEquals(HistoryChoice.Incomplete,
            MaxHistoryPolicy.choose(listOf(ChatLine("last",time(22,11,30),"1234567")),w,7,now,true))
    }

    @Test fun dateWindowAndMidnightRollOver() {
        val w = MaxHistoryPolicy.todayWindow(settings,time(22,11,35),zone)!!
        assertEquals(time(22,10,0),w.startMillis)
        assertEquals(time(22,11,35),w.endMillis)
        val night = settings.copy(windowStart="23:30",windowEnd="01:00")
        val cross = MaxHistoryPolicy.todayWindow(night,time(22,0,10),zone)!!
        assertEquals(time(21,23,30),cross.startMillis)
        assertEquals(time(22,0,10),cross.endMillis)
    }
}
