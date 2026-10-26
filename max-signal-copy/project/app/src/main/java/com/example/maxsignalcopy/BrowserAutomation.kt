package com.example.maxsignalcopy

import android.os.SystemClock
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener

/** A bounded state machine. It NEVER repeats a confirmation on uncertain results. */
class BrowserAutomation(
    private val browser: WebView,
    private val status: (String) -> Unit,
    private val finished: (String, String, Boolean) -> Unit,
) {
    // Foreground service owns the ticker, independently of Activity.onPause.
    private var awaitingJs = false
    private var jsStartedAt = 0L
    private val jsResponseTimeoutMs = 15_000L
    private var active = false
    private var stage = ""
    private var code = ""
    private var ticks = 0
    private var uncertain = false
    private var script = ""
    private var orderAmount = ""
    // The site is a SPA. Nested pages hide the bottom navigation. A controlled
    // home reload restores the predictable entry route without resubmitting orders.
    private var loadingHome = false
    private var homeLoaded = false
    private var homeDestination = ""
    private var lastStage = ""
    private var stageTicks = 0
    private var orderListRecoveryUsed = false
    private val homeUrl = "https://lumecoe.com/ru"

    fun onPageFinished(url: String) {
        // Only the main-frame homepage load that we requested unlocks the driver.
        if (active && loadingHome && safeUrl(url)) {
            val uri = android.net.Uri.parse(url)
            if (uri.path.orEmpty().trimEnd('/') == "/ru") homeLoaded = true
        }
    }

    private fun reloadHome(nextStage: String, message: String) {
        loadingHome = true
        homeLoaded = false
        homeDestination = nextStage
        stage = "WAIT_SITE_HOME"
        status(message)
        browser.loadUrl(homeUrl)
    }

    fun isActive() = active
    fun stageName() = stage.ifBlank { "IDLE" }

    fun rendererLost() {
        if (active) finish(if (uncertain) "НЕИЗВЕСТНО" else "ОШИБКА",
            "WebView остановлен Android. Проверьте «Заказы». Повтор не запускается.")
    }

    fun start(dynamicCode: String) {
        require(dynamicCode.all { it in '0'..'9' } && dynamicCode.length in 4..10)
        if (active) return
        val url = browser.url.orEmpty()
        if (!safeUrl(url)) {
            finished("ОШИБКА", "Откройте https://lumecoe.com/ru и войдите в аккаунт.", true)
            return
        }
        script = browser.context.assets.open("driver.js").bufferedReader().use { it.readText() }
        code = dynamicCode
        uncertain = false
        orderAmount = ""
        ticks = 0
        active = true
        orderListRecoveryUsed = false
        lastStage = ""
        stageTicks = 0
        reloadHome("NAVIGATE_TRADE", "Открываю главную страницу перед оформлением заказа")
        // First and following steps run from the service heartbeat.
    }

    fun stop() {
        if (!active) return
        finish(if (uncertain) "НЕИЗВЕСТНО" else "ОСТАНОВЛЕНО",
            if (uncertain) "Остановлено. Возможна отправка заказа — проверьте на сайте."
            else "Остановлено до отправки заказа.")
    }

    private fun safeUrl(url: String): Boolean {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return false
        val host = uri.host.orEmpty().lowercase()
        return uri.scheme == "https" && (host == "lumecoe.com" || host.endsWith(".lumecoe.com"))
    }

    /** Service-driven single-flight execution on WebView's owning UI thread. */
    fun pulse() {
        if (!active) return
        if (awaitingJs) {
            if (SystemClock.elapsedRealtime() - jsStartedAt >= jsResponseTimeoutMs) {
                finish(if (uncertain) "НЕИЗВЕСТНО" else "ОШИБКА",
                    "WebView не ответил за 15 секунд. Проверьте заказ вручную; повтора нет.")
            }
            return
        }
        if (!safeUrl(browser.url.orEmpty())) {
            finish(if (uncertain) "НЕИЗВЕСТНО" else "ОШИБКА", "Сайт изменил адрес. Дальнейшие действия остановлены.")
            return
        }
        if (++ticks > 140) {
            finish(if (uncertain) "НЕИЗВЕСТНО" else "ОШИБКА", "Превышено время ожидания. Проверьте результат вручную.")
            return
        }
        if (stage != lastStage) { lastStage = stage; stageTicks = 0 }
        stageTicks++
        if (stage == "WAIT_SITE_HOME") {
            if (!homeLoaded) {
                return
            }
            loadingHome = false
            homeLoaded = false
            stage = homeDestination
            homeDestination = ""
            // Do not evaluate old page JS until WebView reports the new home loaded.
        }
        // A stale SPA route could leave us waiting for Orders while displaying Home.
        // Recover once; financial confirmation is never repeated during recovery.
        if (stage == "WAIT_ORDERS" && stageTicks >= 15) {
            if (orderListRecoveryUsed) {
                finish("НЕИЗВЕСТНО", "Не удалось открыть раздел «Заказы». Заказ мог быть создан; проверьте видимую страницу вручную. Повторная отправка запрещена.")
                return
            }
            orderListRecoveryUsed = true
            reloadHome("NAVIGATE_ORDERS", "Раздел «Заказы» не открылся. Возвращаюсь на главную для повторного перехода")
            return
        }
        if (stage == "SUBMIT_CODE" || stage == "CONFIRM") uncertain = true
        val js = script.replace("__STAGE__", JSONObject.quote(stage))
            .replace("__CODE__", JSONObject.quote(code))
            .replace("__AMOUNT__", JSONObject.quote(orderAmount))
            .replace("__STAGE_TICKS__", stageTicks.toString())
        awaitingJs = true
        jsStartedAt = SystemClock.elapsedRealtime()
        try {
            browser.evaluateJavascript(js) { raw ->
                awaitingJs = false
                if (!active) return@evaluateJavascript
            val result = try {
                JSONObject(JSONTokener(raw).nextValue() as String)
            } catch (_: Exception) {
                finish(if (uncertain) "НЕИЗВЕСТНО" else "ОШИБКА", "Неверный ответ страницы; нужно проверить вручную.")
                return@evaluateJavascript
            }
            // The amount is obtained on the order-copy screen, BEFORE confirmation.
            // An ambiguous or changing amount must never be treated as proof.
            val reportedAmount = result.optString("amount", "")
            if (reportedAmount.matches(Regex("\\d+(?:\\.\\d{1,12})?"))) {
                if (orderAmount.isEmpty()) orderAmount = reportedAmount
                else if (orderAmount != reportedAmount) {
                    finish("НЕИЗВЕСТНО", "Сумма заказа изменилась. Проверьте заказ вручную.")
                    return@evaluateJavascript
                }
            }
            when (result.optString("status")) {
                "advance" -> {
                    val next = result.optString("next")
                    if (next !in setOf("NAVIGATE_TRADE", "WAIT_PROMO_DISMISS", "WAIT_TRADE", "OPEN_FAST_COPY", "ENTER_CODE", "SUBMIT_CODE",
                            "WAIT_ORDER", "OPEN_ORDER_COPY", "WAIT_MODAL", "CONFIRM", "WAIT_RESULT",
                            "NAVIGATE_ORDERS", "WAIT_ORDERS", "CHECK_ORDER_STATUS", "WAIT_QUICK_POPUP_CLOSE")) {
                        finish("НЕИЗВЕСТНО", "Неизвестный шаг страницы.")
                        return@evaluateJavascript
                    }
                    if (next == "NAVIGATE_ORDERS" && stage == "WAIT_RESULT") {
                        // The current nested copy screen may not display Orders tab.
                        // Reload Home only AFTER the success message for this run.
                        reloadHome("NAVIGATE_ORDERS", "Заказ создан. Открываю главную для проверки статуса")
                        return@evaluateJavascript
                    }
                    stage = next
                    if (next == "WAIT_ORDER") code = "" // Never retain dynamic code after submitting it.
                    status("Этап: " + when(next) {
                        "NAVIGATE_TRADE" -> "переход в торговлю"
                        "WAIT_PROMO_DISMISS" -> "закрытие рекламного окна"
                        "WAIT_TRADE" -> "загрузка раздела «Торговля»"
                        "OPEN_FAST_COPY" -> "открытие быстрого копирования"
                        "ENTER_CODE" -> "ввод кода"
                        "SUBMIT_CODE" -> "отправка кода"
                        "WAIT_ORDER" -> "ожидание заявки"
                        "OPEN_ORDER_COPY" -> "копирование заявки"
                        "WAIT_MODAL" -> "ожидание подтверждения"
                        "CONFIRM" -> "однократное подтверждение"
                        "NAVIGATE_ORDERS" -> "переход к заказам"
                        "WAIT_ORDERS" -> "загрузка списка «Заказы» — кнопки сайта видны на экране"
                        "CHECK_ORDER_STATUS" -> "проверка статуса «В ожидании»"
                        "WAIT_QUICK_POPUP_CLOSE" -> "закрытие окна «Быстрый»"
                        else -> "проверка результата"
                    })
                }
                "wait" -> {
                    // Explain the actual blocking UI step instead of a silent timeout.
                    val detail = result.optString("detail")
                    if (detail.isNotBlank()) status(detail)
                }
                "pending" -> {
                    if (stage in setOf("CHECK_ORDER_STATUS", "WAIT_QUICK_POPUP_CLOSE") && orderAmount.isNotEmpty()) {
                        finish("В ОЖИДАНИИ", "Заказ BTC/USDT на сумму $orderAmount — В ожидании")
                    } else finish("НЕИЗВЕСТНО", "Статус не подтверждён проверкой списка заказов")
                    return@evaluateJavascript
                }
                "error" -> {
                    finish(if (uncertain) "НЕИЗВЕСТНО" else "ОШИБКА", result.optString("detail", "Действие на сайте не выполнено."))
                    return@evaluateJavascript
                }
                else -> {
                    finish(if (uncertain) "НЕИЗВЕСТНО" else "ОШИБКА", "Неизвестное состояние страницы.")
                    return@evaluateJavascript
                }
            }
            }
        } catch (_: Exception) {
            awaitingJs = false
            finish(if (uncertain) "НЕИЗВЕСТНО" else "ОШИБКА",
                "Не удалось выполнить шаг WebView. Проверьте заказ вручную.")
        }
    }

    private fun finish(result: String, detail: String) {
        active = false
        awaitingJs = false
        stage = ""
        code = ""
        orderAmount = ""
        loadingHome = false
        homeLoaded = false
        homeDestination = ""
        // Before SUBMIT_CODE the automation cannot have sent an order. Allow a
        // retry with the same manually entered code after fixing navigation.
        val safeToRetry = !uncertain && result in setOf("ОШИБКА", "ОСТАНОВЛЕНО")
        uncertain = false
        status(detail)
        finished(result, detail, safeToRetry)
    }
}
