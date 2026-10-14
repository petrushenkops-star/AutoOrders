package com.example.autoorders

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream

/** Isolated offline WebView. Never connects to the real financial website. */
class SimulatorActivity : ComponentActivity() {
    private lateinit var demo: WebView
    private lateinit var runner: SiteRunner
    private var loaded by mutableStateOf(false)
    private var running by mutableStateOf(false)
    private var message by mutableStateOf("Загрузка офлайн-страницы…")
    private var outcome by mutableStateOf("success")
    private var covering by mutableStateOf("normal")
    private var diagnostics by mutableStateOf("")
    private var cycleRunning by mutableStateOf(false)
    private var cycleSummary by mutableStateOf("")
    private var cycleTrace by mutableStateOf<List<String>>(emptyList())
    private val cycleHandler = android.os.Handler(android.os.Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        demo = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?
                ): Boolean = true
                override fun shouldInterceptRequest(
                    view: WebView?, request: WebResourceRequest?
                ): WebResourceResponse = WebResourceResponse(
                    "text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0))
                )
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url?.startsWith(SIMULATOR_URL) == true) {
                        loaded = true
                        message = "Готово. Для автоматического теста используется код 123456."
                    }
                }
            }
            val html = assets.open("simulator.html").bufferedReader().use { it.readText() }
            val js = assets.open("simulator.js").bufferedReader().use { it.readText() }
            loadDataWithBaseURL(
                SIMULATOR_URL + "index.html",
                html.replace("__SIMULATOR_SCRIPT__", js),
                "text/html", "UTF-8", null
            )
        }
        runner = SiteRunner(
            demo, { message = it },
            { status, details ->
                running = false
                message = when (status) {
                    OrderStatus.SUCCESS -> "Тест успешно пройден: " + details
                    OrderStatus.UNKNOWN -> "Тест остановлен (без реального заказа): " + details
                    else -> "Тест завершился: " + details
                }
            },
            simulation = true
        )
        setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier.fillMaxSize().statusBarsPadding()
                        .navigationBarsPadding().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Офлайн-симулятор", style = MaterialTheme.typography.titleLarge)
                    Text("Только тестовые данные, без подключения к сайту.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("success" to "Успех", "error" to "Ошибка", "hang" to "Нет ответа")
                            .forEach { (value, title) ->
                                OutlinedButton(
                                    enabled = loaded && !running && !cycleRunning,
                                    onClick = { outcome = value; resetPage() }
                                ) { Text((if (outcome == value) "✓ " else "") + title) }
                            }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(enabled = loaded && !running && !cycleRunning,
                            onClick = { covering = "normal"; resetPage() }) {
                            Text((if (covering == "normal") "✓ " else "") + "Видно")
                        }
                        OutlinedButton(enabled = loaded && !running && !cycleRunning,
                            onClick = { covering = "blocked"; resetPage() }) {
                            Text((if (covering == "blocked") "✓ " else "") + "Перекрыто")
                        }
                    }
                    OutlinedButton(
                        enabled = loaded && !running && !cycleRunning,
                        onClick = { covering = "stubborn"; resetPage() }
                    ) {
                        Text((if (covering == "stubborn") "✓ " else "") +
                            "Сложное перекрытие (тест DOM)")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(enabled = loaded && !running && !cycleRunning, onClick = {
                            running = true
                            runner.start("123456")
                        }) { Text("Тест") }
                        OutlinedButton(enabled = running, onClick = { runner.stop() }) {
                            Text("СТОП")
                        }
                        OutlinedButton(enabled = loaded && !running && !cycleRunning,
                            onClick = { resetPage() }) { Text("Сброс") }
                    }
                    Button(
                        enabled = loaded && !running && !cycleRunning,
                        onClick = { openTestDialog() }
                    ) { Text("Открыть тестовое окно") }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(enabled = loaded && !running && !cycleRunning,
                            onClick = { inspectPage() }) { Text("Проверить") }
                        OutlinedButton(enabled = loaded && !running && !cycleRunning,
                            onClick = { revealPage() }) { Text("Исправить перекрытие") }
                    }
                    OutlinedButton(enabled = loaded && !running && !cycleRunning,
                        onClick = { rerenderTestDialog() }) {
                        Text("Перерисовать окно (тест)")
                    }
                    Button(enabled = loaded && !running && !cycleRunning,
                        onClick = { runFullCycle() }) {
                        Text("Полный тест окна (без заказов)")
                    }
                    if (cycleSummary.isNotBlank()) {
                        Text(cycleSummary, style = MaterialTheme.typography.bodySmall)
                    }
                    if (cycleTrace.isNotEmpty()) {
                        Text(cycleTrace.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text(message, style = MaterialTheme.typography.bodySmall)
                    if (diagnostics.isNotBlank()) {
                        Text(diagnostics, style = MaterialTheme.typography.bodySmall)
                    }
                    AndroidView(
                        factory = {
                            (demo.parent as? android.view.ViewGroup)?.removeView(demo)
                            demo
                        },
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                }
            }
        }
    }

    /** All actions below are restricted to the simulator's network-blocked WebView. */
    private fun runFullCycle() {
        if (!loaded || running || cycleRunning) return
        cycleRunning = true
        covering = "stubborn"
        outcome = "success"
        cycleTrace = emptyList()
        cycleSummary = "Проверяю полный цикл: перекрытие → исправление → перерисовка → восстановление"
        diagnostics = ""
        message = "Полный тест не нажимает «Подтв.» и не отправляет заказ."

        fun alive() = cycleRunning && !isFinishing && !isDestroyed
        fun fail(problem: String) {
            if (!alive()) return
            cycleSummary = "Тест не пройден: $problem"
            cycleRunning = false
        }
        fun step(label: String, state: String) {
            cycleTrace = cycleTrace + "$label: $state"
        }
        fun inspect(label: String, expected: String, next: () -> Unit) {
            executeScript("page-inspector.js") { data ->
                if (!alive()) return@executeScript
                val observed = data?.optString("confirmState", "no_response") ?: "no_response"
                step(label, observed)
                if (observed != expected) {
                    fail("на этапе «$label» ожидалось $expected, получено $observed")
                } else {
                    next()
                }
            }
        }
        fun repair(next: () -> Unit) {
            executeScript("reveal-confirmation.js") { data ->
                if (!alive()) return@executeScript
                val status = data?.optString("status", "no_response") ?: "no_response"
                if (status != "dom_reordered" && status != "shifted_and_dom_reordered") {
                    fail("резервное исправление не подтвердилось ($status)")
                } else {
                    cycleHandler.postDelayed({
                        if (alive()) next()
                    }, 350)
                }
            }
        }
        demo.evaluateJavascript(
            "window.autoordersSimulator.reset(\"success\",\"stubborn\",2004,2004)"
        ) reset@{ resetResult ->
            if (!alive()) return@reset
            if (resetResult != "true") {
                fail("не удалось сбросить тестовую страницу")
                return@reset
            }
            demo.evaluateJavascript("window.autoordersSimulator.showConfirmation()") opened@{ opened ->
                if (!alive()) return@opened
                if (opened != "true") {
                    fail("не удалось открыть тестовое окно")
                    return@opened
                }
                inspect("1. Первое перекрытие", "covered") {
                    repair {
                        inspect("2. Первое исправление", "visible") {
                            demo.evaluateJavascript(
                                "window.autoordersSimulator.rerenderConfirmation()"
                            ) rerender@{ result ->
                                if (!alive()) return@rerender
                                if (result != "true") {
                                    fail("перерисовка не выполнена")
                                    return@rerender
                                }
                                cycleHandler.postDelayed({
                                    if (alive()) inspect("3. После перерисовки", "covered") {
                                        repair {
                                            inspect("4. Повторное исправление", "visible") {
                                                cycleSummary = "Полный тест пройден: 4 из 4 проверок. " +
                                                    "Заказов не было."
                                                cycleRunning = false
                                            }
                                        }
                                    }
                                }, 350)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun resetPage() {
        if (!loaded || running) return
        demo.evaluateJavascript(
            "window.autoordersSimulator.reset(" +
                JSONObject.quote(outcome) + "," + JSONObject.quote(covering) + ",2004,2004)"
        ) {
            message = "Экран сброшен. Можно повторять тест без нового кода."
            diagnostics = ""
        }
    }

    private fun openTestDialog() {
        if (!loaded || running) return
        demo.evaluateJavascript("window.autoordersSimulator.showConfirmation()") { result ->
            if (result == "true") {
                message = "Тестовое окно открыто. Проверь кнопку «Подтв.» или нажми «Проверить»."
                diagnostics = ""
            } else {
                message = "Не удалось открыть тестовое окно. Нажми «Сброс» и попробуй снова."
            }
        }
    }

    /** Recreates only the local mock dialog, just as a SPA might do after a re-render. */
    private fun rerenderTestDialog() {
        if (!loaded || running) return
        demo.evaluateJavascript("window.autoordersSimulator.rerenderConfirmation()") { result ->
            if (result == "true") {
                message = "Тестовое окно пересоздано. Снова проверяю перекрытие; " +
                    "предыдущее исправление могло исчезнуть."
                diagnostics = ""
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (!isFinishing && !isDestroyed) inspectPage()
                }, 500)
            } else {
                message = "Сначала нажми «Открыть тестовое окно». " +
                    "На других экранах перерисовка не выполняется."
            }
        }
    }

    private fun executeScript(filename: String, done: (JSONObject?) -> Unit) {
        val source = assets.open(filename).bufferedReader().use { it.readText() }
        // Do not inject a global const: subsequent evaluateJavascript calls
        // run in the same WebView JS context and would return JSON null.
        val isolated = "(function() { const __SIMULATOR__ = true; return (" +
            source + "\n); })()"
        demo.evaluateJavascript(isolated) { raw ->
            val data = try {
                JSONObject(JSONTokener(raw).nextValue() as String)
            } catch (_: Exception) { null }
            done(data)
        }
    }

    private fun inspectPage() {
        executeScript("page-inspector.js") { data ->
            diagnostics = if (data == null) {
                "Проверка не выполнена: JavaScript не вернул результат. " +
                    "Сбрось симулятор и попробуй снова."
            } else if (data.optString("confirmState") == "not_found") {
                "Окно подтверждения ещё не открыто. " +
                    "Нажми «Открыть тестовое окно», затем «Проверить»."
            } else {
                "Экран: ${data.optString("pageStage", "?")}. " +
                    "Кнопка: ${data.optString("confirmState", "?")}. " +
                    "Перекрытие: ${data.optString("blockerType", "?")}."
            }
        }
    }

    private fun revealPage() {
        executeScript("reveal-confirmation.js") { data ->
            if (data == null) {
                message = "Исправление не проверено: JavaScript не вернул результат. " +
                    "Никаких заказов не отправлено."
            } else if (data.optString("status") == "not_found") {
                message = "Сначала нажми «Открыть тестовое окно», чтобы появилась кнопка «Подтв.»."
            } else {
                val status = data.optString("status", "не определён")
                message = when (status) {
                    "dom_reordered", "shifted_and_dom_reordered" ->
                        "Резервное исправление: окно перемещено после затемнения. " +
                            "Сейчас проверю, осталась ли кнопка доступной."
                    "dialog_raised", "shifted_and_dialog_raised" ->
                        "Основное исправление: изменён z-index окна. " +
                            "Сейчас проверю доступность кнопки."
                    "overlay_fixed", "shifted_and_overlay_fixed" ->
                        "Исправление: понижен z-index затемнения. " +
                            "Сейчас проверю доступность кнопки."
                    "overlay_still_blocked" ->
                        "Исправление не помогло: изменения отменены. Кнопка пока перекрыта."
                    else -> "Результат исправления: $status. Кнопку проверю повторно."
                }
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (!isFinishing && !isDestroyed) inspectPage()
                }, 1000)
            }
        }
    }

    override fun onDestroy() {
        cycleRunning = false
        cycleHandler.removeCallbacksAndMessages(null)
        if (::runner.isInitialized && runner.isActive()) runner.stop()
        if (::demo.isInitialized) demo.destroy()
        super.onDestroy()
    }

    companion object {
        private const val SIMULATOR_URL = "https://appassets.androidplatform.net/simulator/"
    }
}
