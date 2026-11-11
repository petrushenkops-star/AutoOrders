package com.example.maxsignalcopy

import android.net.Uri

/**
 * The only website the application navigates to for manual orders.
 * Keep package identity and signing separate from the website configuration.
 */
internal object SiteConfig {
    const val HOME_URL = "https://pbeaocuec.com/ru/futures?name=BTC/USDT&tabName=FUTURES"
    private const val HOST = "pbeaocuec.com"
    // Unverified domain: financial actions are disabled until independently checked.
    const val AUTO_TRADING_ENABLED = false
    val DOCUMENT_START_ORIGINS: Set<String> =
        setOf("https://pbeaocuec.com")

    fun accepts(uri: Uri?): Boolean {
        val address = uri ?: return false
        if (address.scheme != "https") return false
        val host = address.host.orEmpty().lowercase()
        return host == HOST
    }

    fun accepts(url: String): Boolean =
        runCatching { accepts(Uri.parse(url)) }.getOrDefault(false)

    /**
     * Accept the root and a language landing page after a site redirect.
     * Do not treat an arbitrary trade/order route as completion of home reload.
     */
    fun isHomePage(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        if (!accepts(uri)) return false
        val path = uri.path.orEmpty().trimEnd('/')
        if (path == "/ru/futures" &&
            uri.getQueryParameter("name") == "BTC/USDT" &&
            uri.getQueryParameter("tabName") == "FUTURES") return true
        return path.isEmpty() || Regex("^/[a-zA-Z]{2}(?:-[a-zA-Z]{2})?$").matches(path)
    }
}
