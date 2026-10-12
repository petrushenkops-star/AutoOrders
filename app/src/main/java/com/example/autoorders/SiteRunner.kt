package com.example.autoorders

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener

/** Finite-state browser driver. It never retries after an uncertain financial confirmation. */
class SiteRunner(
    private val view: WebView,
    private val progress: (String) -> Unit,
    private val completed: (OrderStatus, String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var stage = ""
    private var code = ""
    private var active = false
    private var ticks = 0
    private var confirmationMayHaveBeenSent = false
    private var script: String? = null

    fun isActive() = active

    fun start(dynamicCode: String) {
        require(dynamicCode.isNotBlank() && dynamicCode.all { it.isDigit() })
        if (active) return
        code = dynamicCode
        stage = "OPEN_COPY"
        ticks = 0
        confirmationMayHaveBeenSent = false
        active = true
        progress("Начало обработки заказа. Откройте личный кабинет на вкладке «Сайт».")
        poll()
    }

    fun stop() {
        if (!active) return
        finish(statusAfterInterruption(confirmationMayHaveBeenSent),
            "Остановлено пользователем. Если подтверждение могло быть отправлено, " +
                "проверьте результат на сайте перед повторной попыткой.")
    }

    private fun poll() {
        if (!active) return
        // Avoid resubmitting if the financial outcome cannot be established.
        ticks++
        if (ticks > 75) {
            finish(statusAfterInterruption(confirmationMayHaveBeenSent),
                "Истекло время ожидания. Если заказ мог быть подтверждён, " +
                    "проверьте результат на сайте перед повторной попыткой.")
            return
        }
        val url = view.url ?: ""
        if (!url.startsWith("https://lxcompintb.com/")) {
            progress("Откройте и авторизуйтесь на сайте lxcompintb.com во встроенном браузере")
            handler.postDelayed({ poll() }, 1000)
            return
        }
        if (script == null) script = view.context.assets.open("driver.js").bufferedReader().use { it.readText() }
        // Mark uncertainty BEFORE executing a script that may press financial confirmation.
        // A stop/process death between the click and JS callback must not look like failure.
        if (stage == "WAIT_CONFIRM") confirmationMayHaveBeenSent = true
        val js = script!!.replace("__STAGE__", JSONObject.quote(stage)).replace("__CODE__", JSONObject.quote(code))
        view.evaluateJavascript(js) { raw ->
            if (!active) return@evaluateJavascript
            try {
                val parsed = JSONTokener(raw).nextValue() as String
                val obj = JSONObject(parsed)
                when (obj.optString("status")) {
                    "advance" -> {
                        stage = obj.optString("detail")
                        if (stage != "ENTER_CODE") code = "" // Code has been submitted after ENTER_CODE; do not keep it.
                        progress(when (stage) {
                            "ENTER_CODE" -> "Подготовка поля цифрового кода"
                            "WAIT_DETAILS" -> "Код отправлен, ожидание параметров заказа"
                            "WAIT_CONFIRM" -> "Ожидание подтверждения суммы"
                            "WAIT_RESULT" -> "Подтверждение отправлено, ожидание результата"
                            else -> "Обработка заказа"
                        })
                    }
                    "wait" -> progress(obj.optString("detail"))
                    "error" -> {
                        finish(statusAfterInterruption(confirmationMayHaveBeenSent),
                            obj.optString("detail") + if (confirmationMayHaveBeenSent)
                                ". Результат заказа неизвестен: проверьте его на сайте." else "")
                        return@evaluateJavascript
                    }
                    "success" -> { finish(OrderStatus.SUCCESS, obj.optString("detail")); return@evaluateJavascript }
                    else -> {
                        finish(statusAfterInterruption(confirmationMayHaveBeenSent),
                            "Неизвестный ответ сайта. Проверьте заказ.")
                        return@evaluateJavascript
                    }
                }
            } catch (_: Exception) {
                finish(statusAfterInterruption(confirmationMayHaveBeenSent),
                    "Не удалось распознать страницу. Проверьте заказ на сайте.")
                return@evaluateJavascript
            }
            handler.postDelayed({ poll() }, 1100)
        }
    }

    private fun finish(status: OrderStatus, detail: String) {
        active = false
        code = ""
        stage = ""
        confirmationMayHaveBeenSent = false
        handler.removeCallbacksAndMessages(null)
        progress(detail)
        completed(status, detail)
    }
}
