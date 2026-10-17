package com.faceclaw.app

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android platform Keystore AES-256-GCM (API 23+; app minSdk is 24). */
internal class FaceclawSecretSettings(private val prefs: SharedPreferences) {
    // A failed commit can still alter SharedPreferences' in-memory map. Do not
    // let a later read mistake that uncommitted ciphertext for durable storage.
    private var failed = false
    private val migration = SecretMigration(object : SecretMigrationStore {
        override fun plain(key: String): String? = prefs.getString(key, null)
        override fun cipher(key: String): String? = prefs.getString(FaceclawSecretKeys.cipherKey(key), null)
        override fun writeCipher(key: String, value: String): Boolean =
            prefs.edit().putString(FaceclawSecretKeys.cipherKey(key), value).commit()
        override fun removePlain(key: String): Boolean = prefs.edit().remove(key).commit()
        override fun seal(key: String, value: String): String = encrypt(key, value)
        override fun open(key: String, value: String): String = decrypt(key, value)
    })

    @Synchronized fun get(key: String, fallback: String?): String? = guard { migration.read(key) ?: fallback }
    @Synchronized fun put(key: String, value: String?) = guard { migration.write(key, value ?: "") }
    @Synchronized fun migrateExisting() {
        for (name in FaceclawSecretKeys.all()) {
            if (prefs.contains(name) || prefs.contains(FaceclawSecretKeys.cipherKey(name))) guard { migration.read(name) }
        }
    }

    private inline fun <T> guard(action: () -> T): T {
        if (failed) throw IllegalStateException("Credential storage is unavailable until restart")
        try { return action() }
        catch (error: Exception) { failed = true; throw error }
    }

    private fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(create: Boolean): SecretKey {
        val store = keystore()
        val existing = store.getKey(ALIAS, null)
        if (existing != null) return existing as SecretKey
        if (!create || prefs.all.keys.any(FaceclawSecretKeys::isInternal)) {
            throw IllegalStateException("Settings encryption key is unavailable")
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return generator.generateKey()
    }

    private fun encrypt(name: String, value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        if (cipher.iv.size != 12) throw IllegalStateException("Unexpected encryption IV length")
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return "v1:" + Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(name: String, encoded: String): String {
        if (!encoded.startsWith("v1:")) throw IllegalStateException("Unsupported encrypted setting format")
        val payload = Base64.decode(encoded.substring(3), Base64.NO_WRAP)
        if (payload.size < 28) throw IllegalStateException("Encrypted setting is damaged")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, payload.copyOfRange(0, 12)))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(payload.copyOfRange(12, payload.size)), Charsets.UTF_8)
    }

    companion object { private const val ALIAS = "faceclaw.settings.aes.v1" }
}
