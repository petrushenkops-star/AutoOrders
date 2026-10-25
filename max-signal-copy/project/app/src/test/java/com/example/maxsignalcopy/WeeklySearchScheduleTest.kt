package com.example.maxsignalcopy

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class WeeklySearchScheduleTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private fun window(days: Set<DayOfWeek>, start: String, end: String) =
        WeeklySearchWindow(days, LocalTime.parse(start), LocalTime.parse(end))
    private fun at(date: String, time: String) = LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone)

    @Test fun separateWindowsSameDayAreAllowed() {
        val first=window(setOf(DayOfWeek.MONDAY), "10:00", "10:30")
        val second=window(setOf(DayOfWeek.MONDAY), "18:00", "18:30")
        assertTrue(WeeklySearchSchedule.canAdd(listOf(first), second))
        assertNotNull(WeeklySearchSchedule.activeAt(listOf(first,second), at("2026-10-26","18:12")))
        assertNull(WeeklySearchSchedule.activeAt(listOf(first,second), at("2026-10-26","12:00")))
        assertNull(WeeklySearchSchedule.activeAt(listOf(first,second), at("2026-10-26","18:30")))
    }
    @Test fun repeatsOnlyOnSelectedWeekdays() {
        val w=window(setOf(DayOfWeek.MONDAY,DayOfWeek.WEDNESDAY),"09:00","10:00")
        assertNotNull(WeeklySearchSchedule.activeAt(listOf(w),at("2026-10-26","09:05")))
        assertNull(WeeklySearchSchedule.activeAt(listOf(w),at("2026-10-27","09:05")))
        assertNotNull(WeeklySearchSchedule.activeAt(listOf(w),at("2026-10-28","09:05")))
    }
    @Test fun overnightRangeUsesStartWeekday() {
        val monday=window(setOf(DayOfWeek.MONDAY),"23:30","00:30")
        val active=WeeklySearchSchedule.activeAt(listOf(monday),at("2026-10-27","00:15"))
        assertEquals(LocalDate.parse("2026-10-26"),active?.startDate)
        assertNull(WeeklySearchSchedule.activeAt(listOf(monday),at("2026-10-27","00:30")))
    }
    @Test fun overnightOverlapAtStartOfNextDayIsRejected() {
        val monday=window(setOf(DayOfWeek.MONDAY),"23:30","00:30")
        val tuesday=window(setOf(DayOfWeek.TUESDAY),"00:15","00:45")
        assertFalse(WeeklySearchSchedule.canAdd(listOf(monday),tuesday))
    }
    @Test fun overnightSundayOverlapsMondayInNextWeek() {
        val sunday=window(setOf(DayOfWeek.SUNDAY),"23:50","00:20")
        val monday=window(setOf(DayOfWeek.MONDAY),"00:10","00:30")
        assertFalse(WeeklySearchSchedule.canAdd(listOf(sunday), monday))
    }
    @Test fun invalidOrEmptyWindowsRejected() {
        assertFalse(WeeklySearchSchedule.canAdd(emptyList(),window(emptySet(),"09:00","10:00")))
        assertFalse(WeeklySearchSchedule.canAdd(emptyList(),window(setOf(DayOfWeek.FRIDAY),"10:00","10:00")))
    }
    @Test fun nextWindowComputesNextDay() {
        val w=window(setOf(DayOfWeek.TUESDAY),"13:00","14:00")
        assertEquals(at("2026-10-27","13:00"),WeeklySearchSchedule.nextStart(listOf(w),at("2026-10-26","14:00")))
    }
}
