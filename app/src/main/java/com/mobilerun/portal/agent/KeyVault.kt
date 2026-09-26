package com.mobilerun.portal.agent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretBox {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/** AES-GCM with a key that never leaves the Android Keystore. Output: 12-byte IV + ciphertext. */
class KeystoreSecretBox(private val alias: String = "fastautomate-agent-key") : SecretBox {
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, sealed, 0, 12))
        return cipher.doFinal(sealed, 12, sealed.size - 12)
    }
}

/** This phone's OpenRouter key (from the dashboard's agent/credentials), sealed on disk. */
class KeyVault(private val file: File, private val box: SecretBox) {
    @Synchronized
    fun save(key: String, hash: String) {
        val sealed = java.util.Base64.getEncoder().encodeToString(box.seal(key.toByteArray(Charsets.UTF_8)))
        file.writeText(JSONObject().put("hash", hash).put("sealed", sealed).toString())
    }

    @Synchronized
    fun key(): String? {
        val json = read() ?: return null
        return runCatching {
            String(box.open(java.util.Base64.getDecoder().decode(json.getString("sealed"))), Charsets.UTF_8)
        }.getOrNull()
    }

    @Synchronized
    fun hash(): String = read()?.optString("hash").orEmpty()

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun read(): JSONObject? =
        if (file.exists() && file.length() > 0) runCatching { JSONObject(file.readText()) }.getOrNull() else null
}
