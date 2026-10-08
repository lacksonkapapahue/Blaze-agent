package com.blaze.assistant.agent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts API keys and sign-in tokens with a key that never leaves the phone's Android Keystore.
 * Stored values look like "enc1:<base64>". Plain values (from older versions or a backup import)
 * still read fine and are encrypted on the next start.
 */
object Secrets {
    private const val ALIAS = "blaze_secrets"
    private const val PREFIX = "enc1:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    fun isEncrypted(v: String) = v.startsWith(PREFIX)

    fun encrypt(plain: String): String {
        if (plain.isEmpty() || isEncrypted(plain)) return plain
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val out = c.iv + c.doFinal(plain.toByteArray())
            PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (_: Exception) { plain }   // Keystore unavailable: keep working rather than lose the key.
    }

    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun decrypt(stored: String): String {
        if (!isEncrypted(stored)) return stored
        cache[stored]?.let { return it }
        return try {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12)).also { cache[stored] = it }
        } catch (_: Exception) { "" }   // e.g. restored onto another phone
    }

    /** Pref names that hold secrets. */
    fun isSecretPref(k: String) = k.startsWith("key_") || k.startsWith("conn_tok_") || k.startsWith("mcp_tok_")
}
