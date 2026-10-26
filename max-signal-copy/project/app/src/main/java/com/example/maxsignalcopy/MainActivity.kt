package com.example.maxsignalcopy

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.util.Rational
import android.app.TimePickerDialog
import android.content.Intent
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowInsets
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.webkit.WebSettings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.graphics.Typeface
import android.graphics.Color
import android.view.Gravity
import android.net.Uri
import android.os.SystemClock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class MainActivity : Activity() {
    private lateinit var browser: WebView
    private lateinit var automation: BrowserAutomation
    private lateinit var status: TextView
    private lateinit var settingsView: ScrollView
    private lateinit var siteView: LinearLayout
    private lateinit var history: TextView
    private lateinit var backgroundTrace: TextView
    private lateinit var manualCode: EditText
    private lateinit var manualDigits: EditText
    private lateinit var telegramBridge: TelegramBridge
    private lateinit var telegramState: TextView
    private lateinit var telegramApiId: EditText
    private lateinit var telegramApiHash: EditText
    private lateinit var telegramPhone: EditText
    private lateinit var telegramLoginCode: EditText
    private lateinit var telegram2fa: EditText
    private lateinit var telegramDate: EditText
    private lateinit var telegramStart: EditText
    private lateinit var telegramEnd: EditText
    private lateinit var telegramDigits: EditText
    private lateinit var scheduleState: TextView
    private lateinit var scheduleList: LinearLayout
    private lateinit var scheduleEnabled: CheckBox
    private val scheduleDayChecks = linkedMapOf<DayOfWeek, CheckBox>()
    private lateinit var scheduleStart: EditText
    private lateinit var scheduleEnd: EditText
    private var lastScheduledScan: Long = 0L
    private var lastScheduledWindowKey: String = ""
    private val scheduledTelegramCheck = object : Runnable {
        override fun run() {
            refreshScheduledSearch()
            checkHandler.postDelayed(this, 45_000L)
        }
    }
    private val checkHandler = Handler(Looper.getMainLooper())
    private var visibleSite = false
    private var fullscreenSite = false
    private lateinit var titleView: TextView
    private lateinit var tabsView: LinearLayout
    private lateinit var fullscreenToolbar: LinearLayout
    private lateinit var manualBar: LinearLayout
    private lateinit var websiteActionBar: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!TradeSession.isActive()) SignalMailbox.markInterrupted(this)
        // The new Telegram integration is read-only until explicitly tested.
        // Never enable the retired MAX integration or re-submit a saved code.
        val old = SignalMailbox.settings(this)
        if (old.armed || old.autoConfirm) SignalMailbox.update(this, old.copy(armed=false,autoConfirm=false))
        if (!TradeSession.isActive()) SignalMailbox.clearPending(this)
        // Discard credentials for the retired MAX bot; Telegram credentials are separate.
        getSharedPreferences("max_history_bot_credentials", MODE_PRIVATE).edit().clear().apply()
        runCatching {
            val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias("max_history_token_v1")) store.deleteEntry("max_history_token_v1")
        }
        makeUi()
        telegramBridge = TelegramBridge(applicationContext) { message -> telegramState.text = message }
        automation = TradeSession.bind(this, browser)
        TradeSession.attach(this)
        if (!TradeSession.isActive()) openSite()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 120)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun label(value: String, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = if (bold) 18f else 15f
        if (bold) setTypeface(null, Typeface.BOLD)
        setPadding(dp(8), dp(4), dp(8), dp(4))
    }

    private fun field(hint: String, value: String): EditText = EditText(this).apply {
        setSingleLine(true)
        this.hint = hint
        setText(value)
        textSize = 16f
        setPadding(dp(12), dp(4), dp(12), dp(4))
    }

    private fun addButton(parent: LinearLayout, caption: String, action: () -> Unit): Button = Button(this).also {
        it.text = caption
        it.setOnClickListener { action() }
        parent.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
    }

    private fun makeUi() {
        val cfg = SignalMailbox.settings(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Android 15 enforces edge-to-edge for target SDK 35. Explicitly respect
        // status/navigation/cutout and IME insets to avoid clipped content.
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(
                    bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom)
                )
                insets
            }
        }
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 30) root.requestApplyInsets()
        titleView = label("MAX Автосигналы · v0.5.1", true).apply {
            textSize = 16f
            setPadding(dp(10), dp(2), dp(10), dp(1))
        }
        root.addView(titleView)
        status = label("Ручной режим: введите код на вкладке «Сайт».").apply {
            textSize = 13f
            maxLines = 2
            setPadding(dp(10), dp(1), dp(10), dp(1))
        }
        root.addView(status)
        tabsView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(tabsView)
        Button(this).apply {
            text = "Настройки"
            setOnClickListener { showSettings() }
            tabsView.addView(this, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        Button(this).apply {
            text = "Сайт"
            setOnClickListener { showSite() }
            tabsView.addView(this, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        fullscreenToolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        root.addView(fullscreenToolbar)
        Button(this).apply {
            text = "← Вернуться к управлению"
            textSize = 13f
            isAllCaps = false
            setOnClickListener { setSiteFullscreen(false) }
            fullscreenToolbar.addView(this, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        }

        val settingsColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        settingsView = ScrollView(this)
        settingsView.addView(settingsColumn)
        root.addView(settingsView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        settingsColumn.addView(label("TELEGRAM: КОМАНДА → Сигнал", true))
        settingsColumn.addView(label("Чтение истории через TDLib в обычном Telegram-аккаунте. Бот не нужен. На этом этапе только проверка чтения — ни один заказ из Telegram не отправляется."))
        telegramApiId = field("Telegram api_id", TelegramCredentials.apiId(this).takeIf { it>0 }?.toString() ?: "").apply { inputType=InputType.TYPE_CLASS_NUMBER }
        settingsColumn.addView(telegramApiId)
        telegramApiHash = field("api_hash (оставить пустым, чтобы не менять)", "").apply { inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        settingsColumn.addView(telegramApiHash)
        settingsColumn.addView(label("Получить api_id и api_hash: my.telegram.org → API development tools. Вводите только здесь, не отправляйте секрет в чат."))
        telegramState = label("Telegram не подключён")
        settingsColumn.addView(telegramState)
        addButton(settingsColumn,"Сохранить параметры Telegram") {
            val id=telegramApiId.text.toString().trim().toIntOrNull()
            if(id==null || id<=0) { telegramState.text="Укажите числовой api_id" }
            else try {
                TelegramCredentials.save(this,id,telegramApiHash.text.toString().trim())
                telegramApiHash.text.clear()
                telegramState.text=if(TelegramCredentials.configured(this))"Параметры Telegram сохранены" else "Необходимо заполнить api_hash"
            }catch(_:Exception){telegramState.text="api_hash должен содержать 32 шестнадцатеричных символа"}
        }
        addButton(settingsColumn,"Подключиться к Telegram") { telegramBridge.connect() }
        telegramPhone = field("Номер Telegram +7...", "").apply { inputType=InputType.TYPE_CLASS_PHONE }
        settingsColumn.addView(telegramPhone)
        addButton(settingsColumn,"Отправить номер для входа") { telegramBridge.submitPhone(telegramPhone.text.toString()) }
        telegramLoginCode = field("Код ВХОДА в Telegram", "").apply { inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        settingsColumn.addView(telegramLoginCode)
        addButton(settingsColumn,"Подтвердить вход Telegram") {
            telegramBridge.submitCode(telegramLoginCode.text.toString());telegramLoginCode.text.clear()
        }
        telegram2fa = field("Пароль двухэтапной защиты Telegram", "").apply {
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        settingsColumn.addView(telegram2fa)
        addButton(settingsColumn,"Подтвердить пароль Telegram") {
            telegramBridge.submitPassword(telegram2fa.text.toString());telegram2fa.text.clear()
        }
        settingsColumn.addView(label("Поиск только в теме «Сигнал» группы «КОМАНДА». Выбирайте календарную дату начала интервала и временные границы."))
        telegramDate = field("Дата ГГГГ-ММ-ДД",java.time.LocalDate.now().toString())
        telegramStart = field("Начало HH:mm","09:00")
        telegramEnd = field("Конец HH:mm","21:00")
        telegramDigits = field("Число цифр кода","7").apply{inputType=InputType.TYPE_CLASS_NUMBER}
        settingsColumn.addView(telegramDate)
        settingsColumn.addView(telegramStart)
        settingsColumn.addView(telegramEnd)
        settingsColumn.addView(telegramDigits)
        addButton(settingsColumn,"Проверить тему «Сигнал» (БЕЗ заказа)") {
            val date=runCatching {java.time.LocalDate.parse(telegramDate.text.toString().trim())}.getOrNull()
            if(date==null){telegramState.text="Проверьте дату ГГГГ-ММ-ДД"}
            else telegramBridge.testRead(date,telegramStart.text.toString(),telegramEnd.text.toString(),telegramDigits.text.toString().toIntOrNull() ?: -1)
        }
        // A separate, repeatable weekly schedule for read-only Telegram history scans.
        // The user can configure several nonoverlapping periods across weekdays.
        settingsColumn.addView(label("Расписание поиска Telegram", true))
        settingsColumn.addView(label("Выберите дни недели и интервал. Можно добавить несколько интервалов. Каждый повторяется еженедельно по местному времени телефона. Интервал через полночь относится ко дню его начала."))
        val weekdayRows = listOf(
            listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
            listOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        )
        weekdayRows.forEach { weekdays ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            settingsColumn.addView(row)
            weekdays.forEach { day ->
                val check = CheckBox(this).apply {
                    text = WeeklySearchSchedule.describeDays(setOf(day))
                    textSize = 13f
                    isChecked = day.value in 1..5
                    setPadding(0, 0, 0, 0)
                }
                scheduleDayChecks[day] = check
                row.addView(check, LinearLayout.LayoutParams(0, dp(46), 1f))
            }
        }
        fun addTimeInput(caption: String, initial: String): EditText {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            settingsColumn.addView(row)
            val entry = field(caption, initial).apply { inputType=InputType.TYPE_CLASS_DATETIME }
            row.addView(entry, LinearLayout.LayoutParams(0, dp(48), 1f))
            Button(this).apply {
                text = "Выбрать"
                textSize = 12f
                isAllCaps = false
                setOnClickListener {
                    val init = SignalPolicy.parseTime(entry.text.toString()) ?: LocalTime.of(10, 0)
                    TimePickerDialog(this@MainActivity, { _, hour, minute ->
                        entry.setText("%02d:%02d".format(hour, minute))
                    }, init.hour, init.minute, true).show()
                }
                row.addView(this, LinearLayout.LayoutParams(dp(116), dp(48)))
            }
            return entry
        }
        scheduleStart = addTimeInput("Начало HH:mm", "10:00")
        scheduleEnd = addTimeInput("Конец HH:mm", "10:30")
        addButton(settingsColumn,"Добавить интервал") {
            val selected = scheduleDayChecks.filterValues { it.isChecked }.keys.toSet()
            val timeStart = SignalPolicy.parseTime(scheduleStart.text.toString())
            val timeEnd = SignalPolicy.parseTime(scheduleEnd.text.toString())
            if (timeStart == null || timeEnd == null) {
                scheduleState.text = "Введите время в формате HH:mm"
            } else {
                val rule = WeeklySearchWindow(selected, timeStart, timeEnd)
                val current = WeeklySearchStore.load(this)
                if (!WeeklySearchSchedule.canAdd(current, rule)) {
                    scheduleState.text = "Выберите дни, разные времена начала и конца. Интервалы не должны пересекаться."
                } else {
                    WeeklySearchStore.save(this, current + rule)
                    renderWeeklySchedule()
                    scheduleState.text = "Интервал сохранён. Он будет повторяться каждую неделю."
                }
            }
        }
        scheduleList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        settingsColumn.addView(scheduleList)
        scheduleEnabled = CheckBox(this).apply {
            text = "Автоматически искать код по расписанию (БЕЗ заказа)"
            isChecked = WeeklySearchStore.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                if (checked && WeeklySearchStore.load(this@MainActivity).isEmpty()) {
                    isChecked = false
                    scheduleState.text = "Сначала добавьте хотя бы один интервал."
                } else {
                    WeeklySearchStore.setEnabled(this@MainActivity, checked)
                    scheduleState.text = if (checked) "Поиск включён (пока приложение открыто и Telegram подключён)" else "Поиск по расписанию выключен"
                    lastScheduledWindowKey = ""
                }
            }
        }
        settingsColumn.addView(scheduleEnabled)
        scheduleState = label("Автоматическая проверка только при открытом приложении и подключённом Telegram. Торговые заказы не запускаются.")
        settingsColumn.addView(scheduleState)
        addButton(settingsColumn, "Проверить текущее окно (БЕЗ заказа)") {
            val active = WeeklySearchSchedule.activeAt(WeeklySearchStore.load(this), ZonedDateTime.now())
            if (active == null) scheduleState.text = "Сейчас нет активного интервала поиска."
            else if (!telegramBridge.isReady()) scheduleState.text = "Сначала подключите аккаунт Telegram."
            else {
                val n = telegramDigits.text.toString().toIntOrNull() ?: 7
                scheduleState.text = "Проверяю тему «Сигнал» за ${active.window.start}–${active.window.end}…"
                telegramBridge.testRead(active.startDate, active.window.start.toString(), active.window.end.toString(), n)
            }
        }
        renderWeeklySchedule()
        settingsColumn.addView(label("Ручной ввод кода", true))
        settingsColumn.addView(label("Длина цифрового кода для кнопки «Выполнить». Это не влияет на Telegram."))
        manualDigits = field("Количество цифр (4–10)", cfg.codeDigits.toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        settingsColumn.addView(manualDigits)
        addButton(settingsColumn, "Сохранить длину кода") {
            val n = manualDigits.text.toString().trim().toIntOrNull()
            if (n == null || n !in 4..10) {
                Toast.makeText(this, "Укажите от 4 до 10 цифр", Toast.LENGTH_LONG).show()
            } else {
                val old = SignalMailbox.settings(this)
                SignalMailbox.update(this, old.copy(codeDigits=n, armed=false, autoConfirm=false))
                status.text = "Длина ручного кода сохранена: $n"
                Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
            }
        }
        settingsColumn.addView(label("Заказ выполняется по введённому вручную коду. Если результат неизвестен, повторная отправка запрещена."))
                settingsColumn.addView(label("История операций", true))
        history = label(SignalMailbox.history(this))
        settingsColumn.addView(history)
        settingsColumn.addView(label("Фоновая работа сайта", true))
        settingsColumn.addView(label("При сворачивании во время заказа сохранять WebView в маленьком плавающем окне Android. Это позволяет работать в других приложениях; экран блокировать нельзя."))
        val miniWindowPref = getSharedPreferences("trade_minipip_v1", MODE_PRIVATE)
        settingsColumn.addView(CheckBox(this).apply {
            text = "Мини-окно при сворачивании активного заказа"
            isChecked = miniWindowPref.getBoolean("enabled", true)
            setOnCheckedChangeListener { _, checked ->
                miniWindowPref.edit().putBoolean("enabled", checked).apply()
                refreshPictureInPicturePolicy()
            }
        })
        settingsColumn.addView(label("Диагностика фоновой автоматизации", true))
        backgroundTrace = label(BackgroundRunTrace.read(this))
        settingsColumn.addView(backgroundTrace)
        addButton(settingsColumn, "Обновить диагностику") {
            backgroundTrace.text = BackgroundRunTrace.read(this)
        }

        siteView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        root.addView(siteView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val (siteBrowser, reusingActiveBrowser) = TradeSession.browser(this)
        browser = siteBrowser.apply {
            settings.javaScriptEnabled = true // Site SPA and DOM driver require JavaScript.
            if (Build.VERSION.SDK_INT >= 26) setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return true
                    val host = uri.host.orEmpty().lowercase()
                    return uri.scheme != "https" || !(host == "lumecoe.com" || host.endsWith(".lumecoe.com"))
                }
                override fun onReceivedError(view: WebView?, req: WebResourceRequest?, error: WebResourceError?) {
                    if (req?.isForMainFrame == true) status.text = "Сайт недоступен. Проверьте соединение."
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    TradeSession.pageFinished(url.orEmpty())
                }
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    TradeSession.rendererGone()
                    (view?.parent as? ViewGroup)?.removeView(view)
                    view?.destroy()
                    status.text = "WebView остановлен Android. Проверьте «Заказы» и перезапустите приложение."
                    return true
                }
            }
        }
        // 0.4.5: Hide ONLY the non-financial "Быстрый" status popup from the
        // first DOM mutation, while leaving the entire site, trading dialogs,
        // site scripts and user actions visible. Installation must precede
        // openSite/loadUrl so it runs before the website creates the popup.
        if (!reusingActiveBrowser && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val cosmeticScript = assets.open("quick-dialog-shield.js")
                .bufferedReader().use { it.readText() }
            WebViewCompat.addDocumentStartJavaScript(
                browser, cosmeticScript,
                setOf("https://lumecoe.com", "https://*.lumecoe.com")
            )
        } else if (!reusingActiveBrowser) {
            // Unsupported Android System WebView: do not fall back to covering
            // the whole website or rewriting the order-confirmation DOM.
            status.text = "Обновите Android System WebView: скрытие окна «Быстрый» недоступно."
        }
        // Code and "Run" share a single compact row; the WebView gets the
        // remaining height, rather than being squeezed by fixed-height buttons.
        manualBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        siteView.addView(manualBar)
        manualCode = field("Цифровой код", "")
        manualCode.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        manualBar.addView(manualCode, LinearLayout.LayoutParams(0, dp(46), 1f))
        Button(this).apply {
            text = "Выполнить"
            textSize = 12f
            isAllCaps = false
            setOnClickListener { runManualCode() }
            manualBar.addView(this, LinearLayout.LayoutParams(dp(112), dp(46)))
        }
        // 0.4.3: No opaque native cover. The WebView always remains visible so
        // every page, dialog and site button can be observed during execution.
        siteView.addView(browser, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        websiteActionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        siteView.addView(websiteActionBar)
        fun bottomButton(caption: String, action: () -> Unit) {
            Button(this).apply {
                text = caption
                textSize = 11f
                minWidth = 0
                minimumWidth = 0
                isAllCaps = false
                setPadding(dp(1), 0, dp(1), 0)
                setOnClickListener { action() }
                websiteActionBar.addView(this, LinearLayout.LayoutParams(0, dp(44), 1f))
            }
        }
        bottomButton("Главная") {
            if (automation.isActive()) status.text = "Дождитесь завершения заказа."
            else openSite()
        }
        bottomButton("Весь экран") { setSiteFullscreen(true) }
        bottomButton("СТОП") {
            TradeSession.stop()
            status.text = "Остановка запрошена. Проверьте результат в разделе «Заказы»."
        }
    }

    private fun renderWeeklySchedule() {
        if (!::scheduleList.isInitialized) return
        scheduleList.removeAllViews()
        val saved = WeeklySearchStore.load(this)
        if (saved.isEmpty()) {
            scheduleList.addView(label("Интервалы пока не заданы"))
            return
        }
        saved.forEachIndexed { index, window ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val textView = label("${WeeklySearchSchedule.describeDays(window.days)} · ${window.start}–${window.end}")
            row.addView(textView, LinearLayout.LayoutParams(0, dp(54), 1f))
            Button(this).apply {
                text = "Удалить"
                textSize = 12f
                isAllCaps = false
                setOnClickListener {
                    WeeklySearchStore.save(this@MainActivity, WeeklySearchStore.load(this@MainActivity).filterIndexed { i, _ -> i != index })
                    if (WeeklySearchStore.load(this@MainActivity).isEmpty()) {
                        WeeklySearchStore.setEnabled(this@MainActivity, false)
                        scheduleEnabled.isChecked = false
                    }
                    renderWeeklySchedule()
                    scheduleState.text = "Интервал удалён"
                }
                row.addView(this, LinearLayout.LayoutParams(dp(100), dp(50)))
            }
            scheduleList.addView(row)
        }
    }

    /** Only READ Telegram history, never create a trading order. Limited to the foreground. */
    private fun refreshScheduledSearch() {
        if (!::scheduleState.isInitialized || !::telegramBridge.isInitialized) return
        if (!WeeklySearchStore.enabled(this)) return
        val current = ZonedDateTime.now(ZoneId.systemDefault())
        val intervals = WeeklySearchStore.load(this)
        val active = WeeklySearchSchedule.activeAt(intervals, current)
        if (active == null) {
            val next = WeeklySearchSchedule.nextStart(intervals, current)
            scheduleState.text = if (next == null) "Нет будущих интервалов" else
                "Следующий поиск: ${next.format(DateTimeFormatter.ofPattern("dd.MM, EEE HH:mm"))}"
            return
        }
        if (!telegramBridge.isReady()) {
            scheduleState.text = "Поиск запланирован на ${active.window.start}–${active.window.end}, но Telegram не подключён"
            return
        }
        val key = "${active.startDate}:${active.window.days.sortedBy { it.value }}:${active.window.start}:${active.window.end}"
        val now = SystemClock.elapsedRealtime()
        if (key == lastScheduledWindowKey && now - lastScheduledScan < 120_000L) return
        val n = telegramDigits.text.toString().toIntOrNull() ?: 7
        if (n !in 4..10) {
            scheduleState.text = "Укажите длину Telegram-кода от 4 до 10 цифр"
            return
        }
        lastScheduledWindowKey = key
        lastScheduledScan = now
        scheduleState.text = "Поиск Telegram: ${active.window.start}–${active.window.end} · ${WeeklySearchSchedule.describeDays(active.window.days)}"
        telegramBridge.testRead(active.startDate, active.window.start.toString(), active.window.end.toString(), n)
    }

    private fun setSiteFullscreen(enabled: Boolean) {
        fullscreenSite = enabled && visibleSite
        val normal = if (fullscreenSite) View.GONE else View.VISIBLE
        titleView.visibility = normal
        status.visibility = normal
        tabsView.visibility = normal
        manualBar.visibility = normal
        websiteActionBar.visibility = normal
        fullscreenToolbar.visibility = if (fullscreenSite) View.VISIBLE else View.GONE
    }

    private fun startVisibleAutomation(code: String) {
        status.text = "Выполняется заказ: подготавливаю страницу сайта"
        if (!TradeSession.start(this, code)) {
            // The code was claimed, but no trading action has been attempted.
            SignalMailbox.finish(this, "ОШИБКА", "Не удалось запустить защищённый фоновый режим", true)
            status.text = "Не удалось запустить фоновую службу. Заказ не начинался."
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            refreshPictureInPicturePolicy()
        }
    }

    fun displayTradeStatus(message: String) {
        if (::status.isInitialized) status.text = message
    }

    fun onTradeFinished(message: String) {
        if (::status.isInitialized) status.text = message
        if (::history.isInitialized) history.text = SignalMailbox.history(this)
        refreshPictureInPicturePolicy()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** One-shot manual code; Telegram search remains read-only. */
    private fun runManualCode() {
        if (!visibleSite || automation.isActive()) {
            status.text = "Дождитесь окончания текущего заказа."
            return
        }
        val code = manualCode.text.toString().trim()
        val expected = SignalMailbox.settings(this).codeDigits
        if (!SignalPolicy.validManualCode(code, expected)) {
            status.text = "Код должен содержать ровно $expected цифр."
            return
        }
        val uri = Uri.parse(browser.url.orEmpty())
        val host = uri.host.orEmpty().lowercase()
        if (uri.scheme != "https" || (host != "lumecoe.com" && !host.endsWith(".lumecoe.com"))) {
            status.text = "Сначала откройте сайт LumeCo и войдите в личный кабинет."
            return
        }
        val attempt = SignalMailbox.claimManualDetailed(this, code)
        val claim = attempt.claim
        if (claim == null) {
            status.text = attempt.rejection?.explanation ?: "Не удалось начать заказ. Проверьте историю операций."
            return
        }
        manualCode.text.clear()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text = "Начато выполнение заказа по введённому коду."
        startVisibleAutomation(claim.code)
    }

    private fun showSettings() {
        if (TradeSession.isActive()) {
            status.text = "Во время заказа сайт должен оставаться открытым. Настройки доступны после завершения."
            return
        }
        visibleSite = false
        setSiteFullscreen(false)
        settingsView.visibility = View.VISIBLE
        siteView.visibility = View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showSite() {
        visibleSite = true
        setSiteFullscreen(false)
        settingsView.visibility = View.GONE
        siteView.visibility = View.VISIBLE
    }

        private fun openSite() { browser.loadUrl("https://lumecoe.com/ru") }

    private fun shouldKeepVisibleForTrade(): Boolean {
        if (Build.VERSION.SDK_INT < 26 || !TradeSession.isActive() || !visibleSite) return false
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        return getSharedPreferences("trade_minipip_v1", MODE_PRIVATE).getBoolean("enabled", true)
    }

    private fun pictureInPictureParameters(autoEnter: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(9, 16))
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(autoEnter)
        return builder.build()
    }

    private fun refreshPictureInPicturePolicy() {
        if (Build.VERSION.SDK_INT < 26 || !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        // Android 12+ enters the mini-window on Home/gesture without destroying
        // the Activity. The FGS still owns the order step loop.
        runCatching {
            setPictureInPictureParams(pictureInPictureParameters(shouldKeepVisibleForTrade()))
        }.onFailure {
            BackgroundRunTrace.note(this, "Не удалось включить мини-окно: ${it.javaClass.simpleName}")
        }
    }

    override fun onUserLeaveHint() {
        // Older Android versions do not support autoEnterEnabled.
        if (Build.VERSION.SDK_INT in 26..30 && shouldKeepVisibleForTrade()) {
            runCatching {
                enterPictureInPictureMode(pictureInPictureParameters(false))
            }.onSuccess { entered ->
                BackgroundRunTrace.note(this, if (entered) "Мини-окно запущено" else "Android отказал во входе в мини-окно")
            }.onFailure {
                BackgroundRunTrace.note(this, "Ошибка мини-окна: ${it.javaClass.simpleName}")
            }
        }
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(inPictureInPictureMode: Boolean, configuration: Configuration) {
        super.onPictureInPictureModeChanged(inPictureInPictureMode, configuration)
        if (inPictureInPictureMode) {
            // Keep the REAL WebView laid out and visible, not an opaque status screen.
            setSiteFullscreen(true)
            fullscreenToolbar.visibility = View.GONE
            BackgroundRunTrace.note(this, "Мини-окно активно, этап: ${TradeSession.stageForDiagnostics()}")
        } else {
            setSiteFullscreen(false)
            BackgroundRunTrace.note(this, "Выход из мини-окна, этап: ${TradeSession.stageForDiagnostics()}")
        }
    }

    override fun onResume() {
        super.onResume()
        TradeSession.attach(this)
        refreshPictureInPicturePolicy()
        TradeSession.activityForegrounded()
        if (::backgroundTrace.isInitialized) backgroundTrace.text = BackgroundRunTrace.read(this)
        checkHandler.removeCallbacks(scheduledTelegramCheck)
        checkHandler.post(scheduledTelegramCheck)
    }

    override fun onPause() {
        // Minimize must not cancel an already-started transaction.
        TradeSession.activityBackgrounded()
        if (::browser.isInitialized && TradeSession.isActive()) browser.resumeTimers()
        checkHandler.removeCallbacks(scheduledTelegramCheck)
        super.onPause()
    }

        @Deprecated("Use onBackInvokedDispatcher for target Android versions when migrating UI")
    override fun onBackPressed() {
        if (fullscreenSite) {
            setSiteFullscreen(false)
            return
        }
        if (visibleSite && browser.canGoBack()) {
            browser.goBack()
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        checkHandler.removeCallbacks(scheduledTelegramCheck)
        // Closing/recreating an Activity is not a request to cancel an active order.
        // Do not destroy its WebView: the explicit foreground service owns the run.
        if (!TradeSession.isActive()) {
            if (::browser.isInitialized) browser.destroy()
        }
        TradeSession.detach(this)
        if (::telegramBridge.isInitialized) telegramBridge.stop()
        super.onDestroy()
    }
}
