package com.jiiro.learnharness

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.serverStore by preferencesDataStore(name = "server")

/** Server URL in DataStore; the token is AES-GCM encrypted with an Android Keystore key. */
class ConfigStore(private val context: Context) {
    suspend fun load(): ServerConfig? {
        val prefs = context.serverStore.data.first()
        val url = prefs[URL]?.let(ServerUrls::normalize) ?: return null
        // A token that no longer decrypts (key removed) reads as none; the setup screen re-asks.
        val token = prefs[TOKEN]?.let(TokenCipher::decrypt)
        return ServerConfig(url, token)
    }

    suspend fun save(config: ServerConfig) {
        context.serverStore.edit { prefs ->
            prefs[URL] = config.url
            val token = config.token
            if (token.isNullOrEmpty()) prefs.remove(TOKEN) else prefs[TOKEN] = TokenCipher.encrypt(token)
        }
    }

    private companion object {
        val URL = stringPreferencesKey("url")
        val TOKEN = stringPreferencesKey("token")
    }
}

private object TokenCipher {
    private const val KEY_ALIAS = "lh_token_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String? = try {
        val sealed = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }
            .generateKey()
    }
}
