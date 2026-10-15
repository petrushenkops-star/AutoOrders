package com.example.autoorders

import android.Manifest
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.ActivityNotFoundException
import android.widget.Toast
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import org.json.JSONObject
import org.json.JSONTokener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private lateinit var siteView: WebView
    private lateinit var siteRunner: SiteRunner
    private lateinit var store: LocalStore
    private lateinit var alarms: AlarmScheduler
    private var logUpdate by mutableStateOf(0)
    private var runnerStatus by mutableStateOf("Автоматизация не запущена")
    private var siteError by mutableStateOf<String?>(null)
    private var siteDiagnostics by mutableStateOf("Диагностика ещё не выполнялась")
    private var showInspectionDialog by mutableStateOf(false)
    private var siteWindowTestActive by mutableStateOf(false)
    private val siteWindowTestHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var runnerActive by mutableStateOf(false)
    private var notificationOrderId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationOrderId = intent.getStringExtra("order_id")
        store = LocalStore(this)
        store.recoverInterruptedOrders()
        alarms = AlarmScheduler(this)
        ensureChannel(this)
        siteView = WebView(this).apply {
            settings.javaScriptEnabled = true // Required for the site's SPA and our DOM driver.
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val u = request?.url ?: return true
                    val host = u.host.orEmpty()
                    val permitted = u.scheme == "https" &&
                        (host == "lxcompintb.com" || host.endsWith(".lxcompintb.com"))
                    if (!permitted && request?.isForMainFrame == true) {
                        siteError = "Переход за пределы lxcompintb.com не выполняется во встроенном браузере."
                    }
                    return !permitted
                }
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        siteError = "Ошибка загрузки: ${error?.description ?: "нет описания"} (код ${error?.errorCode ?: "?"})"
                    }
                }
            }
            loadUrl("https://lxcompintb.com/ru")
        }
        siteRunner = SiteRunner(siteView, { runnerStatus = it }, { outcome, detail ->
            activeOrderId?.let { store.updateOrder(it, outcome, detail) }
            activeOrderId = null
            runnerActive = false
            logUpdate++
        })
        setContent { MaterialTheme { AutoOrdersApp() } }
    }

    /**
     * Read-only DOM inspection: no clicks, code values, cookies, or account data are collected.
     * Results are shown locally on the device and are never uploaded.
     */
    private fun inspectSite(afterFailedFix: Boolean = false) {
        val address = siteView.url.orEmpty()
        val host = Uri.parse(address).host.orEmpty()
        if (!(host == "lxcompintb.com" || host.endsWith(".lxcompintb.com")) || !address.startsWith("https://")) {
            siteDiagnostics = "Сначала открой сайт lxcompintb.com во встроенном браузере."
            return
        }
        siteDiagnostics = "Проверка элементов страницы…"
        val js = assets.open("page-inspector.js").bufferedReader().use { it.readText() }
        siteView.evaluateJavascript(js) { raw ->
            siteDiagnostics = try {
                val decoded = JSONTokener(raw).nextValue() as String
                val result = JSONObject(decoded)
                if (result.optString("status") != "ok") {
                    "Не удалось проверить страницу. Перейди в раздел «Быстрое копирование» и попробуй снова."
                } else {
                    buildString {
                        if (afterFailedFix) {
                            append("Окно всё ещё перекрыто. Изменения отменены. ")
                            append("Нажатий не выполнялось.\n\n")
                        }
                        append("Распознанный экран: ")
                        append(result.optString("pageStage", "не определён"))
                        append("\nКнопок «Быстрое копирование»: ")
                        append(result.optInt("copyButtons", 0))
                        append("\nПоле цифрового кода: ")
                        append(if (result.optBoolean("codeInput")) "найдено" else "не найдено")
                        append("\nКнопок «Отправлять»: ")
                        append(result.optInt("sendButtons", 0))
                        append("\nНадпись «Сумма заказа»: ")
                        append(if (result.optBoolean("amountLabel")) "есть" else "нет")
                        append("\nКнопок «Подтв.»: ")
                        append(result.optInt("confirmButtons", 0))
                        append("\nПоложение кнопки: ")
                        append(when (result.optString("confirmState")) {
                            "visible" -> "на экране, доступна"
                            "offscreen" -> "за границами экрана"
                            "covered" -> "на экране, но перекрыта другим элементом"
                            "disabled" -> "отключена"
                            "not_found" -> "не найдена"
                            else -> "не удалось определить"
                        })
                        if (result.optString("confirmState") != "not_found") {
                            append("\nВысота области сайта: ")
                            append(result.optInt("viewportHeight"))
                            append(" px")
                            append("\nКнопка: верх ")
                            append(result.optInt("confirmTop"))
                            append(" px, низ ")
                            append(result.optInt("confirmBottom"))
                            append(" px")
                        }
                        if (result.optString("confirmState") == "covered") {
                            append("\nЧто перекрывает: ")
                            append(result.optString("blockerType", "не определено"))
                            append("\nСлой перекрытия: z=")
                            append(result.optString("blockerZ", "?"))
                            append(", position=")
                            append(result.optString("blockerPosition", "?"))
                            append("\nОтносительно окна: ")
                            append(result.optString("blockerSide", "не определено"))
                            append("\nСлой самого окна: z=")
                            append(result.optString("popupZ", "?"))
                            append(", position=")
                            append(result.optString("popupPosition", "?"))
                            append("\nЦепочка окна: ")
                            append(result.optString("popupChain", "не определено"))
                            append("\nЦепочка перекрытия: ")
                            append(result.optString("blockerChain", "не определено"))
                            append("\nОтношение слоёв: ")
                            append(when (result.optString("layerRelation")) {
                                "siblings" -> "соседние элементы"
                                "dialog_inside_blocker" -> "окно внутри затемнения"
                                "blocker_inside_dialog" -> "затемнение внутри окна"
                                "separate_parents" -> "разные родительские контейнеры"
                                else -> "не удалось определить"
                            })
                            append("\nОбщий контейнер: ")
                            append(result.optString("commonParent", "не определён"))
                            append("\nРодитель окна: ")
                            append(result.optString("popupParentType", "?"))
                            append(", z=")
                            append(result.optString("popupParentZ", "?"))
                            append(", контекст: ")
                            append(result.optString("popupParentContext", "?"))
                            append("\nРодитель затемнения: ")
                            append(result.optString("blockerParentType", "?"))
                            append(", z=")
                            append(result.optString("blockerParentZ", "?"))
                            append(", контекст: ")
                            append(result.optString("blockerParentContext", "?"))
                            append("\nГлубина: окно ")
                            append(result.optInt("popupAncestryDepth"))
                            append(", затемнение ")
                            append(result.optInt("blockerAncestryDepth"))
                            append("\nСлои в точке кнопки: ")
                            append(result.optString("hitStack", "недоступно"))
                        }
                        append("\nЭлементов окна подтверждения: ")
                        append(result.optInt("dialogElements", 0))
                        append("\nСообщение об успехе: ")
                        append(if (result.optBoolean("successLabel")) "есть" else "нет")
                        append("\nПроверка ничего не нажимает и не отправляет.")
                    }
                }
            } catch (_: Exception) {
                "Не удалось получить результат проверки. Обнови страницу и повтори."
            }
            showInspectionDialog = true
        }
    }


    /**
     * Manual viewport adjustment only. Never presses or submits a financial order.
     * Uses the existing WebView session; no account details or codes leave the device.
     */
    private fun revealConfirmationDialog() {
        val address = siteView.url.orEmpty()
        val host = Uri.parse(address).host.orEmpty()
        if (!address.startsWith("https://") ||
            !(host == "lxcompintb.com" || host.endsWith(".lxcompintb.com"))) {
            siteDiagnostics = "Сначала открой lxcompintb.com во встроенном браузере."
            showInspectionDialog = true
            return
        }
        siteDiagnostics = "Попытка показать окно подтверждения…"
        val js = assets.open("reveal-confirmation.js").bufferedReader().use { it.readText() }
        siteView.evaluateJavascript(js) { raw ->
            val status = try {
                val decoded = JSONTokener(raw).nextValue() as String
                JSONObject(decoded).optString("status")
            } catch (_: Exception) { "failed" }
            val message = when (status) {
                    "shifted" -> "Окно перемещено в видимую область. " +
                        "Закрой этот отчёт и проверь, появилась ли кнопка «Подтв.». " +
                        "Никаких нажатий и отправок не выполнялось."
                    "already_visible" -> "Кнопка уже находится в видимой области. " +
                        "Закрой отчёт, чтобы проверить её."
                    "layer_fixed" -> "Окно поднято над затемнением. " +
                        "Закрой отчёт и проверь, появилась ли кнопка «Подтв.». " +
                        "Никаких нажатий не выполнялось."
                    "shifted_and_layer_fixed" -> "Окно перемещено и поднято над затемнением. " +
                        "Закрой отчёт и проверь кнопку «Подтв.»."
                    "blocked_inside_dialog" -> "Кнопку перекрывает элемент внутри окна подтверждения. " +
                        "Автоматически менять его нельзя, заказ не отправлен."
                    "dialog_raised" -> "Окно подтверждения поднято над затемняющим слоем. " +
                        "Закрой отчёт и проверь, видна ли кнопка «Подтв.». Нажатий не выполнялось."
                    "shifted_and_dialog_raised" -> "Окно перемещено и поднято над затемнением. " +
                        "Закрой отчёт и проверь кнопку «Подтв.». Нажатий не выполнялось."
                    "overlay_fixed" -> "Затемнение опущено ниже окна подтверждения. " +
                        "Закрой отчёт и проверь видимость кнопки «Подтв.». Нажатий не выполнялось."
                    "shifted_and_overlay_fixed" -> "Окно перемещено, затемнение опущено ниже него. " +
                        "Закрой отчёт и проверь кнопку «Подтв.»."
                    "dom_reordered" -> "Окно перемещено поверх затемнения без нажатий. " +
                        "Приложение проверит доступность кнопки ещё раз."
                    "shifted_and_dom_reordered" -> "Окно перемещено и выведено поверх затемнения. " +
                        "Приложение проверит доступность кнопки ещё раз."
                    "overlay_still_blocked" -> "Затемнение всё ещё перекрывает окно. " +
                        "Изменения отменены. Заказ не отправлен."
                    "unsafe_layer" -> "Обнаружен другой тип перекрытия или несовпадающие слои. " +
                        "Ради безопасности изменения не выполнены."
                    "unknown_blocker" -> "Не удалось однозначно определить перекрывающий слой. " +
                        "Никаких изменений и нажатий не выполнено."
                    "disabled" -> "Кнопка подтверждения отключена на сайте. " +
                        "Никаких действий не выполнено."
                    "layer_still_blocked" -> "Окно всё ещё перекрыто. " +
                        "Изменения отменены, заказ не отправлен."
                    "not_found" -> "Кнопка «Подтв.» не найдена однозначно. " +
                        "Открой окно подтверждения на сайте и повтори."
                    "no_dialog" -> "Не обнаружено окно подтверждения, которое можно безопасно переместить."
                    "failed" -> "Не удалось вывести кнопку в видимую область. " +
                        "Не подтверждай и не повторяй заказ, пока не проверен его статус."
                    else -> "Нельзя изменить положение окна на этой странице."
                }
            val apparentlyExposed = status in setOf(
                "already_visible", "shifted", "dialog_raised", "overlay_fixed",
                "shifted_and_dialog_raised", "shifted_and_overlay_fixed",
                "dom_reordered", "shifted_and_dom_reordered"
            )
            if (status == "overlay_still_blocked") {
                // Only read DOM metadata after reverting failed temporary CSS edits.
                inspectSite(afterFailedFix = true)
            } else if (!apparentlyExposed) {
                siteDiagnostics = message
                showInspectionDialog = true
            } else {
                siteDiagnostics = "$message\nПроверяю, не вернулось ли перекрытие…"
                showInspectionDialog = true
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (!isFinishing && !isDestroyed) {
                        val inspector = assets.open("page-inspector.js")
                            .bufferedReader().use { it.readText() }
                        siteView.evaluateJavascript(inspector) { checked ->
                            val state = try {
                                val rawJson = JSONTokener(checked).nextValue() as String
                                JSONObject(rawJson).optString("confirmState")
                            } catch (_: Exception) { "unknown" }
                            siteDiagnostics = message + "\n\nПовторная проверка через 1 секунду: " +
                                when (state) {
                                    "visible" -> "Кнопка доступна на странице."
                                    "covered" -> "Затемнение по-прежнему перекрывает кнопку. " +
                                        "Не нажимайте её, требуется дополнительная диагностика."
                                    "offscreen" -> "Кнопка опять оказалась вне экрана."
                                    else -> "Доступность кнопки не подтверждена."
                                } + "\nНажатий и заказов не выполнялось."
                            showInspectionDialog = true
                        }
                    }
                }, 1000)
            }
        }
    }


    /**
     * Geometry-only diagnostic on the real site, with two independent snapshots.
     * No DOM changes, attempts to click, form submission, or order scheduling.
     */
    private fun testSiteWindow() {
        if (runnerActive || siteWindowTestActive) return
        val initialUrl=siteView.url.orEmpty()
        val uri=Uri.parse(initialUrl)
        val host=uri.host.orEmpty()
        if (uri.scheme!="https" ||
            !(host=="lxcompintb.com"||host.endsWith(".lxcompintb.com"))) {
            siteDiagnostics="Открой lxcompintb.com во встроенном браузере."
            showInspectionDialog=true
            return
        }
        siteWindowTestActive=true
        siteDiagnostics="Считываю геометрию окна без изменения сайта…"
        showInspectionDialog=true
        fun finish(report:String) {
            if (!siteWindowTestActive||isFinishing||isDestroyed) return
            siteDiagnostics=report+"\n\nТест только считывает геометрию. "+
                "Кнопка «Подтв.» не нажималась, заказ не отправлялся."
            siteWindowTestActive=false
            showInspectionDialog=true
        }
        fun valid():Boolean {
            if (!siteWindowTestActive||isFinishing||isDestroyed) return false
            if (siteView.url!=initialUrl) {
                finish("Адрес страницы изменился. Проверка остановлена.")
                return false
            }
            return true
        }
        fun readSnapshot(next:(JSONObject?)->Unit) {
            if (!valid()) return
            val code=assets.open("window-geometry-probe.js").bufferedReader()
                .use { it.readText() }
            siteView.evaluateJavascript(code) { raw ->
                if (!valid()) return@evaluateJavascript
                val result=try {
                    JSONObject(JSONTokener(raw).nextValue() as String)
                } catch (_:Exception) { null }
                next(result)
            }
        }
        fun rectSummary(rect:JSONObject?):String {
            if(rect==null)return "нет"
            return "x="+rect.optInt("x")+", y="+rect.optInt("y")+
                ", ширина="+rect.optInt("w")+", высота="+rect.optInt("h")
        }
        fun boxSummary(b:JSONObject?):String {
            if (b==null) return "нет"
            return b.optString("kind")+": "+rectSummary(b.optJSONObject("rect"))+
                "; CSS height="+b.optString("height")+
                "; min-height="+b.optString("minHeight")+
                "; scrollHeight="+b.optInt("scrollHeight")+
                "; offsetHeight="+b.optInt("offsetHeight")+
                "; overflowY="+b.optString("overflowY")+
                "; position="+b.optString("position")
        }
        fun format(snapshot:JSONObject?):String {
            if(snapshot==null)return "Нет ответа JavaScript."
            if(snapshot.optString("status")!="ok")
                return "Состояние: "+snapshot.optString("status","unknown")
            val v=snapshot.optJSONObject("viewport")
            val dialog=snapshot.optJSONObject("dialogStyle")
            val blocker=snapshot.optJSONObject("blockerStyle")
            val delta=snapshot.optJSONObject("buttonCenterDelta")
            return "Состояние кнопки: "+snapshot.optString("confirmState")+
                "\nКоординаты кнопки: "+rectSummary(snapshot.optJSONObject("buttonRect"))+
                "\nКоординаты окна: "+rectSummary(snapshot.optJSONObject("dialogRect"))+
                "\nКоординаты затемнения: "+rectSummary(snapshot.optJSONObject("blockerRect"))+
                "\nКнопка внутри границ окна: "+snapshot.optBoolean("buttonWithinDialogRect")+
                "\nКнопка внутри окна в DOM: "+snapshot.optBoolean("buttonInsideDialogDOM")+
                "\nСмещение центров: x="+(delta?.optInt("x")?:0)+
                ", y="+(delta?.optInt("y")?:0)+
                "\nВ точке кнопки: "+snapshot.optString("buttonStack")+
                "\nВ центре окна: "+snapshot.optString("dialogCenterStack")+
                "\nz-index: окно "+(dialog?.optString("z")?:"?")+
                ", затемнение "+(blocker?.optString("z")?:"?")+
                "\nTransform окна: "+(dialog?.optBoolean("transform")?:false)+
                "; переход CSS: "+(dialog?.optBoolean("transition")?:false)+
                "\nОбласть сайта: "+(v?.optInt("innerW")?:0)+" x "+
                (v?.optInt("innerH")?:0)+
                "; visualViewport: "+(v?.optInt("visualW")?:0)+" x "+
                (v?.optInt("visualH")?:0)+
                "\nСмещение visualViewport: "+
                (v?.optInt("offsetX")?:0)+", "+(v?.optInt("offsetY")?:0)+
                "; масштаб: "+(v?.optDouble("scale")?:1.0)+
                "\n\nРазмеры диалога и родительских контейнеров:\n"+
                (snapshot.optJSONArray("ancestorBoxes")?.let { boxes ->
                    (0 until minOf(boxes.length(),4)).joinToString("\n") { i ->
                        (i+1).toString()+". "+boxSummary(boxes.optJSONObject(i))
                    }
                } ?: "нет")+
                "\n\nВнутренние элементы:\n"+
                (snapshot.optJSONArray("childBoxes")?.let { boxes ->
                    (0 until minOf(boxes.length(),5)).joinToString("\n") { i ->
                        (i+1).toString()+". "+boxSummary(boxes.optJSONObject(i))
                    }
                } ?: "нет")+
                "\nКонтейнер позиционирования кнопки: "+
                boxSummary(snapshot.optJSONObject("offsetBox"))
        }
        readSnapshot first@{ a ->
            if (!valid()) return@first
            siteDiagnostics="Первый замер:\n"+format(a)+"\n\nВторой замер через 1 секунду…"
            siteWindowTestHandler.postDelayed(second@{
                if (!valid()) return@second
                readSnapshot last@{ b ->
                    if (!valid()) return@last
                    finish("Первый замер:\n"+format(a)+
                        "\n\nЧерез 1 секунду:\n"+format(b))
                }
            },1000)
        }
    }


    /**
     * Only a reversible visual trial. The native modal blocks interaction with
     * the WebView. No confirmation click, code entry or order submission.
     */
    /**
     * Temporary native-modal visual test. The WebView is not touchable while
     * the modal is open; no code submission, clicks or order actions.
     * longCheck=true checks for ten seconds to catch spontaneous DOM re-renders.
     */
    private fun trialFixDialogHeight(longCheck: Boolean = false) {
        if (runnerActive || siteWindowTestActive) return
        val originalUrl=siteView.url.orEmpty()
        val uri=Uri.parse(originalUrl)
        val host=uri.host.orEmpty()
        if (uri.scheme!="https" ||
            !(host=="lxcompintb.com"||host.endsWith(".lxcompintb.com"))) {
            siteDiagnostics="Открой окно подтверждения на lxcompintb.com."
            showInspectionDialog=true
            return
        }
        siteWindowTestActive=true
        showInspectionDialog=true
        siteDiagnostics=if (longCheck)
            "Проверяю устойчивость исправления в течение 10 секунд. Заказ не отправляется."
        else "Проверяю высоту диалога. Изменения будут отменены через 2 секунды."
        val times=if (longCheck) listOf(500L,2000L,5000L,10000L)
            else listOf(500L,2000L)
        val trace=mutableListOf<String>()

        fun finish(report:String) {
            if (!siteWindowTestActive||isFinishing||isDestroyed) return
            siteDiagnostics=report+"\n\nТест не нажимает «Подтв.» и не отправляет заказ."
            siteWindowTestActive=false
            siteWindowTestHandler.removeCallbacksAndMessages(null)
        }
        fun execute(phase:String,callback:(JSONObject?)->Unit) {
            if (!siteWindowTestActive||isFinishing||isDestroyed) return
            val script=assets.open("dialog-height-trial.js").bufferedReader()
                .use { it.readText() }.replace("__PHASE__",phase)
            siteView.evaluateJavascript(script) { raw ->
                if (!siteWindowTestActive||isFinishing||isDestroyed) return@evaluateJavascript
                val obj=try {
                    JSONObject(JSONTokener(raw).nextValue() as String)
                } catch (_:Exception) { null }
                callback(obj)
            }
        }
        fun rollback(report:String) {
            // Always revert both dimensions and stacking after a check.
            execute("restore") { response ->
                val status=response?.optString("status")?:"no_response"
                finish(report+"\nВосстановление исходных стилей: $status.")
            }
        }
        execute("start") start@{ data ->
            val status=data?.optString("status")?:"no_response"
            if (status!="started") {
                finish("Изменение не выполнялось: $status.")
                return@start
            }
            val height=data?.optInt("targetHeight") ?: 0
            val immediate=data?.optString("state","unknown") ?: "unknown"
            val header="До изменения: covered.\nПробная высота: $height px."+
                "\nСразу: $immediate."
            trace.add("Сразу: $immediate")
            if (immediate!="visible") {
                rollback(header+"\nИзменение не сделало кнопку доступной.")
                return@start
            }
            // Watchdog: a stalled callback must not leave the visual patch in place.
            siteWindowTestHandler.postDelayed({
                if (siteWindowTestActive) rollback(
                    header+"\nПроверка прервана по тайм-ауту. Исходные стили восстанавливаются.")
            }, if (longCheck) 13000L else 5000L)
            fun sample(index:Int) {
                if (!siteWindowTestActive) return
                val previous=if (index==0) 0L else times[index-1]
                siteWindowTestHandler.postDelayed({
                    if (!siteWindowTestActive) return@postDelayed
                    if (siteView.url != originalUrl) {
                        rollback(header+"\nАдрес страницы изменился. Проверка остановлена.")
                        return@postDelayed
                    }
                    execute("check") { response ->
                        val status=response?.optString("status")?:"no_response"
                        val state=if (status=="checked")
                            response?.optString("state","unknown")?:"unknown"
                        else status
                        val label=if (times[index]<1000L)
                            "Через 0,5 секунды"
                        else "Через "+(times[index]/1000L)+" секунд"
                        trace.add("$label: $state")
                        siteDiagnostics=header+"\n"+trace.drop(1).joinToString("\n")+
                            "\nИзменения ещё временные. Жду следующей проверки…"
                        if (state!="visible") {
                            rollback(header+"\n"+trace.drop(1).joinToString("\n")+
                                "\nКнопка перестала быть доступной; проверка остановлена.")
                        } else if (index==times.lastIndex) {
                            rollback(header+"\n"+trace.drop(1).joinToString("\n")+
                                "\nДоступность кнопки подтверждена на всём интервале.")
                        } else sample(index+1)
                    }
                }, times[index]-previous)
            }
            sample(0)
        }
    }

    private var activeOrderId: String? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationOrderId = intent.getStringExtra("order_id")
    }

    private fun pickTime(day: DayOfWeek, current: Slot?, onPicked: (Slot) -> Unit) {
        val time = current?.time ?: LocalTime.now()
        TimePickerDialog(this, { _, hour, minute ->
            onPicked(if (current == null) Slot(day = day, time = LocalTime.of(hour, minute))
                else current.copy(time = LocalTime.of(hour, minute)))
        }, time.hour, time.minute, true).show()
    }

    @Composable
    private fun AutoOrdersApp() {
        var tab by remember { mutableIntStateOf(if (notificationOrderId != null) 1 else 0) }
        var slots by remember { mutableStateOf(store.slots()) }
        var orders by remember { mutableStateOf(store.orders()) }
        var selectedOrderId by remember { mutableStateOf<String?>(notificationOrderId) }
        var code by remember { mutableStateOf("") }
        var live by remember { mutableStateOf(false) }
        var fullScreenSite by remember { mutableStateOf(false) }
        BackHandler(enabled = fullScreenSite) { fullScreenSite = false }
        var resumeCounter by remember { mutableIntStateOf(0) }
        val launchNotificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        LaunchedEffect(notificationOrderId) {
            if (notificationOrderId != null) {
                selectedOrderId = notificationOrderId
                tab = 1
            }
        }
        val lifecycle = LocalLifecycleOwner.current
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    orders = store.orders()
                    slots = store.slots()
                    resumeCounter++
                }
            }
            lifecycle.lifecycle.addObserver(observer)
            onDispose { lifecycle.lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(logUpdate) { orders = store.orders() }
        val saveSlots: (List<Slot>) -> Unit = { new ->
            store.putSlots(new)
            alarms.refresh(slots, new)
            slots = store.slots()
        }
        val pending = orders.filter { it.status == OrderStatus.WAITING_CODE }
        val selected = pending.firstOrNull { it.id == selectedOrderId } ?: pending.firstOrNull()
        val exactAvailable = remember(slots, resumeCounter) {
            Build.VERSION.SDK_INT < 31 || (getSystemService(ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        }
        if (showInspectionDialog) {
            AlertDialog(
                onDismissRequest = { if (!siteWindowTestActive) showInspectionDialog = false },
                title = { Text("Диагностика окна") },
                text = {
                    Column(
                        modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())
                    ) { Text(siteDiagnostics) }
                },
                confirmButton = {
                    TextButton(onClick = { showInspectionDialog = false },
                        enabled = !siteWindowTestActive) { Text("Закрыть") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        val manager = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        manager.setPrimaryClip(android.content.ClipData.newPlainText(
                            "Диагностика окна", siteDiagnostics))
                        Toast.makeText(this@MainActivity, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
                    }, enabled = !siteWindowTestActive) { Text("Копировать отчёт") }
                }
            )
        }
        if (fullScreenSite) {
            Column(
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            ) {
                Surface(tonalElevation = 3.dp) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(onClick = { fullScreenSite = false }) {
                                Text("← Назад")
                            }
                            TextButton(
                                onClick = { testSiteWindow() },
                                enabled = !runnerActive && !siteWindowTestActive
                            ) { Text("Тест окна") }
                            TextButton(
                                onClick = { inspectSite() },
                                enabled = !runnerActive && !siteWindowTestActive
                            ) { Text("Проверить") }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            TextButton(
                                onClick = { trialFixDialogHeight() },
                                enabled = !runnerActive && !siteWindowTestActive
                            ) { Text("Пробное исправление") }
                            TextButton(
                                onClick = { trialFixDialogHeight(longCheck = true) },
                                enabled = !runnerActive && !siteWindowTestActive
                            ) { Text("Тест 10 секунд") }
                        }
                    }
                }
                AndroidView(
                    factory = {
                        (siteView.parent as? android.view.ViewGroup)?.removeView(siteView)
                        siteView
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            }
        } else {
        Scaffold(
            topBar = {
                Surface(modifier = Modifier.statusBarsPadding(), tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Автозаказы", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Версия ${packageManager.getPackageInfo(packageName, 0).versionName} • lxcompintb.com",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            bottomBar = {
                NavigationBar {
                    listOf("Расписание", "Заказы", "Сайт").forEachIndexed { index, name ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index },
                            icon = { Text(when (index) { 0 -> "▦"; 1 -> "◷"; else -> "◎" }) }, label = { Text(name) })
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (tab) {
                    0 -> {
                        Text("Недельное расписание", style = MaterialTheme.typography.titleMedium)
                        Text("Добавь любое количество заказов на каждый день. Время местное, по настройкам телефона.", style = MaterialTheme.typography.bodySmall)
                        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            OutlinedButton(onClick = { launchNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                                Text("Разрешить уведомления")
                            }
                        }
                        if (!exactAvailable) {
                            Text("Точное время срабатывания не разрешено. Уведомления могут задерживаться.", color = MaterialTheme.colorScheme.error)
                            OutlinedButton(onClick = {
                                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Uri.parse("package:$packageName")))
                            }) { Text("Разрешить точное расписание") }
                        }
                        DayOfWeek.entries.forEach { day ->
                            val daySlots = slots.filter { it.day == day }.sortedBy { it.time }
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(dayLabel(day), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                        Text("${daySlots.count { it.enabled }} заказ(ов)", style = MaterialTheme.typography.labelSmall)
                                    }
                                    daySlots.forEach { slot ->
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            TextButton(onClick = { pickTime(day, slot) { edited ->
                                                saveSlots(slots.map { if (it.id == slot.id) edited else it })
                                            } }) { Text(slot.time.format(DateTimeFormatter.ofPattern("HH:mm"))) }
                                            Spacer(Modifier.weight(1f))
                                            Switch(checked = slot.enabled, onCheckedChange = { newEnabled ->
                                                saveSlots(slots.map { if (it.id == slot.id) it.copy(enabled = newEnabled) else it })
                                            })
                                            TextButton(onClick = { saveSlots(slots.filterNot { it.id == slot.id }) }) { Text("Удалить") }
                                        }
                                    }
                                    OutlinedButton(onClick = { pickTime(day, null) { added -> saveSlots(slots + added) } }) {
                                        Text("+ Добавить время")
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        Text("Заказы", style = MaterialTheme.typography.titleMedium)
                        Text("В назначенное время появится уведомление. Для каждого заказа вводи новый цифровой код.", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = {
                            val entry = OrderEntry(slotId = null)
                            store.addOrder(entry)
                            orders = store.orders()
                            selectedOrderId = entry.id
                        }) { Text("+ Создать тестовый заказ") }
                        if (selected != null) {
                            ElevatedCard {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Ожидает цифровой код", fontWeight = FontWeight.SemiBold)
                                    if (pending.size > 1) Text("Заказов в ожидании: ${pending.size}. Обрабатывай их по одному.")
                                    Text(timeText(selected.createdAtMillis), style = MaterialTheme.typography.labelMedium)
                                    OutlinedTextField(
                                        value = code,
                                        onValueChange = { v -> if (v.length <= 20 && v.all(Char::isDigit)) code = v },
                                        label = { Text("Новый цифровой код") },
                                        visualTransformation = PasswordVisualTransformation(),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Тестовый режим", Modifier.weight(1f))
                                        Switch(checked = !live, enabled = !runnerActive, onCheckedChange = { live = !it })
                                    }
                                    if (live) Text("ЭКСПЕРИМЕНТАЛЬНО: при запуске будет подтверждён реальный финансовый заказ на сайте. Механизм ещё не проверен в личном кабинете.", color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall)
                                    Button(onClick = {
                                        val id = selected.id
                                        val submittedCode = code
                                        code = ""
                                        if (!live) {
                                            store.updateOrder(id, OrderStatus.TEST_SUCCESS, "Тестовый сценарий завершён без обращения к сайту")
                                            orders = store.orders()
                                            selectedOrderId = null
                                        } else {
                                            activeOrderId = id
                                            store.updateOrder(id, OrderStatus.IN_PROGRESS,
                                                "Выполнение на сайте начато. Не повторяйте заказ при неизвестном результате.")
                                            orders = store.orders()
                                            tab = 2
                                            runnerActive = true
                                            siteRunner.start(submittedCode)
                                        }
                                    }, enabled = code.isNotBlank() && !runnerActive && !siteWindowTestActive && (!live || siteError == null), modifier = Modifier.fillMaxWidth()) {
                                        Text(if (live) "Выполнить заказ на сайте" else "Проверить тестовый сценарий")
                                    }
                                    TextButton(onClick = {
                                        store.updateOrder(selected.id, OrderStatus.MISSED, "Пропущено вручную")
                                        orders = store.orders()
                                        selectedOrderId = null
                                        code = ""
                                    }, enabled = !runnerActive) { Text("Пропустить этот заказ") }
                                }
                            }
                        } else Text("Сейчас нет заказов, ожидающих кода.")
                        Text("История", style = MaterialTheme.typography.titleMedium)
                        orders.take(40).forEach { entry ->
                            HorizontalDivider()
                            Column {
                                Text("${timeText(entry.createdAtMillis)} — ${statusLabel(entry.status)}", fontWeight = FontWeight.Medium)
                                if (entry.details.isNotBlank()) Text(entry.details, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    2 -> {
                        Text("Сайт и автоматизация", style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick = {
                            startActivity(Intent(this@MainActivity, SimulatorActivity::class.java))
                        }, enabled = !runnerActive) {
                            Text("Офлайн-симулятор — без реальных заказов")
                        }
                        Text("Первый вход в личный кабинет потребуется выполнить вручную. Встроенный браузер не использует сессию Chrome.", style = MaterialTheme.typography.bodySmall)
                        if (runnerActive) {
                            Button(onClick = { siteRunner.stop() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                                Text("СТОП — прекратить действия")
                            }
                        }
                        Text(runnerStatus, style = MaterialTheme.typography.bodySmall)
                        if (siteError != null) {
                            Text(
                                siteError ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                "Проверь, открывается ли сайт в обычном браузере. " +
                                "Если он работает только там, автоматизация через WebView не сможет им управлять.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                siteError = null
                                siteView.reload()
                            }, enabled = !runnerActive) { Text("Обновить") }
                            OutlinedButton(onClick = {
                                siteError = null
                                siteView.loadUrl("https://lxcompintb.com/ru")
                            }, enabled = !runnerActive) { Text("Главная") }
                        }
                        OutlinedButton(onClick = {
                            val chromeIntent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://lxcompintb.com/ru")
                            ).apply {
                                setPackage("com.android.chrome")
                            }
                            try {
                                startActivity(chromeIntent)
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Google Chrome не установлен. Установите Chrome, чтобы открыть сайт.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }) { Text("Открыть сайт в Chrome") }
                        OutlinedButton(
                            onClick = { inspectSite() },
                            enabled = !runnerActive && !siteWindowTestActive
                        ) { Text("Проверить элементы страницы") }
                        OutlinedButton(onClick = { testSiteWindow() },
                            enabled = !runnerActive && !siteWindowTestActive) {
                            Text("Тест окна без заказа")
                        }
                        OutlinedButton(onClick = { trialFixDialogHeight() },
                            enabled = !runnerActive && !siteWindowTestActive) {
                            Text("Пробное исправление окна")
                        }
                        OutlinedButton(onClick = { trialFixDialogHeight(longCheck = true) },
                            enabled = !runnerActive && !siteWindowTestActive) {
                            Text("Проверка устойчивости (10 секунд)")
                        }
                        Text(siteDiagnostics, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(
                            onClick = { fullScreenSite = true },
                            enabled = !runnerActive
                        ) { Text("Развернуть сайт на весь экран") }
                        AndroidView(factory = { context ->
                            (siteView.parent as? android.view.ViewGroup)?.removeView(siteView)
                            siteView
                        }, modifier = Modifier.fillMaxWidth().height(570.dp))
                    }
                }
            }
        }
        }
    }

    private fun timeText(epoch: Long): String =
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epoch))

    private fun statusLabel(s: OrderStatus) = when (s) {
        OrderStatus.WAITING_CODE -> "Ожидание кода"
        OrderStatus.TEST_SUCCESS -> "Тест выполнен"
        OrderStatus.SUCCESS -> "Сайт сообщил об успехе"
        OrderStatus.ERROR -> "Ошибка выполнения"
        OrderStatus.MISSED -> "Пропущен"
        OrderStatus.IN_PROGRESS -> "Выполняется"
        OrderStatus.UNKNOWN -> "Результат неизвестен — проверьте на сайте"
    }

    override fun onDestroy() {
        siteWindowTestActive = false
        siteWindowTestHandler.removeCallbacksAndMessages(null)
        if (siteRunner.isActive()) siteRunner.stop()
        siteView.destroy()
        super.onDestroy()
    }
}
