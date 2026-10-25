package com.example.maxsignalcopy

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Notify only when this run has observed the matching BTC/USDT order with status В ожидании. */
object OrderPendingNotificationPolicy {
    fun shouldNotify(result: String): Boolean = result == "В ОЖИДАНИИ"
}

/** Local Android notification only; no server, device unlock or data transmission. */
object OrderPendingNotifier {
    private const val CHANNEL_ID = "pending_orders_v1"

    fun show(context: Context): Boolean {
        // Android 13+ can deny app notifications. An in-app message is still displayed.
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) return false

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Заказы в ожидании", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Уведомления после проверки статуса в разделе Заказы" }
        )

        val openApp = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 501, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Заказ в ожидании")
            .setContentText("BTC/USDT: в разделе «Заказы» подтверждён статус «В ожидании».")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()

        // Ensure the temporary trade-progress card cannot remain beside this result.
        manager.cancel(801)
        // Keep each verified pending order visible as a distinct notification.
        val notificationId = (System.currentTimeMillis() and 0x7FFFFFFFL).toInt()
        manager.notify(notificationId, notification)
        return true
    }
}
