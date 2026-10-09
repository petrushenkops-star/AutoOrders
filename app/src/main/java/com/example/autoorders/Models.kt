package com.example.autoorders

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

data class Slot(
    val id: String = UUID.randomUUID().toString(),
    val day: DayOfWeek,
    val time: LocalTime,
    val enabled: Boolean = true,
)

enum class OrderStatus { WAITING_CODE, TEST_SUCCESS, SUCCESS, ERROR, MISSED }

data class OrderEntry(
    val id: String = UUID.randomUUID().toString(),
    val slotId: String?,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val status: OrderStatus = OrderStatus.WAITING_CODE,
    val details: String = "",
)

/** Next occurrence must be strictly after now; zone and DST transitions use the device timezone. */
fun nextOccurrence(slot: Slot, now: ZonedDateTime): ZonedDateTime {
    val offset = (slot.day.value - now.dayOfWeek.value + 7) % 7
    var date = now.toLocalDate().plusDays(offset.toLong())
    var candidate = ZonedDateTime.of(date, slot.time, now.zone)
    if (!candidate.isAfter(now)) {
        date = date.plusWeeks(1)
        candidate = ZonedDateTime.of(date, slot.time, now.zone)
    }
    return candidate
}

fun dayLabel(day: DayOfWeek) = when (day) {
    DayOfWeek.MONDAY -> "Понедельник"
    DayOfWeek.TUESDAY -> "Вторник"
    DayOfWeek.WEDNESDAY -> "Среда"
    DayOfWeek.THURSDAY -> "Четверг"
    DayOfWeek.FRIDAY -> "Пятница"
    DayOfWeek.SATURDAY -> "Суббота"
    DayOfWeek.SUNDAY -> "Воскресенье"
}
