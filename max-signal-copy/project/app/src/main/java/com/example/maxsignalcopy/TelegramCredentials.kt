package com.example.maxsignalcopy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** App-specific keys never leave Android Keystore; API hash is not shipped in the APK. */
object TelegramCredentials {
    private const val FILE = "telegram_credentials_private_v1"
    private const val ALIAS = "telegram_credentials_aes_v1"
    private fun prefs(c:Context) = c.getSharedPreferences(FILE,Context.MODE_PRIVATE)
    private fun key():java.security.Key {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey(ALIAS,null)?.let{return it}
        val generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }
    fun apiId(c:Context):Int = prefs(c).getInt("id",0)
    fun apiHash(c:Context):String {
        val p=prefs(c)
        return try {
            val data=p.getString("hash",null)?:return ""
            val iv=p.getString("iv",null)?:return ""
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data,Base64.NO_WRAP)),Charsets.UTF_8)
        }catch(_:Exception){""}
    }
    fun configured(c:Context):Boolean=apiId(c)>0 && apiHash(c).isNotBlank()
    fun save(c:Context, id:Int, newHash:String) {
        require(id>0)
        val edit=prefs(c).edit().putInt("id",id)
        if(newHash.isNotBlank()){
            require(newHash.matches(Regex("[0-9a-fA-F]{32}")))
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE,key())
            val encrypted=cipher.doFinal(newHash.toByteArray(Charsets.UTF_8))
            edit.putString("hash",Base64.encodeToString(encrypted,Base64.NO_WRAP))
                .putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP))
        }
        check(edit.commit())
    }
}
