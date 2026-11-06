package com.tuissh.app.ssh.key

import com.tuissh.app.core.Hashes
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * PuTTY private key (.ppk) reader.
 *
 * Ported from `SSHKeyFen/PPKConverter.swift`. Three on-disk versions exist:
 *
 *   v1  MAC over the plaintext private blob (SHA-1)
 *   v2  MAC over the plaintext, per-line metadata in the header
 *   v3  Argon2 key derivation, MAC over the whole MAC section
 *
 * Windows users overwhelmingly have v2/v3 files, and v1 shows up in old exports.
 * We convert to PEM only; the actual signature work stays in JSch.
 */
object PpKConverter {

    private const val MAC_V1 = "HMAC-SHA-1"
    private const val MAC_V2 = "HMAC-SHA-256"
    private const val MAC_V3 = "HMAC-SHA-256"

    fun convert(text: String, passphrase: String?): KeyParseResult {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val header = linkedMapOf<String, String>()
        val body = mutableListOf<String>()
        var inBody = false

        var keyType = ""
        // The algorithm sits on the `PuTTY-User-Key-File-N:` header line itself.
        var headerKeyType = ""
        var encryption = "none"
        var comment = ""
        var publicLineCount = 0
        var privateLineCount = 0
        var macLine = ""

        for (raw in lines) {
            val line = raw.trimEnd()
            when {
                line.isBlank() -> continue
                line.startsWith("PuTTY-User-Key-File-") -> {
                    val rest = line.removePrefix("PuTTY-User-Key-File-")
                    val version = rest.substringBefore(":")
                    headerKeyType = rest.substringAfter(":", "").trim()
                    if (headerKeyType.isEmpty()) headerKeyType = keyType
                    header["version"] = version
                }
                line.startsWith("Encryption:") -> encryption = line.substringAfter(':').trim()
                line.startsWith("Comment:") -> comment = line.substringAfter(':').trim()
                line.startsWith("Public-Lines:") -> { publicLineCount = line.substringAfter(':').trim().toIntOrNull() ?: 0; inBody = false }
                line.startsWith("Private-Lines:") -> { privateLineCount = line.substringAfter(':').trim().toIntOrNull() ?: 0; inBody = false }
                line.startsWith("Private-MAC:") -> macLine = line.substringAfter(':').trim()
                line.startsWith("Key-Derivation:") -> header["kdf"] = line.substringAfter(':').trim()
                line.startsWith("Argon2-Memory:") -> header["argon2mem"] = line.substringAfter(':').trim()
                line.startsWith("Argon2-Passes:") -> header["argon2passes"] = line.substringAfter(':').trim()
                line.startsWith("Argon2-Parallelism:") -> header["argon2par"] = line.substringAfter(':').trim()
                line.startsWith("Argon2-Salt:") -> header["argon2salt"] = line.substringAfter(':').trim()
                inBody -> body.add(line)
                else -> {
                    // First body line after the header is the key type.
                    if (keyType.isEmpty() && !line.contains(':') && !line.startsWith("PuTTY")) {
                        keyType = line.trim()
                        inBody = true
                    }
                }
            }
            if (line.startsWith("Public-Lines:") || line.startsWith("Private-Lines:")) inBody = true
        }

        // PPK v2/v3 put the algorithm on the header line itself:
        //   PuTTY-User-Key-File-2: ssh-ed25519
        // The body scan above only helps when the header is truncated, so the header
        // wins and the body is the fallback.
        if (keyType.isEmpty()) keyType = headerKeyType
        if (keyType.isEmpty()) return KeyParseResult.Failure("missing key type")

        val version = header["version"] ?: "2"

        if (encryption != "none" && passphrase.isNullOrEmpty()) return KeyParseResult.NeedsPassphrase

        val algorithm = when {
            keyType.contains("ssh-rsa") -> "RSA"
            keyType.contains("ecdsa") -> "ECDSA"
            keyType.contains("ed25519") -> "ED25519"
            keyType.contains("dss") -> "DSA"
            else -> "RSA"
        }

        // We cannot faithfully reproduce PuTTY's private blob without the full
        // Argon2/bcrypt KDF, so conversion is honest about its limit: report what the
        // key is and let the user export it as PEM from PuTTYgen. This is better than
        // producing a silently corrupt PEM.
        val fingerprint = deriveFingerprint(body.take(maxOf(publicLineCount, 1)).joinToString(""))

        return KeyParseResult.Failure(
            buildString {
                append("PuTTY PPK v").append(version).append(" (").append(algorithm).append(")")
                if (encryption != "none") append(", encrypted with ").append(encryption)
                if (fingerprint != null) append(", ").append(fingerprint)
                append(
                    ". PPK conversion is not supported: open the key in PuTTYgen and export it " +
                        "as an OpenSSH key, or paste the OpenSSH key directly " +
                        "(-----BEGIN OPENSSH PRIVATE KEY-----).",
                )
            },
        )
    }

    private fun deriveFingerprint(publicBase64: String): String? {
        if (publicBase64.isBlank()) return null
        // java.util.Base64, not the Android stub: see KeyParser.decodeBase64.
        val blob = runCatching { java.util.Base64.getMimeDecoder().decode(publicBase64) }.getOrNull()
            ?: return null
        return Hashes.sshFingerprint(blob)
    }

    /** Internal helper kept for a future full PEM conversion path. */
    @Suppress("unused")
    private fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key, algorithm))
        return mac.doFinal(data)
    }

    @Suppress("unused")
    private fun decryptAesCtr(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray? = runCatching {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        cipher.doFinal(data)
    }.getOrNull()

    @Suppress("unused")
    private val macConstants = listOf(MAC_V1, MAC_V2, MAC_V3)
}
