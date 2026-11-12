package com.example.autoorders

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
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
    @Volatile var serviceConnected = false
    @Volatile var observedChromeEvents = 0

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
    private var gesturePending = false
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
        QuickOrderSession.serviceConnected = true
        handler.post(poll)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() == "com.android.chrome") {
            QuickOrderSession.observedChromeEvents++
        }
        // Do not reset a pending poll on every content-change event:
        // a busy SPA can emit these events continuously and starve the runner.
        // The periodic poll is the sole scheduler.
    }
    override fun onInterrupt() { QuickOrderSession.cancel() }
    override fun onDestroy() {
        running = false
        QuickOrderSession.serviceConnected = false
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
        // Some Android/Chrome versions return null for rootInActiveWindow
        // while an interactive window root is accessible via getWindows().
        val activeRoot = rootInActiveWindow
        val chromeWindowRoots = windows.orEmpty().mapNotNull { window ->
            if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) window.root else null
        }.filter { it.packageName?.toString() == "com.android.chrome" }
        val root = when {
            activeRoot?.packageName?.toString() == "com.android.chrome" -> activeRoot
            else -> chromeWindowRoots.firstOrNull()
        }
        if (root == null) {
            val active = activeRoot?.packageName?.toString().orEmpty().ifEmpty { "нет" }
            val connection = if (QuickOrderSession.serviceConnected) "подключена" else "не подключена"
            QuickOrderSession.message.value =
                "Chrome не предоставил доступ к элементам страницы. " +
                "Служба: $connection; активное окно: $active; " +
                "окон Chrome: ${chromeWindowRoots.size}; событий: ${QuickOrderSession.observedChromeEvents}. " +
                "Проверьте разрешение специальных возможностей."
            return
        }
        if (gesturePending || System.currentTimeMillis() - QuickOrderSession.lastActionAt < 1100) return
        try {
            when (state) {
                QuickOrderSession.Step.OPEN_COPY -> {
                    // The site may already be showing the code form: do not click
                    // a second "quick copy" element, which might be an order action.
                    if (findWebsiteCodeFields(root).size == 1) {
                        move(QuickOrderSession.Step.ENTER_CODE, "Форма кода открыта")
                    } else {
                        val buttons = findWebsiteActions(root, listOf("Быстрое копирование"))
                        when (buttons.size) {
                            0 -> QuickOrderSession.message.value =
                                "Не найдена кнопка перехода к форме кода в Chrome"
                            1 -> pressOnce(buttons.single(), QuickOrderSession.Step.ENTER_CODE,
                                "Нажата кнопка открытия формы")
                            else -> QuickOrderSession.message.value =
                                "Найдено несколько кнопок «Быстрое копирование». Нажатие отменено"
                        }
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
                    // The screenshot labels vary between "Отправить" and
                    // "Отправлять". Never click multiple candidates or retry.
                    val buttons = findWebsiteActions(root, listOf("Отправить", "Отправлять"))
                    when (buttons.size) {
                        0 -> QuickOrderSession.message.value =
                            "Код введён. Кнопка отправки не обнаружена в форме сайта"
                        1 -> pressOnce(buttons.single(), QuickOrderSession.Step.WAIT_DETAILS,
                            "Нажата «Отправить» один раз; ожидаю ответ сайта")
                        else -> QuickOrderSession.message.value =
                            "Несколько кнопок отправки. Автонажатие отменено"
                    }
                }
                QuickOrderSession.Step.WAIT_DETAILS -> {
                    val title = findText(root, "Доставка и копирование")
                    if (title != null) {
                        move(QuickOrderSession.Step.OPEN_DETAILS, "Появились детали заказа")
                    }
                }
                QuickOrderSession.Step.OPEN_DETAILS -> {
                    // Never auto-press a second "quick copy" action on an order
                    // screen: it may execute a financial transaction.
                    QuickOrderSession.finish(
                        "Детали заказа открыты. Финальное финансовое действие выполните вручную.",
                        true)
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
    /**
     * Resolve labels only inside Chrome's website tree. If the text belongs
     * to a child span, its closest actionable ancestor is selected.
     * Duplicate labels that refer to the same button count as one.
     */
    private fun findWebsiteActions(
        root: AccessibilityNodeInfo, labels: List<String>
    ): List<AccessibilityNodeInfo> {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>()
        queue.add(root to false)
        val result = mutableListOf<AccessibilityNodeInfo>()
        val seenBounds = mutableSetOf<String>()
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 1600) {
            val (node, parentWeb) = queue.removeFirst()
            val type = node.className?.toString().orEmpty()
            val resourceId = node.viewIdResourceName.orEmpty()
            val chromeUi = resourceId.startsWith("com.android.chrome:id/") ||
                resourceId.contains("url_bar", ignoreCase = true)
            val inWeb = parentWeb || type.contains("WebView", ignoreCase = true)
            if (inWeb && !chromeUi && node.isVisibleToUser) {
                val text = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                if (labels.any { text.equals(it, ignoreCase = true) ||
                        desc.equals(it, ignoreCase = true) }) {
                    var target = node
                    var current = node.parent
                    var hops = 0
                    while (!target.isClickable && current != null && hops++ < 5) {
                        if (current.className?.toString()?.contains("WebView", true) == true) break
                        target = current
                        current = current.parent
                    }
                    if (target.isEnabled && target.isVisibleToUser) {
                        val b = Rect()
                        target.getBoundsInScreen(b)
                        if (!b.isEmpty) {
                            val key = "${b.left}:${b.top}:${b.right}:${b.bottom}"
                            if (seenBounds.add(key)) result.add(target)
                        }
                    }
                }
            }
            if (!chromeUi) for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it to inWeb) }
            }
        }
        return result
    }

    /**
     * One attempt per stage. Android accessibility actions often fail on
     * virtual HTML buttons; a single on-screen gesture is the fallback.
     */
    private fun pressOnce(
        node: AccessibilityNodeInfo,
        next: QuickOrderSession.Step,
        status: String
    ) {
        // First try semantic ACTION_CLICK, also allowing clickable ancestors.
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops++ < 6) {
            if (current.isEnabled && current.isClickable &&
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                move(next, status)
                return
            }
            if (current.className?.toString()?.contains("WebView", true) == true) break
            current = current.parent
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty || rect.width() < 6 || rect.height() < 6) {
            QuickOrderSession.message.value = "Кнопка найдена, но её границы недоступны"
            return
        }
        gesturePending = true
        val tap = Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
        val stroke = GestureDescription.StrokeDescription(tap, 0L, 90L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val submitted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                gesturePending = false
                move(next, status)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                gesturePending = false
                // The tap may already have reached Chrome before cancellation.
                // Do not automatically retry, which could double-submit.
                QuickOrderSession.finish("Нажатие отменено Android. Проверьте результат вручную.", false)
            }
        }, handler)
        if (!submitted) {
            gesturePending = false
            QuickOrderSession.finish("Android не разрешил нажать кнопку. Повтор не выполняется.", false)
        }
    }
}
