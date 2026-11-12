package com.example.autoorders

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.mutableStateOf

/**
 * A short-lived, user-initiated Chrome helper. Never reads credentials or
 * sends details to a network endpoint. Never confirms a financial order.
 */
object QuickOrderSession {
    enum class Step { IDLE, OPEN_COPY, ENTER_CODE, SEND_CODE, WAIT_DETAILS, OPEN_DETAILS, DONE, ERROR }
    @Volatile var step = Step.IDLE
    @Volatile var code = ""
    val message = mutableStateOf("Готово к работе")
    @Volatile var lastActionAt = 0L

    fun begin(value: String) {
        code = value
        step = Step.OPEN_COPY
        lastActionAt = 0L
        message.value = "Откройте страницу аккаунта в Chrome"
    }
    fun finish(text: String, success: Boolean) {
        code = ""
        step = if (success) Step.DONE else Step.ERROR
        message.value = text
    }
    fun cancel() {
        code = ""
        step = Step.IDLE
        message.value = "Остановлено"
    }
}

class QuickOrderAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            tick()
            handler.postDelayed(this, 900L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        handler.post(poll)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != "com.android.chrome") return
        if (!running) return
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, 350L)
    }
    override fun onInterrupt() { QuickOrderSession.cancel() }
    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        QuickOrderSession.cancel()
        super.onDestroy()
    }

    private fun tick() {
        val state = QuickOrderSession.step
        if (state == QuickOrderSession.Step.IDLE || state == QuickOrderSession.Step.DONE ||
            state == QuickOrderSession.Step.ERROR) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != "com.android.chrome") return
        if (System.currentTimeMillis() - QuickOrderSession.lastActionAt < 1100) return
        try {
            when (state) {
                QuickOrderSession.Step.OPEN_COPY -> {
                    // The code can only be entered once the user is signed into the site.
                    val target = findText(root, "Быстрое копирование")
                    if (target != null && click(target)) {
                        move(QuickOrderSession.Step.ENTER_CODE, "Открываю «Быстрое копирование»")
                    }
                }
                QuickOrderSession.Step.ENTER_CODE -> {
                    val input = findEditable(root)
                    if (input != null && input.isEnabled && input.isEditable) {
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                QuickOrderSession.code)
                        }
                        if (input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                            move(QuickOrderSession.Step.SEND_CODE, "Код введён")
                        }
                    }
                }
                QuickOrderSession.Step.SEND_CODE -> {
                    val submit = findText(root, "Отправлять")
                    if (submit != null && submit.isEnabled && click(submit)) {
                        // No automatic re-click, even if the network result is uncertain.
                        move(QuickOrderSession.Step.WAIT_DETAILS, "Код отправлен, ожидаю страницу")
                    }
                }
                QuickOrderSession.Step.WAIT_DETAILS -> {
                    val title = findText(root, "Доставка и копирование")
                    if (title != null) {
                        move(QuickOrderSession.Step.OPEN_DETAILS, "Появились детали заказа")
                    }
                }
                QuickOrderSession.Step.OPEN_DETAILS -> {
                    val copy = findText(root, "Быстрое копирование")
                    if (copy != null && click(copy)) {
                        QuickOrderSession.finish(
                            "Действия из видео выполнены. Подтверждение финансового заказа оставлено пользователю.",
                            true)
                    }
                }
                else -> Unit
            }
        } catch (_: Exception) {
            QuickOrderSession.finish("Не удалось распознать страницу. Проверьте её вручную.", false)
        }
    }
    private fun move(step: QuickOrderSession.Step, status: String) {
        QuickOrderSession.step = step
        QuickOrderSession.message.value = status
        QuickOrderSession.lastActionAt = System.currentTimeMillis()
    }
    private fun findText(root: AccessibilityNodeInfo, needle: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen++ < 1200) {
            val n = queue.removeFirst()
            if (n.isVisibleToUser) {
                val text = n.text?.toString().orEmpty()
                val desc = n.contentDescription?.toString().orEmpty()
                if (text.contains(needle, ignoreCase = true) ||
                    desc.contains(needle, ignoreCase = true)) return n
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
        }
        return null
    }
    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen++ < 1200) {
            val n = queue.removeFirst()
            if (n.isVisibleToUser && n.isEditable &&
                n.className?.toString()?.contains("EditText") == true) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
        }
        return null
    }
    private fun click(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops++ < 8) {
            if (current.isClickable && current.isEnabled &&
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent
        }
        return false
    }
}
