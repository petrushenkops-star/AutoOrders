package com.example.autoorders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleTest {
    private val zone = ZoneId.of("Europe/Moscow")

    @Test fun differentDaysCanHaveDifferentNumbers() {
        val slots = listOf(
            Slot(day = DayOfWeek.MONDAY, time = LocalTime.of(9, 0)),
            Slot(day = DayOfWeek.MONDAY, time = LocalTime.of(13, 0)),
            Slot(day = DayOfWeek.TUESDAY, time = LocalTime.of(17, 0)),
        )
        assertEquals(2, slots.count { it.day == DayOfWeek.MONDAY })
        assertEquals(1, slots.count { it.day == DayOfWeek.TUESDAY })
        assertEquals(0, slots.count { it.day == DayOfWeek.SUNDAY })
    }

    @Test fun nextOccurrenceThisWeek() {
        val now = ZonedDateTime.of(2026, 10, 9, 17, 0, 0, 0, zone) // Friday
        val slot = Slot(day = DayOfWeek.SATURDAY, time = LocalTime.of(9, 30))
        assertEquals("2026-10-10T09:30+03:00[Europe/Moscow]", nextOccurrence(slot, now).toString())
    }

    @Test fun sameTimeIsNextWeekNotImmediateReplay() {
        val now = ZonedDateTime.of(2026, 10, 9, 17, 0, 0, 0, zone)
        val slot = Slot(day = DayOfWeek.FRIDAY, time = LocalTime.of(17, 0))
        assertEquals(2026, nextOccurrence(slot, now).year)
        assertEquals(16, nextOccurrence(slot, now).dayOfMonth)
    }

    @Test fun interruptedAttemptHasUnknownOutcome() {
        assertEquals(OrderStatus.UNKNOWN, statusAfterInterruption(true))
        assertEquals(OrderStatus.ERROR, statusAfterInterruption(false))
    }

    @Test fun restartDoesNotReturnInterruptedOrderToPending() {
        assertEquals(OrderStatus.UNKNOWN, statusAfterAppRestart(OrderStatus.IN_PROGRESS))
        assertEquals(OrderStatus.WAITING_CODE, statusAfterAppRestart(OrderStatus.WAITING_CODE))
        assertEquals(OrderStatus.SUCCESS, statusAfterAppRestart(OrderStatus.SUCCESS))
        assertEquals(OrderStatus.ERROR, statusAfterAppRestart(OrderStatus.ERROR))
    }

    @Test fun nextOccurrenceAlwaysFuture() {
        for (day in DayOfWeek.entries) {
            val now = ZonedDateTime.of(2026, 10, 9, 17, 0, 0, 0, zone)
            assertTrue(nextOccurrence(Slot(day = day, time = LocalTime.of(9, 0)), now).isAfter(now))
        }
    }
}
