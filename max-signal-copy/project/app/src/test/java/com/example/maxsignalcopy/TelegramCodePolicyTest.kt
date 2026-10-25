package com.example.maxsignalcopy

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class TelegramCodePolicyTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val date = LocalDate.of(2026,10,22)
    private fun stamp(day: LocalDate = date, time: String): Long = day.atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()
    private fun choose(messages: List<TelegramSignalMessage>, start: String="10:00", end:String="10:30", now:String="10:40", recent:Boolean=false) =
        TelegramCodePolicy.inspect(messages,date,start,end,7,stamp(time=now),zone,recent)

    @Test fun selectsOnlyMessageInsideWindow() {
        val result=choose(listOf(TelegramSignalMessage(1,stamp(time="09:59"),"1111111"),TelegramSignalMessage(2,stamp(time="10:17"),"Сигнал 1234567")))
        assertEquals("1234567",(result as TelegramSelection.Found).code)
    }
    @Test fun rejectsDifferentCodes() {
        assertTrue(choose(listOf(TelegramSignalMessage(1,stamp(time="10:01"),"1234567"),TelegramSignalMessage(2,stamp(time="10:03"),"7654321"))) is TelegramSelection.Rejected)
    }
    @Test fun rejectsTwoCodesInOneMessage() {
        assertTrue(choose(listOf(TelegramSignalMessage(1,stamp(time="10:01"),"1234567 и 7654321"))) is TelegramSelection.Rejected)
    }
    @Test fun rejectsStaleTrade() {
        assertTrue(choose(listOf(TelegramSignalMessage(1,stamp(time="10:02"),"1234567")),recent=true) is TelegramSelection.Rejected)
    }
    @Test fun supportsMidnightAndDoesNotUseWrongDay() {
        val result=TelegramCodePolicy.inspect(listOf(TelegramSignalMessage(1,stamp(time="23:05"),"1234567"),TelegramSignalMessage(2,stamp(date.plusDays(1),"00:10"),"1234567")),date,"23:00","00:30",7,stamp(date.plusDays(1),"00:15"),zone)
        assertTrue(result is TelegramSelection.Found)
    }
    @Test fun treatsFullDayAsTodayOnly() {
        val r=choose(listOf(TelegramSignalMessage(1,stamp(date.minusDays(1),"10:17"),"1234567")),"00:00","00:00")
        assertTrue(r is TelegramSelection.Rejected)
    }
    @Test fun rejectOverlongHistory() {
        assertTrue(choose(List(1001){ TelegramSignalMessage(it.toLong(),stamp(time="10:01"),"1234567") }) is TelegramSelection.Rejected)
    }
}
