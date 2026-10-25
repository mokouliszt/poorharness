package dev.mokouliszt.poordsh.secrets

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys typed into the UI (Tensorlake), encrypted with an AES-GCM key held
 * in the Android Keystore and stored in app-private preferences. Values survive
 * app restarts and updates; they are not backed up (the app disables backup,
 * and a Keystore key could not decrypt them on another device anyway).
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (_: Exception) {
            // Key lost (e.g. device credential reset): treat as unset rather than crash.
            null
        }
    }

    fun put(name: String, value: String?) {
        if (value.isNullOrEmpty()) {
            // commit(): the write must be on disk before the app can be killed.
            prefs.edit().remove(name).commit()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val out = cipher.iv + ct
        prefs.edit().putString(name, Base64.encodeToString(out, Base64.NO_WRAP)).commit()
    }

    fun has(name: String): Boolean = get(name) != null

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    companion object {
        const val TENSORLAKE_API_KEY = "tensorlake_api_key"
        /** TinyFish, the fallback for webfetch and websearch (docs/adr/web-in-sandbox.md). */
        const val TINYFISH_API_KEY = "tinyfish_api_key"
        /** The S3-compatible bucket's settings and keys, as one JSON ([dev.mokouliszt.poordsh.storage.S3Storage.Config]). */
        const val S3_STORAGE = "s3_storage"
        private const val ALIAS = "poordsh.secrets.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}
