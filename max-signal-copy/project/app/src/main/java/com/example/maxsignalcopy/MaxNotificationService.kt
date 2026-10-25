package com.example.maxsignalcopy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Listen only to MAX's Android notifications. No private chat scraping or MAX login credentials. */
class MaxNotificationService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        SignalMailbox.listenerConnection(this, true)
    }

    override fun onListenerDisconnected() {
        SignalMailbox.listenerConnection(this, false)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName != SignalPolicy.MAX_PACKAGE) return
        SignalMailbox.maxNotificationObserved(this)
        val notification = sbn.notification
        // The group summary may repeat an earlier message; never treat it as a new code.
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            SignalMailbox.diagnostic(this, "получено сводное уведомление группы, отдельного кода в нём нет.")
            return
        }
        val extras = notification.extras ?: run {
            SignalMailbox.diagnostic(this, "уведомление пришло без данных сообщения.")
            return
        }
        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty().trim()
        val subtext = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty().trim()
        val bigTitle = extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString().orEmpty().trim()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        // An Android group notification can show the sender as EXTRA_TITLE and
        // the actual chat in EXTRA_CONVERSATION_TITLE or EXTRA_SUB_TEXT.
        val names = listOf(conversation, subtext, bigTitle, title)
            .filter { it.isNotBlank() }.distinct()
        if (names.isEmpty()) {
            SignalMailbox.diagnostic(this, "уведомление получено, но Android не передал название чата.")
            return
        }
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val mainText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString("\n") { it.toString() }.orEmpty()
        // Some MAX versions use Android MessagingStyle rather than EXTRA_TEXT.
        val messagingText = readMessagingText(extras)
        val body = listOf(bigText, mainText, lines, messagingText)
            .filter { it.isNotBlank() }.joinToString("\n")
        val now = System.currentTimeMillis()
        if (!SignalPolicy.isFresh(sbn.postTime, now)) {
            SignalMailbox.diagnostic(this, "уведомление устарело или передано повторно; код не принят.")
            return
        }
        if (SignalMailbox.isDiscoveringChat(this)) {
            // Discovery mode NEVER accepts an order; show what the notification
            // names the group so the owner can explicitly approve it.
            SignalMailbox.observeChat(this, conversation.ifBlank { subtext.ifBlank { title } })
            SignalMailbox.diagnostic(this, "обнаружено название чата. Проверьте его в Настройках.")
            return
        }
        if (SignalMailbox.offer(this, sbn.packageName, names, body, sbn.postTime)) {
            sendFoundNotification()
        }
    }

    private fun readMessagingText(extras: Bundle): String {
        return try {
            @Suppress("DEPRECATION")
            val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return ""
            messages.mapNotNull { it as? Bundle }
                .mapNotNull { it.getCharSequence("text")?.toString() }
                .filter { it.isNotBlank() }
                .joinToString("\n")
        } catch (_: Exception) {
            ""
        }
    }

    private fun sendFoundNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val id = "max_signals"
        manager.createNotificationChannel(
            NotificationChannel(id, "Новые коды MAX", NotificationManager.IMPORTANCE_HIGH)
        )
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(this, 101, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, id)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Получен новый сигнал MAX")
            .setContentText("Откройте приложение для проверки сигнала. Код не отображается.")
            .setAutoCancel(true).setContentIntent(pi).build()
        try { manager.notify(101, notification) } catch (_: SecurityException) {
            // Reading MAX notifications and posting ours are different permissions.
        }
    }
}
