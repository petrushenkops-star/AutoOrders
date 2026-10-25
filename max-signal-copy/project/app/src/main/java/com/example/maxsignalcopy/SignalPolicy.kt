package com.example.maxsignalcopy

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalTime
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Pure, independently testable rules. No personal chat access or network requests. */
data class SignalSettings(
    val chatTitle: String,
    val windowStart: String = "09:00",
    val windowEnd: String = "21:00",
    val codeDigits: Int = 7,
    val armed: Boolean = false,
    val autoConfirm: Boolean = false,
)

object SignalPolicy {
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    const val MAX_PACKAGE = "ru.oneme.app"

    fun valid(settings: SignalSettings): Boolean =
        (!settings.armed || settings.chatTitle.trim().isNotEmpty()) &&
            settings.codeDigits in 4..10 &&
            parseTime(settings.windowStart) != null &&
            parseTime(settings.windowEnd) != null

    /** Manual operation doesn't require access to MAX or a configured chat. */
    fun validManualCode(code: String, expectedDigits: Int): Boolean =
        expectedDigits in 4..10 && code.length == expectedDigits && code.all { it in '0'..'9' }

    fun parseTime(value: String): LocalTime? =
        runCatching { LocalTime.parse(value.trim(), timeFormat) }.getOrNull()

    fun withinWindow(settings: SignalSettings, timestamp: Long, zone: ZoneId): Boolean {
        val start = parseTime(settings.windowStart) ?: return false
        val end = parseTime(settings.windowEnd) ?: return false
        val time = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalTime()
        // Same start/end explicitly means 24 hours.
        return when {
            start == end -> true
            start < end -> !time.isBefore(start) && time.isBefore(end)
            else -> !time.isBefore(start) || time.isBefore(end)
        }
    }

    /** No trade may be started from a delayed notification replay. */
    fun isFresh(notificationTime: Long, now: Long): Boolean =
        notificationTime > 0L && notificationTime <= now + 5_000L &&
            now - notificationTime <= 120_000L

    /** Strict exact chat matching: do not accept a sender's name from another conversation. */
    fun matchesChat(observedConversation: String, expectedChat: String): Boolean =
        expectedChat.isNotBlank() && observedConversation.trim().equals(expectedChat.trim(), ignoreCase = true)

    /** Rejects multiple distinct numeric codes rather than guessing which one to trade with. */
    fun extractCode(message: String, digits: Int): String? {
        if (digits !in 4..10) return null
        val candidates = Regex("(?<![0-9])[0-9]{$digits}(?![0-9])")
            .findAll(message).map { it.value }.distinct().toList()
        return candidates.singleOrNull()
    }

    fun fromNotification(
        packageName: String,
        candidateChatTitles: List<String>,
        messageText: String,
        timestamp: Long,
        settings: SignalSettings,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        if (!settings.armed || !valid(settings)) return null
        if (packageName != MAX_PACKAGE) return null
        if (candidateChatTitles.none { matchesChat(it, settings.chatTitle) }) return null
        if (!withinWindow(settings, timestamp, zone)) return null
        return extractCode(messageText, settings.codeDigits)
    }

    /** Persist only a fingerprint after attempting a code, never the raw code. */
    fun fingerprint(code: String, chatTitle: String, day: LocalDate): String {
        val input = "$chatTitle\u0000$code\u0000$day".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(input)
            .joinToString("") { "%02x".format(it) }
    }
}
