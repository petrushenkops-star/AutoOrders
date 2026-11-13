package com.example.autoorders

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import android.net.Uri

class QuickOrderActivity : ComponentActivity() {
    private val url = "https://pbeaocuec.com/ru"

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, QuickOrderAccessibilityService::class.java)
            .flattenToString()
        val enabled = Settings.Secure.getString(contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun openChrome() {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            setPackage("com.android.chrome")
        }
        try { startActivity(i) }
        catch (_: ActivityNotFoundException) {
            QuickOrderSession.message.value =
                "Chrome не установлен. Для автоматизации нужен Google Chrome."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var code by remember { mutableStateOf("") }
                var enabled by remember { mutableStateOf(isAccessibilityEnabled()) }
                val status = QuickOrderSession.message.value
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Автозаказы", style = MaterialTheme.typography.headlineMedium)
                    Text("Версия ${packageManager.getPackageInfo(packageName, 0).versionName} · Chrome")
                    Text("Сайт открывается в Chrome, без встроенного WebView. Авторизуйтесь на сайте вручную.")
                    Button(onClick = { openChrome() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Открыть сайт")
                    }
                    HorizontalDivider()
                    Text("Доступ к экрану Chrome",
                        style = MaterialTheme.typography.titleMedium)
                    Text(if (enabled) "Служба включена" else
                        "Один раз включите службу «Автозаказы» в настройках специальных возможностей Android.")
                    OutlinedButton(
                        onClick = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Настроить специальные возможности") }
                    OutlinedButton(
                        onClick = { enabled = isAccessibilityEnabled() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Проверить разрешение") }
                    HorizontalDivider()
                    OutlinedTextField(
                        value = code, onValueChange = { if (it.length <= 24 && it.all(Char::isDigit)) code = it },
                        label = { Text("Динамический код") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            if (!isAccessibilityEnabled()) {
                                QuickOrderSession.message.value = "Сначала включите службу специальных возможностей"
                            } else {
                                QuickOrderSession.begin(code)
                                code = ""
                                openChrome()
                            }
                        },
                        enabled = code.isNotBlank() &&
                            QuickOrderSession.step !in listOf(
                                QuickOrderSession.Step.OPEN_TRADE,
                                QuickOrderSession.Step.OPEN_COPY,
                                QuickOrderSession.Step.ENTER_CODE,
                                QuickOrderSession.Step.SEND_CODE,
                                QuickOrderSession.Step.WAIT_DETAILS,
                                QuickOrderSession.Step.OPEN_DETAILS),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Выполнить") }
                    OutlinedButton(
                        onClick = { QuickOrderSession.cancel() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Остановить") }
                    Text("Состояние автоматизации", style = MaterialTheme.typography.titleMedium)
                    SelectionContainer {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Text("Прокрутите экран, чтобы увидеть сообщение полностью. Текст можно выделить и скопировать.",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Автоматизация не подтверждает финансовый заказ и не повторяет отправку кода при неизвестном результате.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
