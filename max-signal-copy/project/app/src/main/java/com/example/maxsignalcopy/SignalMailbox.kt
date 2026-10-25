package com.example.maxsignalcopy

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.time.ZoneId
import java.time.Instant

/** One signal can be pending at once. Interrupted/unknown orders cannot be silently re-submitted. */
object SignalMailbox {
    private const val PREFS = "max_signal_copy_v1"
    private const val DONE = "attempted_fingerprints"
    private const val LEARN = "learn_max_chat"
    private const val DISCOVERED_CHAT = "discovered_max_chat"
    private const val LAST_MAX_EVENT = "last_max_event"
    private const val LISTENER_CONNECTED = "max_listener_connected"
    private const val LAST_NOTIFICATION_TIME = "last_max_notification_time"
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    var onSignal: (() -> Unit)? = null

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun settings(context: Context): SignalSettings {
        val p = prefs(context)
        return SignalSettings(
            // User-selected signal channel for the MAX integration.  Do not overwrite
            // a previously selected non-empty conversation after an upgrade.
            chatTitle = p.getString("chat", "").orEmpty().ifBlank { "ЛюменХ" },
            windowStart = p.getString("start", "09:00").orEmpty(),
            windowEnd = p.getString("end", "21:00").orEmpty(),
            codeDigits = p.getInt("digits", 7),
            armed = p.getBoolean("armed", false),
            autoConfirm = p.getBoolean("auto", false),
        )
    }

    fun update(context: Context, s: SignalSettings) {
        require(SignalPolicy.valid(s))
        if (!s.armed) clearPending(context)
        prefs(context).edit().putString("chat", s.chatTitle.trim())
            .putString("start", s.windowStart.trim()).putString("end", s.windowEnd.trim())
            .putInt("digits", s.codeDigits).putBoolean("armed", s.armed)
            .putBoolean("auto", s.autoConfirm).apply()
    }

    /**
     * Discover the conversation title from ONE future MAX notification, but never
     * accept the message as a trading signal while using this feature.
     * The owner must explicitly approve the title afterwards.
     */
    fun startChatDiscovery(context: Context) = synchronized(lock) {
        prefs(context).edit().putBoolean(LEARN, true).remove(DISCOVERED_CHAT).commit()
    }

    fun isDiscoveringChat(context: Context): Boolean = prefs(context).getBoolean(LEARN, false)

    fun discoveredChat(context: Context): String? = prefs(context).getString(DISCOVERED_CHAT, null)

    fun cancelChatDiscovery(context: Context) = synchronized(lock) {
        prefs(context).edit().putBoolean(LEARN, false).remove(DISCOVERED_CHAT).commit()
    }

    fun observeChat(context: Context, conversationTitle: String): Boolean = synchronized(lock) {
        if (!isDiscoveringChat(context) || conversationTitle.isBlank()) return@synchronized false
        // Save only a title, NEVER the notification message or the dynamic code.
        prefs(context).edit().putString(DISCOVERED_CHAT, conversationTitle.trim())
            .putBoolean(LEARN, false).commit()
        main.post { onSignal?.invoke() }
        true
    }

    fun lastEvent(context: Context): String =
        prefs(context).getString(LAST_MAX_EVENT, "От MAX ещё не было сообщений после включения доступа.").orEmpty()

    private fun recordEvent(context: Context, value: String) {
        prefs(context).edit().putString(LAST_MAX_EVENT, value).apply()
        main.post { onSignal?.invoke() }
    }

    /** Diagnostic metadata only: NEVER store or display the message or digital code. */
    fun listenerConnection(context: Context, connected: Boolean) {
        prefs(context).edit().putBoolean(LISTENER_CONNECTED, connected).apply()
        main.post { onSignal?.invoke() }
    }

    fun listenerConnected(context: Context): Boolean =
        prefs(context).getBoolean(LISTENER_CONNECTED, false)

    fun lastMaxNotificationTime(context: Context): Long =
        prefs(context).getLong(LAST_NOTIFICATION_TIME, 0L)

