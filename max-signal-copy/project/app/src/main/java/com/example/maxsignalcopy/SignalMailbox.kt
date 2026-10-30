package com.example.maxsignalcopy

import android.content.Context
import android.content.SharedPreferences
import java.time.ZoneId
import java.time.Instant

/** One signal can be pending at once. Interrupted/unknown orders cannot be silently re-submitted. */
object SignalMailbox {
    private const val PREFS = "max_signal_copy_v1" // Old storage key retained for upgrades
    private const val DONE = "attempted_fingerprints"
    private val lock = Any()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun settings(context: Context): SignalSettings {
        val p = prefs(context)
        return SignalSettings(
            // Keep stored keys compatible with the previously installed version.
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
        val hash = SignalPolicy.fingerprint(code, "MAX-SIGNAL", day) // Legacy fingerprint scope retained to prevent duplicate orders
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
        .replace("MAX Автосигналы", "Автосигналы")
        .replace("MAX:", "Сигнал:")
        .replace("МАХ:", "Сигнал:")
}
