package com.example.autoorders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.ZonedDateTime

const val CHANNEL_ID = "orders_codes"

class AlarmScheduler(private val context: Context) {
    private val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pending(slot: Slot, scheduledAt: Long = 0L): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java)
            .setData(Uri.parse("autoorders://slot/${slot.id}"))
            .putExtra("slot_id", slot.id)
            .putExtra("scheduled_at", scheduledAt)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * Rebuild previously inexact alarms after exact-alarm permission is granted.
     * Without this, Android keeps pending old inexact broadcasts.
     */
    fun rescheduleAfterPermissionChange() {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()
        val prefs = context.getSharedPreferences("autoorders_alarm_state", Context.MODE_PRIVATE)
        val previous = if (prefs.contains("exact_allowed")) prefs.getBoolean("exact_allowed", allowed) else null
        if (previous == null || previous != allowed) {
            val slots = LocalStore(context).slots()
            refresh(slots, slots)
            prefs.edit().putBoolean("exact_allowed", allowed).apply()
        }
    }

    fun refresh(oldSlots: List<Slot>, slots: List<Slot>) {
        oldSlots.forEach { alarm.cancel(pending(it)) }
        slots.filter { it.enabled }.forEach(::schedule)
    }

    fun schedule(slot: Slot) {
        if (!slot.enabled) return
        val time = nextOccurrence(slot, ZonedDateTime.now()).toInstant().toEpochMilli()
        val pi = pending(slot, time)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi)
        } else {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi)
        }
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val slotId = intent.getStringExtra("slot_id") ?: return
        val store = LocalStore(context)
        val slot = store.slots().firstOrNull { it.id == slotId && it.enabled } ?: return
        val order = OrderEntry(slotId = slotId)
        store.addOrder(order)
        val notificationResult = showNotification(context, order)
        store.recordAlarmEvent(intent.getLongExtra("scheduled_at", 0L),
            System.currentTimeMillis(), notificationResult)
        // Set the next weekly occurrence even if the user never opens the notification.
        AlarmScheduler(context).schedule(slot)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
            AlarmScheduler(context).rescheduleAfterPermissionChange()
        } else if (intent.action in listOf(
                Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED
            )) {
            val slots = LocalStore(context).slots()
            AlarmScheduler(context).refresh(slots, slots)
        }
    }
}

fun ensureChannel(context: Context) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Ввод кода заказа", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "Напоминание в назначенное время"
    })
}

private fun showNotification(context: Context, order: OrderEntry): String =
    postOrderNotification(context, order.id.hashCode(),
        "Время выполнить заказ", "Откройте приложение и введите новый цифровой код", order.id)

fun testScheduleNotification(context: Context): String =
    postOrderNotification(context, -100271, "Тест уведомления «Автозаказы»",
        "Это проверочное уведомление. Заказ не создан.", null)

private fun postOrderNotification(
    context: Context, notificationId: Int, title: String, message: String,
    orderId: String?
): String {
    ensureChannel(context)
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return "Нет разрешения на уведомления"
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled())
        return "Уведомления приложения выключены"
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE)
        return "Канал уведомлений отключён"
    val open = Intent(context, MainActivity::class.java).apply {
        if (orderId != null) putExtra("order_id", orderId)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val pending = PendingIntent.getActivity(context, notificationId, open,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(message)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(pending)
        .build()
    return try {
        NotificationManagerCompat.from(context).notify(notificationId, notification)
        "Уведомление передано Android"
    } catch (_: SecurityException) {
        "Android запретил уведомление"
    } catch (_: RuntimeException) {
        "Ошибка отправки уведомления"
    }
}
