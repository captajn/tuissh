package com.tuissh.app.ssh.key

import com.tuissh.app.core.Hashes
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * OpenSSH private-key decryption for the "does this passphrase work?" check.
 *
 * The actual SSH library decrypts the key itself; we only need a cheap, correct
 * verification so the UI can say `BadPassphrase` immediately instead of failing
 * later at connect time. This mirrors `SSHPrivateKeyModel` behaviour in the
 * original app.
 *
 * Cipher suites used by OpenSSH key files:
 *   none, aes128-cbc, aes192-cbc, aes256-cbc, aes128-ctr, aes192-ctr, aes256-ctr,
 *   aes128-gcm@openssh.com, aes256-gcm@openssh.com, chacha20-poly1305@openssh.com
 *
 * KDFs: bcrypt (with rounds + salt), none.
 */
object AesCtrCipher {

    /** Result of a local passphrase check. */
    enum class PassphraseVerdict {
        /** The checkint bytes matched; the passphrase is correct. */
        Correct,

        /** Decryption succeeded but the checkints differ; the passphrase is wrong. */
        Wrong,

        /** The KDF or cipher is not available locally, so we cannot decide. */
        CannotVerify,
    }

    /**
     * Check [passphrase] against an OpenSSH private key section.
     *
     * Returns [PassphraseVerdict.CannotVerify] rather than [PassphraseVerdict.Wrong]
     * when the key uses bcrypt_pbkdf, which the Android platform does not expose.
     */
    fun verifyOpenSshPassphrase(
        cipherName: String,
        kdfName: String,
        kdfOptions: ByteArray,
        encryptedPrivateSection: ByteArray,
        passphrase: String,
    ): PassphraseVerdict {
        if (cipherName == "none") return PassphraseVerdict.Correct

        val (keyLen, ivLen, blockSize) = cipherParams(cipherName)
            ?: return PassphraseVerdict.CannotVerify

        val keyAndIv = when (kdfName) {
            "bcrypt" -> deriveBcrypt(kdfOptions, passphrase, keyLen + ivLen)
            "none" -> ByteArray(0)
            else -> null
        } ?: return PassphraseVerdict.CannotVerify

        if (keyAndIv.isEmpty()) return PassphraseVerdict.CannotVerify

        val key = keyAndIv.copyOfRange(0, keyLen)
        val iv = keyAndIv.copyOfRange(keyLen, keyLen + ivLen)

        val decrypted = decrypt(cipherName, key, iv, encryptedPrivateSection, blockSize)
            ?: return PassphraseVerdict.Wrong

        // The private section starts with checkint1, checkint2 which must match.
        if (decrypted.size < 8) return PassphraseVerdict.Wrong
        val c1 = ((decrypted[0].toInt() and 0xFF) shl 24) or
            ((decrypted[1].toInt() and 0xFF) shl 16) or
            ((decrypted[2].toInt() and 0xFF) shl 8) or
            (decrypted[3].toInt() and 0xFF)
        val c2 = ((decrypted[4].toInt() and 0xFF) shl 24) or
            ((decrypted[5].toInt() and 0xFF) shl 16) or
            ((decrypted[6].toInt() and 0xFF) shl 8) or
            (decrypted[7].toInt() and 0xFF)
        return if (c1 == c2) PassphraseVerdict.Correct else PassphraseVerdict.Wrong
    }

    private fun cipherParams(name: String): Triple<Int, Int, Int>? = when (name) {
        "aes128-cbc" -> Triple(16, 16, 16)
        "aes192-cbc" -> Triple(24, 16, 16)
        "aes256-cbc" -> Triple(32, 16, 16)
        "aes128-ctr" -> Triple(16, 16, 16)
        "aes192-ctr" -> Triple(24, 16, 16)
        "aes256-ctr" -> Triple(32, 16, 16)
        "aes128-gcm@openssh.com" -> Triple(16, 12, 16)
        "aes256-gcm@openssh.com" -> Triple(32, 12, 16)
        else -> null
    }

    /**
     * bcrypt_pbkdf. OpenSSH uses a modified bcrypt with SHA-512 as the hash.
     *
     * kdfOptions is `string salt` + `uint32 rounds`.
     */
    private fun deriveBcrypt(kdfOptions: ByteArray, passphrase: String, outLen: Int): ByteArray? {
        val reader = SshBinaryReader(kdfOptions, 0)
        val salt = reader.readString() ?: return null
        val rounds = reader.readUInt32()?.toInt() ?: return null
        if (rounds <= 0 || rounds > 1_000_000) return null
        if (salt.isEmpty()) return null
        // bcrypt is not available on the Android platform; see BcryptPbkdf.
        return BcryptPbkdf.derive(
            password = passphrase.toByteArray(Charsets.UTF_8),
            salt = salt,
            rounds = rounds,
            keyLen = outLen,
        )
    }

    private fun decrypt(
        name: String,
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray,
        blockSize: Int,
    ): ByteArray? = runCatching {
        when {
            name.endsWith("-ctr") -> {
                val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                cipher.doFinal(data)
            }
            name.endsWith("-cbc") -> {
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                val out = cipher.doFinal(data)
                // Strip PKCS#7 padding.
                if (out.isEmpty()) return@runCatching null
                val pad = out[out.size - 1].toInt() and 0xFF
                if (pad in 1..blockSize && pad <= out.size) out.copyOfRange(0, out.size - pad) else out
            }
            name.contains("gcm") -> {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val spec = javax.crypto.spec.GCMParameterSpec(128, iv)
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
                cipher.doFinal(data)
            }
            else -> null
        }
    }.getOrNull()

    // Kept for parity with the original: HMAC-SHA256 helper used by PPK verification.
    internal fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    internal fun sha1(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(data)

    internal fun fingerprintOf(blob: ByteArray): String = Hashes.sshFingerprint(blob)
}
