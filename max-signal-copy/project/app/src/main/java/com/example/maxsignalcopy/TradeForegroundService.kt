package com.example.maxsignalcopy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/** User-initiated, short foreground work only for ONE active order (~3 min max).
 *  This service never executes or repeats an order on its own.
 */
class TradeForegroundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private val deadline = Runnable {
        if (TradeSession.isActive()) TradeSession.onServiceTimeout()
        stopSelf()
    }
    private val check = object : Runnable {
        override fun run() {
            if (!TradeSession.isActive()) stopSelf()
            else handler.postDelayed(this, 4_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, "Выполнение заказа", NotificationManager.IMPORTANCE_LOW)
        )
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION, buildNotification("Выполняется заказ на сайте"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else startForeground(NOTIFICATION, buildNotification("Выполняется заказ на сайте"))
        // Bounded wake lock helps the Java step-loop and WebView callbacks while minimized.
        // It cannot guarantee that arbitrary site JavaScript works when Android kills the app.
        val manager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MaxSignalCopy:ActiveOrder")
        wakeLock?.acquire(165_000L)
        handler.postDelayed(check, 4_000L)
        handler.postDelayed(deadline, 165_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> { TradeSession.stop(); stopSelf() }
            FINISH -> stopSelf()
            STATUS -> {
                val message = intent.getStringExtra("message").orEmpty().take(80)
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIFICATION, buildNotification(message.ifBlank { "Выполняется заказ" }))
            }
        }
        return START_NOT_STICKY // Never auto-resubmit after process death.
    }

    override fun onTimeout(startId: Int) {
        TradeSession.onServiceTimeout()
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        TradeSession.onServiceTimeout()
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (TradeSession.isActive()) TradeSession.onServiceTimeout()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 101,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 102,
            Intent(this, TradeForegroundService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("MAX Автосигналы: заказ выполняется")
            .setContentText(message)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_delete, "Остановить", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "active_trade"
        private const val NOTIFICATION = 801
        private const val STOP = "trade.stop"
        private const val FINISH = "trade.finish"
        private const val STATUS = "trade.status"
        fun start(context: Context) {
            val i = Intent(context, TradeForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
        fun finish(context: Context) = context.stopService(Intent(context, TradeForegroundService::class.java))
        fun update(context: Context, value: String) {
            // Send only order stage, never the dynamic code or personal site data.
            try {
                context.startService(Intent(context, TradeForegroundService::class.java).setAction(STATUS)
                    .putExtra("message", value))
            } catch (_: Exception) { /* Keep transaction running; notification may not update. */ }
        }
    }
}
