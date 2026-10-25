package com.example.maxsignalcopy

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Stable, testable selection. API returns reverse chronological messages; never trust ordering. */
data class ChatLine(val id: String, val postedMillis: Long, val text: String)
data class HistoryWindow(val startMillis: Long, val endMillis: Long)
sealed class HistoryChoice {
    data class Found(val code: String, val id: String, val postedMillis: Long) : HistoryChoice()
    object NoCode : HistoryChoice()
    object Ambiguous : HistoryChoice()
    object Incomplete : HistoryChoice()
    object TooOld : HistoryChoice()
}

object MaxHistoryPolicy {
    /** Relative to the phone's local date, including windows crossing midnight. */
    fun todayWindow(settings: SignalSettings, nowMillis: Long, zone: ZoneId): HistoryWindow? {
        val start = SignalPolicy.parseTime(settings.windowStart) ?: return null
        val end = SignalPolicy.parseTime(settings.windowEnd) ?: return null
        val at = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val day = at.toLocalDate()
        fun milli(d: LocalDate, t: LocalTime): Long = LocalDateTime.of(d, t).atZone(zone).toInstant().toEpochMilli()
        if (start == end) return HistoryWindow(milli(day, LocalTime.MIDNIGHT), nowMillis)
        if (start < end) return HistoryWindow(milli(day, start), minOf(nowMillis, milli(day, end)))
        val startedYesterday = at.toLocalTime() < end
        val fromDay = if (startedYesterday) day.minusDays(1) else day
        return HistoryWindow(milli(fromDay, start), minOf(nowMillis, milli(fromDay.plusDays(1), end)))
    }

    fun choose(
        messages: List<ChatLine>, window: HistoryWindow, digits: Int,
        nowMillis: Long, pageFull: Boolean, maxAgeMillis: Long = 300_000L,
    ): HistoryChoice {
        if (window.endMillis < window.startMillis) return HistoryChoice.NoCode
        // If the API truncates the selected window at 100, never guess a winner.
        if (pageFull && (messages.minOfOrNull { it.postedMillis } ?: Long.MAX_VALUE) >= window.startMillis)
            return HistoryChoice.Incomplete
        val matches = messages.filter { it.postedMillis in window.startMillis..window.endMillis }
            .mapNotNull { m -> SignalPolicy.extractCode(m.text, digits)?.let { ChatLine(m.id, m.postedMillis, it) } }
        val distinct = matches.map { it.text }.distinct()
        if (distinct.isEmpty()) return HistoryChoice.NoCode
        if (distinct.size > 1) return HistoryChoice.Ambiguous
        val newest = matches.maxByOrNull { it.postedMillis } ?: return HistoryChoice.NoCode
        if (newest.postedMillis > nowMillis + 5_000L || nowMillis - newest.postedMillis > maxAgeMillis)
            return HistoryChoice.TooOld
        return HistoryChoice.Found(newest.text, newest.id, newest.postedMillis)
    }
}
