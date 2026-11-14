package com.example.autoorders

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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
    @Volatile var diagnosticsEnabled = false
    @Volatile var screenCheckEnabled = false
    @Volatile var screenCheckDone = false
    @Volatile var diagnosticsCaptured = false
    @Volatile var observedChromeEvents = 0
    @Volatile var lastChromeStatus = "Chrome ещё не был обнаружен"

    fun begin(value: String) {
        diagnosticsEnabled = false
        screenCheckEnabled = false
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
    fun beginDiagnostics() {
        screenCheckEnabled = false
        code = ""
        step = Step.IDLE
        diagnosticsEnabled = true
        diagnosticsCaptured = false
        message.value = "Проверка включена. Откройте «Быстрое копирование» в Chrome вручную. Действия не выполняются."
    }
    fun beginScreenCheck() {
        code = ""
        step = Step.IDLE
        diagnosticsEnabled = false
        screenCheckEnabled = true
        screenCheckDone = false
        message.value = "Проверка изображения запущена. Откройте форму в Chrome вручную. Снимки не сохраняются."
    }
    fun cancel() {
        screenCheckEnabled = false
        diagnosticsEnabled = false
        diagnosticsCaptured = false
        code = ""
        step = Step.IDLE
        message.value = "Остановлено"
    }
}

class QuickOrderAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var gesturePending = false
    private var screenCheckPending = false
    private var lastScreenCheckAt = 0L
    private val screenRecognizerLazy = lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val screenRecognizer get() = screenRecognizerLazy.value
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
        if (screenRecognizerLazy.isInitialized()) screenRecognizerLazy.value.close()
        QuickOrderSession.cancel()
        super.onDestroy()
    }

    private fun tick() {
        if (QuickOrderSession.screenCheckEnabled) {
            runScreenCheck()
            return
        }
        if (QuickOrderSession.diagnosticsEnabled) {
            inspectChrome()
            return
        }
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
                            "Форма «Динамический пароль» видна, но поле ввода не предоставлено Chrome для Accessibility. Адресная строка исключена; ввод отменён."
                        } else {
                            "Chrome показал ${inputs.size} возможных полей. Выбор неоднозначен, ввод отменён."
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

    /**
     * Read-only accessibility diagnostics. Never examines text values of
     * password inputs, performs clicks or stores credentials.
     */
    /** Only inspect static labels, never editable fields or their contents. */
    private fun findDiagnosticLabel(
        root: AccessibilityNodeInfo,
        phrase: String
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var n = 0
        while (queue.isNotEmpty() && n++ < 1800) {
            val node = queue.removeFirst()
            val type = node.className?.toString().orEmpty()
            val id = node.viewIdResourceName.orEmpty()
            val chromeUi = id.startsWith("com.android.chrome:id/") ||
                id.contains("url_bar", true) || type.contains("Omnibox", true)
            if (!chromeUi) {
                val editable = node.isEditable || node.actionList.any {
                    it.id == AccessibilityNodeInfo.ACTION_SET_TEXT
                }
                if (node.isVisibleToUser && !editable) {
                    val text = node.text?.toString().orEmpty()
                    val description = node.contentDescription?.toString().orEmpty()
                    if (text.contains(phrase, true) ||
                        description.contains(phrase, true)) return node
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let(queue::addLast)
                }
            }
        }
        return null
    }

    /**
     * Opt-in screenshot check for MIUI Chrome when accessibility exposes only
     * the browser shell (4 nodes). No buttons are pressed and no image or
     * recognized text is saved or transmitted. Only boolean labels are shown.
     */
    private fun runScreenCheck() {
        if (QuickOrderSession.screenCheckDone || screenCheckPending) return
        val active = rootInActiveWindow
        val chromeVisible = active?.packageName?.toString() == "com.android.chrome" ||
            windows.orEmpty().any {
                it.root?.packageName?.toString() == "com.android.chrome" && it.isActive
            }
        if (!chromeVisible) return
        val now = System.currentTimeMillis()
        if (now - lastScreenCheckAt < 3500L) return
        lastScreenCheckAt = now
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            QuickOrderSession.message.value =
                "Снимок экрана недоступен: требуется Android 11 или новее."
            QuickOrderSession.screenCheckDone = true
            return
        }
        screenCheckPending = true
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onFailure(errorCode: Int) {
                    screenCheckPending = false
                    QuickOrderSession.message.value =
                        "Android запретил получение изображения экрана (код ошибки $errorCode). " +
                            "Убедитесь, что служба специальных возможностей включена заново."
                }
                override fun onSuccess(result: ScreenshotResult) {
                    var bitmap: Bitmap? = null
                    try {
                        val hardware = result.hardwareBuffer
                        try {
                            val wrapped = Bitmap.wrapHardwareBuffer(hardware, result.colorSpace)
                            bitmap = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                        } finally {
                            hardware.close()
                        }
                        val image = bitmap ?: run {
                            screenCheckPending = false
                            QuickOrderSession.message.value = "Не удалось прочитать снимок Android."
                            return
                        }
                        val width = image.width
                        val height = image.height
                        // ML Kit recognition is on-device. We inspect only
                        // presence of labels, NEVER store raw or recognized text.
                        screenRecognizer.process(InputImage.fromBitmap(image, 0))
                            .addOnSuccessListener { detected ->
                                if (!QuickOrderSession.screenCheckEnabled) return@addOnSuccessListener
                                val labels = detected.textBlocks.flatMap { it.lines }.map { it.text }
                                val header = labels.any {
                                    it.contains("Быстрое", true) ||
                                        it.contains("копирование", true)
                                }
                                val passwordLabel = labels.any {
                                    it.contains("Динамический", true) ||
                                        it.contains("пароль", true)
                                }
                                val submit = labels.any {
                                    it.contains("Отправлять", true)
                                }
                                val ready = header && passwordLabel
                                QuickOrderSession.screenCheckDone = ready
                                QuickOrderSession.message.value =
                                    "Проверка изображения (локально, без сохранения):\n" +
                                        "Снимок Android: получен (${width}×${height})\n" +
                                        "Текстовых строк OCR: ${labels.size}\n" +
                                        "«Быстрое копирование»: ${if (header) "обнаружено" else "не обнаружено"}\n" +
                                        "«Динамический пароль»: ${if (passwordLabel) "обнаружен" else "не обнаружен"}\n" +
                                        "«Отправлять»: ${if (submit) "обнаружено" else "не обнаружено"}\n" +
                                        "Распознавание кириллицы экспериментальное: отсутствие надписи не означает её отсутствия на экране.\n" +
                                        "${if (ready) "Проверка завершена." else "Повторяю проверку, пока открыт Chrome."}\n" +
                                        "Пароль не введён. Никаких нажатий и отправок не выполнялось."
                            }
                            .addOnFailureListener { e ->
                                QuickOrderSession.message.value =
                                    "Снимок получен, но локальное распознавание не запустилось: " +
                                        e.javaClass.simpleName
                            }
                            .addOnCompleteListener {
                                image.recycle()
                                screenCheckPending = false
                            }
                    } catch (e: Exception) {
                        bitmap?.recycle()
                        screenCheckPending = false
                        QuickOrderSession.message.value =
                            "Ошибка обработки снимка: " + e.javaClass.simpleName
                    }
                }
            })
        } catch (e: Exception) {
            screenCheckPending = false
            QuickOrderSession.message.value =
                "Снимок экрана не разрешён Android: " + e.javaClass.simpleName
        }
    }

    private fun diagnosticNodeCount(root: AccessibilityNodeInfo): Int {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        while (queue.isNotEmpty() && count < 1800) {
            val node = queue.removeFirst()
            count++
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return count
    }

    /** Observe the most informative Chrome window; never cache password nodes. */
    private fun inspectChrome() {
        // Once the complete code form is observed, preserve its read-only
        // snapshot for the user to inspect after returning to AutoOrders.
        if (QuickOrderSession.diagnosticsCaptured) return

        val active = rootInActiveWindow
        val chromeRoots = mutableListOf<AccessibilityNodeInfo>()
        if (active?.packageName?.toString() == "com.android.chrome") {
            chromeRoots.add(active)
        }
        for (window in windows.orEmpty()) {
            val root = window.root ?: continue
            if (root.packageName?.toString() == "com.android.chrome" &&
                chromeRoots.none { it.windowId == root.windowId &&
                    it.childCount == root.childCount }) {
                chromeRoots.add(root)
            }
        }
        if (chromeRoots.isEmpty()) {
            // Never overwrite the last Chrome observation when the user
            // switches to AutoOrders or the launcher to view the report.
            if (active?.packageName?.toString() == packageName ||
                active?.packageName?.toString() == "com.miui.home") return
            QuickOrderSession.message.value =
                "Диагностика ожидает окно Chrome. Оставьте страницу «Быстрое копирование» на экране, " +
                "затем вернитесь в приложение."
            return
        }

        val chrome = chromeRoots.maxByOrNull { root ->
            val labels = (if (findDiagnosticLabel(root, "Динамический пароль") != null) 10000 else 0) +
                (if (findDiagnosticLabel(root, "Быстрое копирование") != null) 5000 else 0)
            labels + diagnosticNodeCount(root)
        } ?: return

        val hasFormTitle = findDiagnosticLabel(chrome, "Быстрое копирование") != null
        val hasFieldLabel = findDiagnosticLabel(chrome, "Динамический пароль") != null
        val sendButton = findDiagnosticLabel(chrome, "Отправлять")
        val rootBounds = Rect().also { chrome.getBoundsInScreen(it) }
        var editCandidates = 0
        var setTextSupported = 0
        var webNodes = 0
        var totalNodes = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(chrome)
        while (queue.isNotEmpty() && totalNodes < 1800) {
            val node = queue.removeFirst()
            totalNodes++
            val klass = node.className?.toString().orEmpty()
            val id = node.viewIdResourceName.orEmpty()
            if (klass.contains("WebView", true)) webNodes++
            val browserBar = id.startsWith("com.android.chrome:id/") ||
                id.contains("url_bar", true) || klass.contains("Omnibox", true)
            val supportsSetText = node.actionList.any {
                it.id == AccessibilityNodeInfo.ACTION_SET_TEXT
            }
            if (!browserBar && node.isVisibleToUser && node.isEnabled &&
                (node.isEditable || supportsSetText)) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if (!bounds.isEmpty && bounds.width() > 60 &&
                    bounds.centerY() > rootBounds.top + rootBounds.height() * 0.16f) {
                    editCandidates++
                    if (supportsSetText) setTextSupported++
                }
            }
            if (!browserBar) for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }

        // Do not freeze a snapshot before the actual password form loads.
        // Continue observing until the form header and password label appear.
        val formObserved = hasFormTitle && hasFieldLabel
        QuickOrderSession.diagnosticsCaptured = formObserved
        QuickOrderSession.message.value =
            "Проверка формы — только чтение, пароль не считывается:\n" +
            "Форма обнаружена: ${if (formObserved) "да" else "нет (ожидаю открытия)"}\n" +
            "Chrome: доступен; найдено окон: ${chromeRoots.size}\n" +
            "Заголовок «Быстрое копирование»: ${if (hasFormTitle) "найден" else "не найден"}\n" +
            "Надпись «Динамический пароль»: ${if (hasFieldLabel) "найдена" else "не найдена"}\n" +
            "Доступных полей ввода: $editCandidates\n" +
            "Из них поддерживают вставку: $setTextSupported\n" +
            "Кнопка «Отправлять»: ${if (sendButton != null) "найдена" else "не найдена"}\n" +
            "Кнопка активна: ${sendButton?.isEnabled ?: false}\n" +
            "WebView-узлов: $webNodes; проверено узлов: $totalNodes\n" +
            "Событий Chrome: ${QuickOrderSession.observedChromeEvents}\n" +
            "${if (formObserved) "Результат зафиксирован." else "Проверка продолжается, пока открыт Chrome."}\n" +
            "Никаких нажатий и отправок не выполнялось."
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

    /**
     * The code page is a separate Chrome document: /ru/mer...
     * MIUI may present its HTML input without a WebView class or isEditable.
     * Never select a toolbar element, another site or an ambiguous field.
     */
    private fun findWebsiteCodeFields(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        if (!isCodeDialogVisible(root)) return emptyList()
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var inspected = 0
        while (queue.isNotEmpty() && inspected++ < 1800) {
            val node = queue.removeFirst()
            val type = node.className?.toString().orEmpty()
            val resourceId = node.viewIdResourceName.orEmpty()
            val hint = node.hintText?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            val text = node.text?.toString().orEmpty()
            val addressBar = resourceId.startsWith("com.android.chrome:id/") ||
                resourceId.contains("url_bar", true) ||
                type.contains("Omnibox", true)
            val textAction = node.actionList.any {
                it.id == AccessibilityNodeInfo.ACTION_SET_TEXT
            }
            val dynamicHint = listOf(hint, description, text).any {
                it.contains("динамическ", true) ||
                    it.contains("password", true)
            }
            val inputKind = type.contains("EditText", true) ||
                type.contains("input", true) || node.isEditable
            if (!addressBar && node.isVisibleToUser && node.isEnabled &&
                (node.isEditable || textAction) && (inputKind || dynamicHint)) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val rootBounds = Rect()
                root.getBoundsInScreen(rootBounds)
                // Exclude browser navigation bars and outside-page controls.
                if (!bounds.isEmpty && bounds.width() > 80 &&
                    bounds.height() > 20 &&
                    bounds.centerY() > rootBounds.top + rootBounds.height() * 0.18f &&
                    bounds.centerY() < rootBounds.bottom - rootBounds.height() * 0.12f) {
                    candidates.add(node)
                }
            }
            if (!addressBar) for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
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
