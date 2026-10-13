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
                                    enabled = loaded && !running,
                                    onClick = { outcome = value; resetPage() }
                                ) { Text((if (outcome == value) "✓ " else "") + title) }
                            }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(enabled = loaded && !running,
                            onClick = { covering = "normal"; resetPage() }) {
                            Text((if (covering == "normal") "✓ " else "") + "Видно")
                        }
                        OutlinedButton(enabled = loaded && !running,
                            onClick = { covering = "blocked"; resetPage() }) {
                            Text((if (covering == "blocked") "✓ " else "") + "Перекрыто")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(enabled = loaded && !running, onClick = {
                            running = true
                            runner.start("123456")
                        }) { Text("Тест") }
                        OutlinedButton(enabled = running, onClick = { runner.stop() }) {
                            Text("СТОП")
                        }
                        OutlinedButton(enabled = loaded && !running,
                            onClick = { resetPage() }) { Text("Сброс") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(enabled = loaded && !running,
                            onClick = { inspectPage() }) { Text("Проверить") }
                        OutlinedButton(enabled = loaded && !running,
                            onClick = { revealPage() }) { Text("Показать окно") }
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

    private fun executeScript(filename: String, done: (JSONObject?) -> Unit) {
        val source = assets.open(filename).bufferedReader().use { it.readText() }
        // Do not inject a global const: subsequent evaluateJavascript calls
        // run in the same WebView JS context and would return JSON null.
        val isolated = "(function() { const __SIMULATOR__ = true; return " +
            source + "\n})()"
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
            } else {
                message = "Результат исправления: ${data.optString("status", "не определён")}."
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (!isFinishing && !isDestroyed) inspectPage()
                }, 1000)
            }
        }
    }

    override fun onDestroy() {
        if (::runner.isInitialized && runner.isActive()) runner.stop()
        if (::demo.isInitialized) demo.destroy()
        super.onDestroy()
    }

    companion object {
        private const val SIMULATOR_URL = "https://appassets.androidplatform.net/simulator/"
    }
}
