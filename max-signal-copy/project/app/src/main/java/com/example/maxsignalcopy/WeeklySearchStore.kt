package com.example.maxsignalcopy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalTime

/** Stores no codes, messages, API credentials or session secrets. */
object WeeklySearchStore {
    private const val PREFS = "telegram_weekly_windows_v1"
    private const val KEY_WINDOWS = "windows"
    private const val KEY_ENABLED = "enabled"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): List<WeeklySearchWindow> = runCatching {
        val json = JSONArray(prefs(context).getString(KEY_WINDOWS, "[]"))
        val list = mutableListOf<WeeklySearchWindow>()
        for (i in 0 until json.length()) {
            val rule = json.getJSONObject(i)
            val days = mutableSetOf<DayOfWeek>()
            val values = rule.getJSONArray("days")
            for (j in 0 until values.length()) days.add(DayOfWeek.of(values.getInt(j)))
            val candidate = WeeklySearchWindow(
                days, LocalTime.parse(rule.getString("start")), LocalTime.parse(rule.getString("end"))
            )
            if (WeeklySearchSchedule.canAdd(list, candidate)) list.add(candidate)
        }
        list.toList()
    }.getOrDefault(emptyList())

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)
    fun setEnabled(context: Context, value: Boolean) = prefs(context).edit()
        .putBoolean(KEY_ENABLED, value).apply()

    fun save(context: Context, windows: List<WeeklySearchWindow>) {
        require(windows.size <= WeeklySearchSchedule.MAX_WINDOWS)
        val checked = mutableListOf<WeeklySearchWindow>()
        windows.forEach { window ->
            require(WeeklySearchSchedule.canAdd(checked, window)) { "Overlapping or invalid weekly windows" }
            checked.add(window)
        }
        val json = JSONArray()
        windows.forEach { window ->
            json.put(JSONObject().put("days", JSONArray(window.days.map { it.value }.sorted()))
                .put("start", window.start.toString()).put("end", window.end.toString()))
        }
        prefs(context).edit().putString(KEY_WINDOWS, json.toString()).apply()
    }
}
