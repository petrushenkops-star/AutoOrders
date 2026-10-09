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
    private val completed: (Boolean, String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var stage = ""
    private var code = ""
    private var active = false
    private var ticks = 0
    private var script: String? = null

    fun isActive() = active

    fun start(dynamicCode: String) {
        require(dynamicCode.isNotBlank() && dynamicCode.all { it.isDigit() })
        if (active) return
        code = dynamicCode
        stage = "OPEN_COPY"
        ticks = 0
        active = true
        progress("Начало обработки заказа. Откройте личный кабинет на вкладке «Сайт».")
        poll()
    }

    fun stop() {
        if (!active) return
        finish(false, "Остановлено пользователем. Если подтверждение уже было отправлено, проверьте заказ на сайте.")
    }

    private fun poll() {
        if (!active) return
        // Avoid resubmitting if the financial outcome cannot be established.
        ticks++
        if (ticks > 75) {
            finish(false, "Истекло время ожидания. Статус заказа необходимо проверить вручную.")
            return
        }
        val url = view.url ?: ""
        if (!url.startsWith("https://lxcompintb.com/")) {
            progress("Откройте и авторизуйтесь на сайте lxcompintb.com во встроенном браузере")
            handler.postDelayed({ poll() }, 1000)
            return
        }
        if (script == null) script = view.context.assets.open("driver.js").bufferedReader().use { it.readText() }
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
                    "error" -> { finish(false, obj.optString("detail")); return@evaluateJavascript }
                    "success" -> { finish(true, obj.optString("detail")); return@evaluateJavascript }
                    else -> { finish(false, "Неизвестный ответ сайта"); return@evaluateJavascript }
                }
            } catch (_: Exception) { finish(false, "Не удалось распознать страницу. Проверьте заказ на сайте."); return@evaluateJavascript }
            handler.postDelayed({ poll() }, 1100)
        }
    }

    private fun finish(success: Boolean, detail: String) {
        active = false
        code = ""
        stage = ""
        handler.removeCallbacksAndMessages(null)
        progress(detail)
        completed(success, detail)
    }
}
