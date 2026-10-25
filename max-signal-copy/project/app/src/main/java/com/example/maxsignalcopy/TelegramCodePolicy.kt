package com.example.maxsignalcopy

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Read-only signal selection; it never triggers trading, and is usable in plain JVM tests. */
data class TelegramSignalMessage(val id: Long, val postedMillis: Long, val text: String)

sealed class TelegramSelection {
    data class Found(val code: String, val messageId: Long, val postedMillis: Long): TelegramSelection()
    data class Rejected(val reason: String): TelegramSelection()
}

object TelegramCodePolicy {
    const val CHAT_TITLE = "КОМАНДА"
    const val TOPIC_TITLE = "Сигнал"
    const val MAX_PAGES = 10
    const val PAGE_SIZE = 100
    const val MAX_CODE_AGE_MILLIS = 300_000L

    fun inspect(
        messages: List<TelegramSignalMessage>,
        date: LocalDate,
        windowStart: String,
        windowEnd: String,
        digits: Int,
        nowMillis: Long,
        zone: ZoneId,
        requireRecent: Boolean = false
    ): TelegramSelection {
        if (messages.size > MAX_PAGES * PAGE_SIZE)
            return TelegramSelection.Rejected("История слишком велика для безопасной проверки")
        val start = SignalPolicy.parseTime(windowStart)
            ?: return TelegramSelection.Rejected("Некорректное время начала")
        val end = SignalPolicy.parseTime(windowEnd)
            ?: return TelegramSelection.Rejected("Некорректное время окончания")
        if (digits !in 4..10) return TelegramSelection.Rejected("Неверная длина кода")
        // Equal HH:mm means a whole day. For ranges crossing midnight, use the
        // calendar date of the START of the requested window.
        val from = date.atTime(if (start == end) LocalTime.MIDNIGHT else start).atZone(zone).toInstant().toEpochMilli()
        val until = when {
            start == end -> date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            start < end -> date.atTime(end).atZone(zone).toInstant().toEpochMilli()
            else -> date.plusDays(1).atTime(end).atZone(zone).toInstant().toEpochMilli()
        }
        if (from > nowMillis + 5_000L) return TelegramSelection.Rejected("Период ещё не начался")
        val choices = messages.filter { it.postedMillis in from until until && it.postedMillis <= nowMillis + 5_000L }
            .distinctBy { it.id }
        if (choices.isEmpty()) return TelegramSelection.Rejected("За период сообщений нет")
        val found = choices.mapNotNull { item ->
            SignalPolicy.extractCode(item.text, digits)?.let { TelegramSelection.Found(it, item.id, item.postedMillis) }
        }
        if (found.isEmpty()) return TelegramSelection.Rejected("Однозначный код не найден")
        if (choices.any { item ->
                val matches = Regex("(?<![0-9])[0-9]{$digits}(?![0-9])").findAll(item.text).map { it.value }.distinct().toList()
                matches.size > 1
            }) return TelegramSelection.Rejected("В одном сообщении несколько кодов")
        if (found.map { it.code }.distinct().size != 1) return TelegramSelection.Rejected("За период найдено несколько разных кодов")
        val recent = found.maxBy { it.postedMillis }
        if (requireRecent && nowMillis - recent.postedMillis > MAX_CODE_AGE_MILLIS)
            return TelegramSelection.Rejected("Код найден, но устарел для отправки заказа")
        return recent
    }

    fun startOfWindow(date: LocalDate, start: String, end: String, zone: ZoneId): Long? {
        val s=SignalPolicy.parseTime(start) ?: return null
        val e=SignalPolicy.parseTime(end) ?: return null
        return date.atTime(if (s == e) LocalTime.MIDNIGHT else s).atZone(zone).toInstant().toEpochMilli()
    }
}
