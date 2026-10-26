package com.example.maxsignalcopy

import android.content.Context
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Human-readable stage log. No code, token or account information is stored. */
object BackgroundRunTrace {
    private const val PREF = "background_trace_v1"
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")
    fun note(context: Context, event: String) {
        val p = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val lines = p.getString("entries", "").orEmpty().lines().filter { it.isNotBlank() }
        val latest = LocalTime.now().format(clock) + " · " + event.take(140)
        p.edit().putString("entries", (lines + latest).takeLast(18).joinToString("\n")).apply()
    }
    fun read(context: Context): String =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("entries", "Событий ещё нет").orEmpty()
}
