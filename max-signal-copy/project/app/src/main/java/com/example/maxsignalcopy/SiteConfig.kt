package com.example.maxsignalcopy

import android.net.Uri

/**
 * The only website the application navigates to for manual orders.
 * Keep package identity and signing separate from the website configuration.
 */
internal object SiteConfig {
    const val HOME_URL = "https://lumecoe.com/ru"
    private const val HOST = "lumecoe.com"
    val DOCUMENT_START_ORIGINS: Set<String> =
        setOf("https://lumecoe.com", "https://*.lumecoe.com")

    fun accepts(uri: Uri?): Boolean {
        val address = uri ?: return false
        if (address.scheme != "https") return false
        val host = address.host.orEmpty().lowercase()
        return host == HOST || host.endsWith(".$HOST")
    }

    fun accepts(url: String): Boolean =
        runCatching { accepts(Uri.parse(url)) }.getOrDefault(false)
}