    fun maxNotificationObserved(context: Context) {
        prefs(context).edit().putLong(LAST_NOTIFICATION_TIME, System.currentTimeMillis()).apply()
        main.post { onSignal?.invoke() }
    }

    fun diagnostic(context: Context, explanation: String) {
        recordEvent(context, "MAX: $explanation")
    }

    /** Accepts only current notifications, never reads an old MAX chat history. */
    fun offer(
        context: Context,
        pkg: String,
        chatTitles: List<String>,
        message: String,
        receivedAtMillis: Long,
    ): Boolean = synchronized(lock) {
        val s = settings(context)
        if (!s.armed) {
            recordEvent(context, "MAX: уведомление получено, но приём кодов выключен в настройках.")
            return@synchronized false
        }
        val code = SignalPolicy.fromNotification(
            pkg, chatTitles, message, receivedAtMillis, s, ZoneId.systemDefault()
        ) ?: run {
            recordEvent(context,
                when {
                    chatTitles.none { SignalPolicy.matchesChat(it, s.chatTitle) } ->
                        "MAX: получено сообщение, но заголовок не совпал с чатом «${s.chatTitle}». " +
                            "Используйте «Определить чат по следующему сообщению»."
                    !SignalPolicy.withinWindow(s, receivedAtMillis, ZoneId.systemDefault()) ->
                        "MAX: сообщение вне разрешённого времени (проверьте HH:mm)."
                    message.isBlank() ->
                        "MAX: уведомление содержит заголовок чата, но текст сообщения скрыт или отсутствует."
                    else -> "MAX: цифровой код не найден, длина не совпадает или найдено несколько кодов."
                })
            return@synchronized false
        }
        val p = prefs(context)
        val localDay = Instant.ofEpochMilli(receivedAtMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        val hash = SignalPolicy.fingerprint(code, "MAX-SIGNAL", localDay)
        if (hash in p.getStringSet(DONE, emptySet()).orEmpty()) {
            recordEvent(context, "MAX: такой код уже обрабатывался сегодня.")
            return@synchronized false
        }
        if (p.getString("running", null) != null || p.getString("pending_hash", null) != null) {
            recordEvent(context, "MAX: другой сигнал уже ожидает или выполняется; новый код не принят.")
            return@synchronized false
        }
        p.edit().putString("pending_hash", hash).putString("pending_code", code)
            .putLong("pending_time", receivedAtMillis).apply()
        recordEvent(context, "MAX: получен новый код из выбранного чата.")
        true
    }

    /** Accept code from authenticated, time-filtered MAX history, not from notifications. */
    fun offerFromHistory(context: Context, code: String, postedMillis: Long): Boolean = synchronized(lock) {
        val s = settings(context)
        val now = System.currentTimeMillis()
        if (!s.armed || !SignalPolicy.validManualCode(code, s.codeDigits) ||
            !SignalPolicy.withinWindow(s, postedMillis, ZoneId.systemDefault()) ||
            postedMillis > now + 5_000L || now - postedMillis > 300_000L
        ) return@synchronized false
        val p = prefs(context)
        val day = Instant.ofEpochMilli(postedMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        val hash = SignalPolicy.fingerprint(code, "MAX-SIGNAL", day)
        if (hash in p.getStringSet(DONE, emptySet()).orEmpty() ||
            p.getString("running", null) != null || p.getString("pending_hash", null) != null
        ) return@synchronized false
        if (!p.edit().putString("pending_hash", hash).putString("pending_code", code)
                .putLong("pending_time", now).commit()) return@synchronized false
        recordEvent(context, "MAX: код из истории чата получен, ждёт выполнения.")
        true
    }

    data class Claimed(val code: String, val hash: String)

    /** The lock is held while classifying and reserving the attempt.
     * Never accept a re-used code after an unknown/potentially submitted order.
     */
    data class ManualClaimResult(val claim: Claimed? = null, val rejection: ManualClaimBlock? = null)

    fun claimManualDetailed(context: Context, enteredCode: String): ManualClaimResult = synchronized(lock) {
        val code = enteredCode.trim()
        val settings = settings(context)
        if (!SignalPolicy.validManualCode(code, settings.codeDigits))
            return@synchronized ManualClaimResult(rejection = ManualClaimBlock.INVALID)
        val p = prefs(context)
        val day = Instant.ofEpochMilli(System.currentTimeMillis())
            .atZone(ZoneId.systemDefault()).toLocalDate()
        val hash = SignalPolicy.fingerprint(code, "MAX-SIGNAL", day)
        val attempted = p.getStringSet(DONE, emptySet()).orEmpty()
        val block = ManualClaimGuard.classify(
            anotherOrderRunning = p.getString("running", null) != null,
            awaitingSignal = p.contains("pending_hash"),
            codeAlreadyAttemptedToday = hash in attempted,
        )
        if (block != null) return@synchronized ManualClaimResult(rejection = block)
        // Persist the claimed fingerprint BEFORE the financial web driver starts.
        val updated = attempted.toMutableSet().apply { add(hash) }
        if (!p.edit().putStringSet(DONE, updated).putString("running", hash).commit())
            return@synchronized ManualClaimResult(rejection = ManualClaimBlock.SAVE_FAILED)
        ManualClaimResult(claim = Claimed(code, hash))
    }

    /** Compatibility entry point; callers needing diagnostics use claimManualDetailed. */
    fun claimManual(context: Context, enteredCode: String): Claimed? =
        claimManualDetailed(context, enteredCode).claim

    fun pending(context: Context): Boolean = synchronized(lock) {
        val p = prefs(context)
        if (!p.contains("pending_hash")) return@synchronized false
        if (!SignalPolicy.isFresh(p.getLong("pending_time", 0L), System.currentTimeMillis()) ||
            !settings(context).armed) {
            p.edit().remove("pending_hash").remove("pending_code").remove("pending_time").commit()
            return@synchronized false
        }
        true
    }

    fun claim(context: Context): Claimed? = synchronized(lock) {
        val p = prefs(context)
        val code = p.getString("pending_code", null) ?: return@synchronized null
        val hash = p.getString("pending_hash", null) ?: return@synchronized null
        // The code may expire quickly. Do not execute a stale notification.
        if (!SignalPolicy.isFresh(p.getLong("pending_time", 0L), System.currentTimeMillis()) || !settings(context).armed) {
            p.edit().remove("pending_code").remove("pending_hash").remove("pending_time").apply()
            return@synchronized null
        }
        // Record an attempt BEFORE the browser can submit any financial action.
        val history = p.getStringSet(DONE, emptySet()).orEmpty().toMutableSet()
        history.add(hash)
        p.edit().putStringSet(DONE, history.toList().takeLast(200).toSet())
            .putString("running", hash)
            .remove("pending_code").remove("pending_hash").remove("pending_time")
            .commit()
        Claimed(code, hash)
    }

    fun finish(context: Context, state: String, detail: String, safeToRetry: Boolean = false) = synchronized(lock) {
        val p = prefs(context)
        val entry = "${System.currentTimeMillis()} | $state | $detail"
        val old = p.getString("history", "").orEmpty().lines().filter { it.isNotBlank() }
        val edit = p.edit().putString("history", (listOf(entry) + old).take(50).joinToString("\n"))
        // Only undo a claim if the engine definitively stopped before the
        // first potentially consequential network action. Never undo unknown.
        val running = p.getString("running", null)
        if (safeToRetry && running != null) {
            val attempted = p.getStringSet(DONE, emptySet()).orEmpty().toMutableSet()
            attempted.remove(running)
            edit.putStringSet(DONE, attempted)
        }
        edit.remove("running").commit()
    }

    fun clearPending(context: Context) {
        prefs(context).edit().remove("pending_code").remove("pending_hash").remove("pending_time").apply()
    }

    fun hasInterruptedAttempt(context: Context): Boolean = prefs(context).contains("running")

    fun markInterrupted(context: Context) {
        if (hasInterruptedAttempt(context)) {
            // A crash/kill after a possible click cannot be classified as a failed order.
            finish(context, "НЕИЗВЕСТНО", "Приложение было закрыто во время операции. Проверьте заказ на сайте.")
        }
    }

    fun history(context: Context): String = prefs(context).getString("history", "История пока пуста").orEmpty()
}
