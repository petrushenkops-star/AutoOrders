package com.example.maxsignalcopy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** Token is encrypted using a non-exportable Android Keystore AES-GCM key. */
object MaxApiCredentials {
    private const val FILE = "max_history_bot_credentials"
    private const val ALIAS = "max_history_token_v1"
    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun chatId(context: Context): String = prefs(context).getString("chat_id", "").orEmpty()
    fun configured(context: Context): Boolean = chatId(context).matches(Regex("-?[0-9]+")) && token(context).isNotBlank()

    private fun key(): java.security.Key {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey(ALIAS, null)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }

    fun update(context: Context, chatId: String, replacementToken: String) {
        require(chatId.matches(Regex("-?[0-9]+")))
        val edit = prefs(context).edit().putString("chat_id", chatId)
        if (replacementToken.isNotEmpty()) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(replacementToken.toByteArray(Charsets.UTF_8))
            edit.putString("token", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        }
        check(edit.commit())
    }

    fun token(context: Context): String {
        val p = prefs(context)
        val data = p.getString("token", null) ?: return ""
        val iv = p.getString("iv", null) ?: return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }
}
