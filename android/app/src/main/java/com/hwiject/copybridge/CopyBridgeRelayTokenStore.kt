package com.hwiject.copybridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object CopyBridgeRelayTokenStore {

    private const val KEY_ALIAS = "copybridge_relay_read_token_v1"
    private const val PREFS_NAME = "copybridge_relay_auth"
    private const val IV_KEY = "read_token_iv"
    private const val CIPHERTEXT_KEY = "read_token_ciphertext"

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or
                KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(
                KeyProperties.ENCRYPTION_PADDING_NONE
            )
            .setKeySize(256)
            .build()

        generator.init(spec)
        return generator.generateKey()
    }

    fun save(context: Context, token: String): Boolean {
        val value = token.trim()

        require(value.length in 24..256) {
            "Invalid relay credential length"
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())

        val encrypted = cipher.doFinal(
            value.toByteArray(Charsets.UTF_8)
        )

        val iv = Base64.encodeToString(
            cipher.iv,
            Base64.NO_WRAP
        )

        val ciphertext = Base64.encodeToString(
            encrypted,
            Base64.NO_WRAP
        )

        val prefs = context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

        return prefs.edit()
            .putString(IV_KEY, iv)
            .putString(CIPHERTEXT_KEY, ciphertext)
            .commit()
    }

    fun readToken(context: Context): String? {
        val prefs = context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

        val ivString = prefs.getString(IV_KEY, null)
            ?: return null

        val ciphertextString =
            prefs.getString(CIPHERTEXT_KEY, null)
                ?: return null

        return try {
            val iv = Base64.decode(
                ivString,
                Base64.NO_WRAP
            )

            val encrypted = Base64.decode(
                ciphertextString,
                Base64.NO_WRAP
            )

            if (iv.size != 12) return null

            val cipher = Cipher.getInstance(
                "AES/GCM/NoPadding"
            )

            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, iv)
            )

            val decrypted = cipher.doFinal(encrypted)

            String(decrypted, Charsets.UTF_8)
                .takeIf { it.isNotBlank() }

        } catch (_: Exception) {
            null
        }
    }

    fun hasToken(context: Context): Boolean {
        return readToken(context) != null
    }
}
