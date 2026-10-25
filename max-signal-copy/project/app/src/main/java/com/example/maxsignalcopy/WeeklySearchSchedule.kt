package com.example.maxsignalcopy

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** Weekly search windows are recurring, in the phone's local time zone.
 * Day-of-week refers to the day the window STARTS (also for overnight ranges).
 * Equal start/end is not allowed; split whole-day coverage into two windows.
 */
data class WeeklySearchWindow(
    val days: Set<DayOfWeek>,
    val start: LocalTime,
    val end: LocalTime
)

data class ActiveWeeklySearch(val window: WeeklySearchWindow, val startDate: LocalDate)

object WeeklySearchSchedule {
    const val MAX_WINDOWS = 28

    fun valid(window: WeeklySearchWindow): Boolean =
        window.days.isNotEmpty() && window.start != window.end

    /** Disallow overlapping windows, including an overnight interval on a neighboring day. */
    fun canAdd(existing: List<WeeklySearchWindow>, proposed: WeeklySearchWindow): Boolean {
        if (!valid(proposed) || existing.size >= MAX_WINDOWS || existing.any { !valid(it) }) return false
        val occupied = BooleanArray(7 * 24 * 60)
        fun reserve(window: WeeklySearchWindow, check: Boolean): Boolean {
            val startMin = window.start.hour * 60 + window.start.minute
            val endMin = window.end.hour * 60 + window.end.minute
            val duration = if (endMin > startMin) endMin - startMin else endMin + 1440 - startMin
            for (day in window.days) {
                val startOfWeek = (day.value - 1) * 1440 + startMin
                for (offset in 0 until duration) {
                    val index = (startOfWeek + offset) % occupied.size
                    if (check && occupied[index]) return false
                    occupied[index] = true
                }
            }
            return true
        }
        existing.forEach { reserve(it, false) }
        return reserve(proposed, true)
    }

    fun activeAt(windows: List<WeeklySearchWindow>, now: ZonedDateTime): ActiveWeeklySearch? {
        for (window in windows) {
            if (!valid(window)) continue
            for (date in listOf(now.toLocalDate(), now.toLocalDate().minusDays(1))) {
                if (date.dayOfWeek !in window.days) continue
                val begins = date.atTime(window.start).atZone(now.zone)
                val ends = date.plusDays(if (window.end <= window.start) 1 else 0)
                    .atTime(window.end).atZone(now.zone)
                if (!now.isBefore(begins) && now.isBefore(ends)) return ActiveWeeklySearch(window, date)
            }
        }
        return null
    }

    fun nextStart(windows: List<WeeklySearchWindow>, now: ZonedDateTime): ZonedDateTime? =
        (0..7).asSequence().flatMap { offset ->
            val date = now.toLocalDate().plusDays(offset.toLong())
            windows.asSequence().filter { valid(it) && date.dayOfWeek in it.days }
                .map { date.atTime(it.start).atZone(now.zone) }
        }.filter { !it.isBefore(now) }.minOrNull()

    fun describeDays(days: Set<DayOfWeek>): String = DayOfWeek.values().filter { it in days }
        .joinToString(", ") { day ->
            when(day) {
                DayOfWeek.MONDAY -> "Пн"
                DayOfWeek.TUESDAY -> "Вт"
                DayOfWeek.WEDNESDAY -> "Ср"
                DayOfWeek.THURSDAY -> "Чт"
                DayOfWeek.FRIDAY -> "Пт"
                DayOfWeek.SATURDAY -> "Сб"
                DayOfWeek.SUNDAY -> "Вс"
            }
        }
}
