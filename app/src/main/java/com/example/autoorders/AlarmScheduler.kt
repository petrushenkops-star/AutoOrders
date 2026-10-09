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

    private fun pending(slot: Slot): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java)
            .setData(Uri.parse("autoorders://slot/${slot.id}"))
            .putExtra("slot_id", slot.id)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun refresh(oldSlots: List<Slot>, slots: List<Slot>) {
        oldSlots.forEach { alarm.cancel(pending(it)) }
        slots.filter { it.enabled }.forEach(::schedule)
    }

    fun schedule(slot: Slot) {
        if (!slot.enabled) return
        val time = nextOccurrence(slot, ZonedDateTime.now()).toInstant().toEpochMilli()
        val pi = pending(slot)
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
        val slot = LocalStore(context).slots().firstOrNull { it.id == slotId && it.enabled } ?: return
        val order = OrderEntry(slotId = slotId)
        LocalStore(context).addOrder(order)
        showNotification(context, order)
        // Set the next weekly occurrence even if the user never opens the notification.
        AlarmScheduler(context).schedule(slot)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED)) {
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

private fun showNotification(context: Context, order: OrderEntry) {
    ensureChannel(context)
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val open = Intent(context, MainActivity::class.java).apply {
        putExtra("order_id", order.id)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val pending = PendingIntent.getActivity(context, order.id.hashCode(), open,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Время выполнить заказ")
        .setContentText("Откройте приложение и введите новый цифровой код")
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(pending)
        .build()
    NotificationManagerCompat.from(context).notify(order.id.hashCode(), notification)
}
