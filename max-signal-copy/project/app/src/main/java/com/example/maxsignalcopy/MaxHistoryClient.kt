package com.example.maxsignalcopy

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZoneId

/** Official MAX Bot API, never UI scraping or notification reading. Network only off main thread. */
object MaxHistoryClient {
    private const val HOST = "https://platform-api2.max.ru"
    data class Result(val choice: HistoryChoice?, val message: String)

    private fun get(path: String, token: String): JSONObject {
        val conn = URL("$HOST$path").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 12_000
        conn.readTimeout = 12_000
        conn.setRequestProperty("Authorization", token)
        conn.setRequestProperty("Accept", "application/json")
        conn.instanceFollowRedirects = false
        try {
            val status = conn.responseCode
            if (status != 200) throw IllegalStateException(when (status) {
                401 -> "MAX: неверный токен бота (401)."
                403 -> "MAX: недостаточно прав администратора на чтение чата (403)."
                404 -> "MAX: чат не найден (404)."
                else -> "MAX: ошибка запроса ($status)."
            })
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            if (body.length > 1_000_000) throw IllegalStateException("MAX: ответ слишком большой.")
            return JSONObject(body)
        } finally { conn.disconnect() }
    }

    fun read(context: Context, settings: SignalSettings, now: Long): Result {
        val token = MaxApiCredentials.token(context)
        val id = MaxApiCredentials.chatId(context)
        if (token.isBlank() || !id.matches(Regex("-?[0-9]+")))
            return Result(null, "Введите chat_id и токен бота в настройках MAX.")
        val w = MaxHistoryPolicy.todayWindow(settings, now, ZoneId.systemDefault())
            ?: return Result(null, "Некорректный временной интервал.")
        if (w.endMillis < w.startMillis) return Result(HistoryChoice.NoCode, "Окно поиска сегодня ещё не началось.")
        return try {
            // Verify actual chat ID/title on server, not a guessed sender display name.
            val metadata = get("/chats/$id", token)
            if (!metadata.optString("title").trim().equals(settings.chatTitle.trim(), ignoreCase = true))
                return Result(null, "MAX: chat_id не соответствует чату «${settings.chatTitle}».")
            // API semantics: 'from' is upper timestamp, 'to' lower timestamp (milliseconds).
            val body = get("/messages?chat_id=$id&from=${w.endMillis}&to=${w.startMillis}&count=100", token)
            val array = body.optJSONArray("messages")
                ?: return Result(null, "MAX: сервер не вернул список сообщений.")
            val messages = (0 until array.length()).mapNotNull { index ->
                val o = array.optJSONObject(index) ?: return@mapNotNull null
                val timestamp = o.optLong("timestamp", -1L)
                if (timestamp <= 0) return@mapNotNull null
                ChatLine(o.optString("mid"), timestamp, o.optJSONObject("body")?.optString("text").orEmpty())
            }
            val choice = MaxHistoryPolicy.choose(messages, w, settings.codeDigits, now,
                pageFull = array.length() >= 100)
            val diagnostic = when (choice) {
                is HistoryChoice.Found -> "MAX: код найден в истории «${settings.chatTitle}»; время подтверждено."
                HistoryChoice.NoCode -> "MAX: за выбранный период подходящий код не найден."
                HistoryChoice.Ambiguous -> "MAX: несколько разных кодов. Заказ не запущен."
                HistoryChoice.Incomplete -> "MAX: слишком много сообщений за период. Сократите окно поиска."
                HistoryChoice.TooOld -> "MAX: найденный код старше 5 минут. Заказ не запущен."
            }
            Result(choice, diagnostic)
        } catch (e: Exception) {
            // Never include HTTP response body/token in the UI or crash reports.
            Result(null, when (e) {
                is IllegalStateException -> e.message ?: "MAX: ошибка проверки истории."
                else -> "MAX: соединение не удалось. Проверьте сеть и сертификаты MAX."
            })
        }
    }
}
