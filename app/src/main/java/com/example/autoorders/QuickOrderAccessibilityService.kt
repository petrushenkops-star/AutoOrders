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
    enum class Step { IDLE, OPEN_TRADE, OPEN_COPY, ENTER_CODE, SEND_CODE, WAIT_DETAILS, OPEN_DETAILS, DONE, ERROR }
    @Volatile var step = Step.IDLE
    @Volatile var code = ""
    val message = mutableStateOf("Готово к работе")
    @Volatile var lastActionAt = 0L
    @Volatile var startedAt = 0L
    @Volatile var serviceConnected = false
    @Volatile var observedChromeEvents = 0
    @Volatile var lastChromeStatus = "Chrome ещё не был обнаружен"

    fun begin(value: String) {
        code = value
        step = Step.OPEN_COPY
        lastActionAt = 0L
        startedAt = System.currentTimeMillis()
        message.value = "Открываю торговый раздел BTC/USDT напрямую; ожидаю форму кода"
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
        // The user has switched back to AutoOrders to read the diagnostics.
        // Preserve the last Chrome-related status rather than replacing it
        // with a misleading "Chrome did not provide access" error.
        if (root == null && activeRoot?.packageName?.toString() == packageName) {
            return
        }
        if (root == null) {
            val active = activeRoot?.packageName?.toString().orEmpty().ifEmpty { "не определено" }
            QuickOrderSession.message.value =
                if (active == "com.miui.home" || active == "com.android.launcher3" ||
                    active == "com.google.android.apps.nexuslauncher") {
                    "Автоматизация приостановлена: сейчас открыт домашний экран Android ($active). " +
                    "Вернитесь в Chrome, оставьте сайт на экране. Последний шаг: " +
                    QuickOrderSession.lastChromeStatus
                } else {
                    "Автоматизация ожидает Chrome. Активное окно: $active; " +
                    "служба: ${if (QuickOrderSession.serviceConnected) "подключена" else "отключена"}; " +
                    "окон Chrome: ${chromeWindowRoots.size}; событий Chrome: ${QuickOrderSession.observedChromeEvents}. " +
                    "Последний шаг: " + QuickOrderSession.lastChromeStatus
                }
            return
        }
        if (gesturePending || System.currentTimeMillis() - QuickOrderSession.lastActionAt < 1100) return
        QuickOrderSession.lastChromeStatus = QuickOrderSession.message.value
        try {
            when (state) {
                QuickOrderSession.Step.OPEN_TRADE -> {
                    // Navigate using the bottom tab shown in the user's video.
                    // Only one distinct visible website control is safe to press.
                    val buttons = findWebsiteActions(root, listOf("Торговля"))
                    when (buttons.size) {
                        0 -> {
                            // The bottom nav is an icon-backed control on this
                            // site's mobile homepage. Accessibility sometimes
                            // omits its text, so use a *single* bounded tap
                            // only after recognizing the homepage context.
                            if (findText(root, "Популярное") != null &&
                                findText(root, "Фьючерсные контракты") != null) {
                                tapTradingTabFromHomepage(root)
                            } else {
                                QuickOrderSession.message.value =
                                    "«Торговля» не распознана. Откройте главную страницу сайта: резервное касание доступно только там"
                            }
                        }
                        1 -> pressOnce(buttons.single(), QuickOrderSession.Step.OPEN_COPY,
                            "Нажата вкладка «Торговля», ищу форму кода")
                        else -> QuickOrderSession.message.value =
                            "Обнаружено несколько кнопок «Торговля». Автонажатие отменено"
                    }
                }
                QuickOrderSession.Step.OPEN_COPY -> {
                    // The futures chart can contain unrelated editable fields.
                    // Only skip this button if the code form is visibly identified.
                    val codeFormVisible = findText(root, "Динамический пароль") != null ||
                        findText(root, "Динамический код") != null
                    if (codeFormVisible) {
                        move(QuickOrderSession.Step.ENTER_CODE, "Форма кода обнаружена, ищу поле ввода")
                    } else {
                        val buttons = findWebsiteActions(root, listOf("Быстрое копирование"))
                        when (buttons.size) {
                            0 -> {
                                // Chrome's accessibility hierarchy occasionally does
                                // not identify HTML text as a WebView descendant.
                                // The fallback still uses the exact text's screen
                                // bounds, and only on the BTC/USDT trading screen.
                                val pageBounds = findWebViewBounds(root)
                                val label = findText(root, "Быстрое копирование")
                                val tradingTitle = findText(root, "BTC/USDT") != null ||
                                    findText(root, "BTCUSDT") != null
                                if (label != null && pageBounds != null &&
                                    isWithinPage(label, pageBounds)) {
                                    pressOnce(label, QuickOrderSession.Step.ENTER_CODE,
                                        "Нажата «Быстрое копирование» на странице BTC/USDT")
                                } else if (tradingTitle && pageBounds != null &&
                                    isExpectedTradingUrl(root)) {
                                    // Fallback based on the confirmed mobile BTC/USDT
                                    // screenshot: this wide blue button is 75% down
                                    // the website viewport. At most one gesture.
                                    tapQuickCopyOnTradingScreen(pageBounds)
                                } else {
                                    QuickOrderSession.message.value =
                                        "Кнопка не найдена. Диагностика: BTC/USDT=${tradingTitle}; " +
                                        "WebView=${pageBounds != null}; адрес торговой страницы=" +
                                        isExpectedTradingUrl(root) +
                                        ". Нажатие отменено. Пришлите это сообщение."
                                }
                            }
                            1 -> pressOnce(buttons.single(), QuickOrderSession.Step.ENTER_CODE,
                                "Нажата «Быстрое копирование», ожидаю поле кода")
                            else -> QuickOrderSession.message.value =
                                "Несколько элементов «Быстрое копирование». Автонажатие отменено"
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
                    if (!isCodeDialogVisible(root)) {
                        QuickOrderSession.finish("Форма кода не подтверждена. Отправка отменена.", false)
                        return
                    }
                    val primaryButtons = findWebsiteActions(root, listOf("Отправить", "Отправлять"))
                    val buttons = if (primaryButtons.isNotEmpty()) primaryButtons else
                        findExactTextActions(root, listOf("Отправить", "Отправлять"))
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
        QuickOrderSession.lastChromeStatus = status
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

    private fun isCodeDialogVisible(root: AccessibilityNodeInfo): Boolean =
        findText(root, "Динамический пароль") != null ||
        findText(root, "Динамический код") != null

    /** Recognize MIUI Chrome's website text fields even when WebView nodes are absent. */
    private fun findWebsiteCodeFields(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val dialogVisible = isCodeDialogVisible(root)
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>()
        queue.add(root to false)
        var seen = 0
        while (queue.isNotEmpty() && seen++ < 1600) {
            val (n, withinWebView) = queue.removeFirst()
            val type = n.className?.toString().orEmpty()
            val id = n.viewIdResourceName.orEmpty()
            val inWeb = withinWebView || type.contains("WebView", ignoreCase = true)
            val browserUi = id.startsWith("com.android.chrome:id/") ||
                id.contains("url_bar", ignoreCase = true) ||
                type.contains("Omnibox", ignoreCase = true)
            val textInput = type.contains("EditText") || type.contains("input", true)
            if (!browserUi && n.isVisibleToUser && n.isEnabled && n.isEditable &&
                textInput && (inWeb || dialogVisible)) {
                val rect = Rect()
                n.getBoundsInScreen(rect)
                if (!rect.isEmpty && rect.width() > 60 && rect.height() > 18) {
                    candidates.add(n)
                }
            }
            if (!browserUi) for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.addLast(it to inWeb) }
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

    /** Returns the visible website content rectangle, never the Chrome toolbar. */
    private fun findWebViewBounds(root: AccessibilityNodeInfo): Rect? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var best: Rect? = null
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 1600) {
            val n = queue.removeFirst()
            if (n.isVisibleToUser &&
                n.className?.toString()?.contains("WebView", ignoreCase = true) == true) {
                val b = Rect()
                n.getBoundsInScreen(b)
                if (!b.isEmpty && b.width() > 180 && b.height() > 280 &&
                    (best == null || b.width() * b.height() > best.width() * best.height())) {
                    best = b
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
        }
        return best
    }

    private fun isWithinPage(node: AccessibilityNodeInfo, page: Rect): Boolean {
        if (!node.isVisibleToUser) return false
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return !bounds.isEmpty && page.contains(bounds.centerX(), bounds.centerY()) &&
            bounds.centerY() > page.top + 25
    }

    /**
     * Website and chart heading must match before attempting a fallback gesture.
     * Never trust URL bar position or visit a different domain.
     */
    private fun isExpectedTradingUrl(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var scanned = 0
        while (queue.isNotEmpty() && scanned++ < 1600) {
            val node = queue.removeFirst()
            val label = node.text?.toString().orEmpty() + " " +
                node.contentDescription?.toString().orEmpty()
            if (label.contains("pbeaocuec.com", ignoreCase = true) &&
                (label.contains("/ru/fut", ignoreCase = true) ||
                    label.contains("/futures", ignoreCase = true))) return true
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return false
    }

    private fun tapQuickCopyOnTradingScreen(page: Rect) {
        val x = page.exactCenterX()
        val y = page.top + page.height() * 0.75f
        if (page.height() < 650 || page.width() < 300 ||
            y < page.top + page.height() * 0.65f ||
            y > page.bottom - page.height() * 0.15f) {
            QuickOrderSession.message.value =
                "Нестандартные размеры торгового экрана. Автоматическое касание отменено."
            return
        }
        // A gesture is a one-shot navigation attempt, not proof the page changed.
        gesturePending = true
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 95L)).build()
        val launched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                gesturePending = false
                if (QuickOrderSession.step == QuickOrderSession.Step.OPEN_COPY) {
                    move(QuickOrderSession.Step.ENTER_CODE,
                        "Один раз нажата область «Быстрое копирование». Ожидаю появление формы кода.")
                }
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                gesturePending = false
                QuickOrderSession.finish(
                    "Android отменил касание «Быстрое копирование». Повтора не будет.", false)
            }
        }, handler)
        if (!launched) {
            gesturePending = false
            QuickOrderSession.finish(
                "Android не разрешил касание «Быстрое копирование». Повтора не будет.", false)
        }
    }

    /**
     * Fallback for the site's icon-only mobile bottom navigation.
     * The user video shows "Торговля" centered in the page bottom bar.
     * Do not use absolute screen coordinates: derive from the visible
     * WebView rectangle and refuse if it is not identified reliably.
     */

    /** Exact button text from Chrome's accessibility tree, scoped by caller to code dialog. */
    private fun findExactTextActions(
        root: AccessibilityNodeInfo,
        labels: List<String>
    ): List<AccessibilityNodeInfo> {
        val actions = mutableListOf<AccessibilityNodeInfo>()
        val boundsSeen = mutableSetOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen++ < 1600) {
            val node = queue.removeFirst()
            val id = node.viewIdResourceName.orEmpty()
            if (!id.startsWith("com.android.chrome:id/") && node.isVisibleToUser) {
                val label = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                if (labels.any { label.equals(it, true) || desc.equals(it, true) }) {
                    var action = node
                    var parent = node.parent
                    var n = 0
                    while (!action.isClickable && parent != null && n++ < 4) {
                        action = parent
                        parent = parent.parent
                    }
                    val rect = Rect()
                    action.getBoundsInScreen(rect)
                    if (action.isEnabled && !rect.isEmpty) {
                        val key = "${rect.left}:${rect.top}:${rect.right}:${rect.bottom}"
                        if (boundsSeen.add(key)) actions.add(action)
                    }
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return actions
    }

    private fun tapTradingTabFromHomepage(root: AccessibilityNodeInfo) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var webRect: Rect? = null
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 1600) {
            val node = queue.removeFirst()
            if (node.className?.toString()?.contains("WebView", ignoreCase = true) == true &&
                node.isVisibleToUser) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (!rect.isEmpty && rect.width() > 180 && rect.height() > 280 &&
                    (webRect == null || rect.width() * rect.height() >
                        webRect.width() * webRect.height())) {
                    webRect = rect
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }
        val area = webRect
        if (area == null) {
            QuickOrderSession.message.value =
                "Нижнее меню обнаружено на видео, но WebView не найден в дереве Android. Касание отменено"
            return
        }
        // Mobile bottom nav occupies approximately the bottom 15% of
        // page width in portrait; center tab is at 50% of page width.
        val x = area.exactCenterX()
        val y = area.bottom - area.width() * 0.15f
        if (y < area.top + area.height() * 0.55f || y > area.bottom - 12f) {
            QuickOrderSession.message.value =
                "Положение нижнего меню не определено. Касание отменено"
            return
        }
        gesturePending = true
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 90L)).build()
        val scheduled = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                gesturePending = false
                if (QuickOrderSession.step == QuickOrderSession.Step.OPEN_TRADE) {
                    move(QuickOrderSession.Step.OPEN_COPY,
                        "Выполнено одно касание вкладки «Торговля». Проверяю следующую страницу")
                }
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                gesturePending = false
                QuickOrderSession.finish(
                    "Android отменил касание «Торговля». Автоматический повтор запрещён.", false)
            }
        }, handler)
        if (!scheduled) {
            gesturePending = false
            QuickOrderSession.finish(
                "Android не разрешил касание «Торговля». Автоматический повтор запрещён.", false)
        }
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
