package com.example.maxsignalcopy

import android.content.Context
import android.content.ContextWrapper
import android.content.MutableContextWrapper
import android.webkit.WebView
import java.lang.ref.WeakReference

/** One process-local owner for an in-progress WebView order. Never persist the code. */
object TradeSession {
    private var web: WebView? = null
    private var engine: BrowserAutomation? = null
    private var listener: WeakReference<MainActivity>? = null
    private var app: Context? = null
    private var running = false
    var lastStatus: String = ""
        private set

    /** Reuse the exact same website page/session after Activity recreation. */
    fun browser(activity: MainActivity): Pair<WebView, Boolean> {
        listener = WeakReference(activity)
        app = activity.applicationContext
        val existing = web
        if (existing != null && isActive()) {
            (existing.context as? MutableContextWrapper)?.baseContext = activity
            (existing.parent as? android.view.ViewGroup)?.removeView(existing)
            return existing to true
        }
        web?.destroy()
        val newBrowser = WebView(MutableContextWrapper(activity))
        web = newBrowser
        engine = null
        return newBrowser to false
    }

    fun bind(activity: MainActivity, currentWeb: WebView): BrowserAutomation {
        listener = WeakReference(activity)
        app = activity.applicationContext
        if (engine == null || !isActive()) {
            engine = BrowserAutomation(currentWeb, { report(it) }, { result, detail, safe -> onFinished(result, detail, safe) })
        }
        return checkNotNull(engine)
    }

    fun isActive() = running && engine?.isActive() == true

    fun start(context: Context, code: String): Boolean {
        if (running || engine == null) return false
        try {
            // Start a genuine OS foreground service BEFORE touching the trading DOM.
            TradeForegroundService.start(context)
        } catch (e: Exception) {
            report("Фоновый режим недоступен (${e.javaClass.simpleName}). Заказ не начинался.")
            return false
        }
        running = true
        engine!!.start(code)
        // The engine can fail synchronously if the web session has expired.
        return true
    }

    fun stop() { engine?.stop() }

    fun onServiceTimeout() {
        if (isActive()) engine?.stop() // uncertain stage remains НЕИЗВЕСТНО; no repeat.
    }

    fun attach(activity: MainActivity) {
        listener = WeakReference(activity)
        app = activity.applicationContext
        if (lastStatus.isNotBlank()) activity.displayTradeStatus(lastStatus)
    }

    fun detach(activity: MainActivity) {
        if (listener?.get() === activity) listener = null
        // If the Activity is destroyed during an active operation, retain the WebView
        // and its session in this process; the foreground service holds process priority.
        if (!isActive()) {
            web = null
            engine = null
        }
    }

    private fun report(message: String) {
        lastStatus = message
        listener?.get()?.displayTradeStatus(message)
        if (running) app?.let { TradeForegroundService.update(it, message) }
    }

    private fun onFinished(result: String, detail: String, safeToRetry: Boolean) {
        val context = app ?: return
        running = false
        SignalMailbox.finish(context, result, detail, safeToRetry)
        // Stop the temporary foreground notification before publishing the verified result.
        TradeForegroundService.finish(context)
        if (OrderPendingNotificationPolicy.shouldNotify(result)) {
            val posted = OrderPendingNotifier.show(context)
            lastStatus = if (posted) "Заказ в ожидании" else "Заказ в ожидании. Разрешите уведомления Android."
        } else lastStatus = detail
        listener?.get()?.onTradeFinished(lastStatus)
    }
}
