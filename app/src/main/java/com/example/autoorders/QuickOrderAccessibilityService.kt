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
    @Volatile var startedAt = 0L

    fun begin(value: String) {
        code = value
        step = Step.OPEN_COPY
        lastActionAt = 0L
        startedAt = System.currentTimeMillis()
        message.value = "Автоматизация запущена. Ищу «Быстрое копирование» в Chrome"
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
        // Do not reset a pending poll on every content-change event:
        // a busy SPA can emit these events continuously and starve the runner.
        // The periodic poll is the sole scheduler.
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
        if (System.currentTimeMillis() - QuickOrderSession.startedAt > 60000L) {
            QuickOrderSession.finish(
                "За 60 секунд нужные элементы не найдены. Откройте нужный раздел сайта и повторите.", false)
            return
        }
        val root = rootInActiveWindow ?: run {
            QuickOrderSession.message.value = "Нет доступа к дереву окна Chrome. Проверьте службу специальных возможностей."
            return
        }
        if (root.packageName?.toString() != "com.android.chrome") {
            QuickOrderSession.message.value = "Ожидаю активное окно Chrome"
            return
        }
        if (System.currentTimeMillis() - QuickOrderSession.lastActionAt < 1100) return
        try {
            when (state) {
                QuickOrderSession.Step.OPEN_COPY -> {
                    // The code can only be entered once the user is signed into the site.
                    val target = findText(root, "Быстрое копирование")
                    if (target != null && click(target)) {
                        move(QuickOrderSession.Step.ENTER_CODE, "Открываю «Быстрое копирование»")
                    } else if (target == null) {
                        QuickOrderSession.message.value =
                            "Кнопка «Быстрое копирование» не видна в Chrome. Откройте меню сайта вручную."
                    } else {
                        QuickOrderSession.message.value =
                            "Кнопка найдена, но Android не разрешает нажатие. Нажмите её вручную."
                    }
                }
                QuickOrderSession.Step.ENTER_CODE -> {
                    val inputs = findWebsiteCodeFields(root)
                    val input = inputs.singleOrNull()
                    if (input == null) {
                        QuickOrderSession.message.value = if (inputs.isEmpty()) {
                            "Поле динамического кода внутри сайта не найдено. Адресная строка Chrome исключена. Откройте форму кода вручную."
                        } else {
                            "На странице несколько подходящих полей кода. Автоввод отменён во избежание ошибки."
                        }
                    }
                    if (input != null && input.isEnabled && input.isEditable) {
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                QuickOrderSession.code)
                        }
                        if (input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                            // Do not send until we have observed the expected code
                            // in the same website field, not Chrome's address bar.
                            if (input.text?.toString() == QuickOrderSession.code) {
                                move(QuickOrderSession.Step.SEND_CODE, "Код введён в поле сайта")
                            } else {
                                QuickOrderSession.finish(
                                    "Не удалось проверить введённый код в поле сайта. Отправка отменена.", false)
                            }
                        } else {
                            QuickOrderSession.finish("Сайт не разрешил ввод в поле кода. Отправка отменена.", false)
                        }
                    }
                }
                QuickOrderSession.Step.SEND_CODE -> {
                    val verified = findWebsiteCodeFields(root).singleOrNull()
                    if (verified == null || verified.text?.toString() != QuickOrderSession.code) {
                        QuickOrderSession.finish(
                            "Поле сайта изменилось или код не подтверждён. Автоматическая отправка отменена.", false)
                        return
                    }
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
    /**
     * Chrome's URL field is an ordinary editable AccessibilityNodeInfo but
     * MUST NEVER be treated as a webpage input. Only virtual HTML elements
     * that descend from a WebView are candidates. Refuse ambiguous pages.
     */
    private fun findWebsiteCodeFields(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>()
        queue.add(root to false)
        var seen = 0
        while (queue.isNotEmpty() && seen++ < 1500) {
            val (n, withinWebView) = queue.removeFirst()
            val className = n.className?.toString().orEmpty()
            val id = n.viewIdResourceName.orEmpty()
            val inWebsite = withinWebView || className.contains("WebView", ignoreCase = true)
            val chromeControl = id.startsWith("com.android.chrome:id/") ||
                id.contains("url_bar", ignoreCase = true) ||
                className.contains("Omnibox", ignoreCase = true)
            if (inWebsite && !chromeControl && n.isVisibleToUser && n.isEnabled &&
                n.isEditable && (className.contains("EditText") ||
                    className.contains("input", ignoreCase = true))) {
                candidates.add(n)
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.addLast(it to (inWebsite && !chromeControl)) }
            }
        }
        return candidates
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
