package com.tuissh.app.data.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.tuissh.app.core.LogTag
import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoLog
import com.tuissh.app.core.NeoResult
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM vault backed by the Android Keystore.
 *
 * Purpose: the original app stored SSH passwords and private keys through
 * `PrivateKeyManager` using a keychain item. Android's equivalent is a
 * non-exportable Keystore key, so the plaintext never touches disk and cannot be
 * extracted from a backup.
 *
 * Wire format: `base64( [12-byte IV] + [ciphertext] )`.
 */
class CredentialVault(context: Context) {

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "neoserver.vault.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val IV_BYTES = 12
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    init {
        ensureKey()
    }

    private fun ensureKey(): SecretKey {
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // No user authentication requirement: background reconnect / monitoring
            // must be able to decrypt without prompting. The app lock is a separate
            // BiometricPrompt gate in front of the whole UI.
            .setUserAuthenticationRequired(false)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun key(): SecretKey =
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: ensureKey()

    /** Encrypt UTF-8 text. Returns base64 with the IV prefixed. */
    fun encrypt(plaintext: String): NeoResult<String> = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(iv.size + body.size)
        System.arraycopy(iv, 0, packed, 0, iv.size)
        System.arraycopy(body, 0, packed, iv.size, body.size)
        NeoResult.Ok(java.util.Base64.getEncoder().encodeToString(packed))
    } catch (e: Exception) {
        NeoLog.e(LogTag.CRYPTO, "encrypt failed", e)
        NeoResult.Err(NeoError.Io("vault encrypt: ${e.message}"))
    }

    /** Decrypt a base64 payload produced by [encrypt]. */
    fun decrypt(payload: String): NeoResult<String> = try {
        val packed = java.util.Base64.getDecoder().decode(payload)
        require(packed.size > IV_BYTES) { "payload too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, packed, 0, IV_BYTES))
        val plain = cipher.doFinal(packed, IV_BYTES, packed.size - IV_BYTES)
        NeoResult.Ok(String(plain, Charsets.UTF_8))
    } catch (e: Exception) {
        NeoLog.e(LogTag.CRYPTO, "decrypt failed", e)
        NeoResult.Err(NeoError.Io("vault decrypt: ${e.message}"))
    }

    /** Decrypt or return null instead of a typed failure. */
    fun decryptOrNull(payload: String?): String? {
        if (payload.isNullOrEmpty()) return null
        return decrypt(payload).getOrNull()
    }

    /** Wipe the vault key. Existing ciphertext becomes unrecoverable. */
    fun destroy() {
        runCatching { keyStore.deleteEntry(KEY_ALIAS) }
    }
}
