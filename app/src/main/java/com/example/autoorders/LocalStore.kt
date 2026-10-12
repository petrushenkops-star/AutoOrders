package com.example.autoorders

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalTime

/** Do not persist user codes or website passwords. Only schedule and order outcomes. */
class LocalStore(context: Context) {
    private val prefs = context.getSharedPreferences("autoorders_local", Context.MODE_PRIVATE)
    fun slots(): List<Slot> = try {
        val arr = JSONArray(prefs.getString("slots", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Slot(o.getString("id"), DayOfWeek.of(o.getInt("day")), LocalTime.parse(o.getString("time")), o.optBoolean("enabled", true))
        }.sortedWith(compareBy({ it.day.value }, { it.time }))
    } catch (_: Exception) { emptyList() }

    fun putSlots(slots: List<Slot>) {
        val arr = JSONArray()
        slots.forEach { s -> arr.put(JSONObject().put("id", s.id).put("day", s.day.value).put("time", s.time.toString()).put("enabled", s.enabled)) }
        prefs.edit().putString("slots", arr.toString()).apply()
    }

    fun orders(): List<OrderEntry> = try {
        val arr = JSONArray(prefs.getString("orders", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            OrderEntry(o.getString("id"), o.optString("slotId").takeIf { it.isNotEmpty() }, o.getLong("when"),
                OrderStatus.valueOf(o.getString("status")), o.optString("details"))
        }.sortedByDescending { it.createdAtMillis }
    } catch (_: Exception) { emptyList() }

    /** On process death, preserve an uncertain financial outcome; no automatic replay. */
    fun recoverInterruptedOrders() {
        val current = orders()
        if (current.none { it.status == OrderStatus.IN_PROGRESS }) return
        putOrders(current.map { order ->
            if (order.status != OrderStatus.IN_PROGRESS) order else order.copy(
                status = statusAfterAppRestart(order.status),
                details = "Приложение закрылось во время выполнения. Результат неизвестен. " +
                    "Проверьте состояние заказа на сайте перед новой попыткой."
            )
        })
    }

    fun addOrder(order: OrderEntry) {
        // Only persist results, NOT the dynamic code.
        putOrders((listOf(order) + orders()).take(250))
    }

    fun updateOrder(id: String, status: OrderStatus, details: String) {
        putOrders(orders().map { if (it.id == id) it.copy(status = status, details = details) else it })
    }

    private fun putOrders(orders: List<OrderEntry>) {
        val arr = JSONArray()
        orders.forEach { o -> arr.put(JSONObject().put("id", o.id).put("slotId", o.slotId ?: "")
            .put("when", o.createdAtMillis).put("status", o.status.name).put("details", o.details)) }
        prefs.edit().putString("orders", arr.toString()).apply()
    }
}
