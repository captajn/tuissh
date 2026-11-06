package com.tuissh.app.ssh.key

import com.tuissh.app.core.Hashes
import java.security.MessageDigest
import java.util.Base64

/**
 * A parsed private key ready to hand to JSch.
 *
 * The original app accepted four input formats through `SSHKeyFen`:
 *   - OpenSSH new format (`-----BEGIN OPENSSH PRIVATE KEY-----`)
 *   - PKCS#1 / PKCS#8 PEM (`RSA PRIVATE KEY`, `PRIVATE KEY`, `EC PRIVATE KEY`)
 *   - PuTTY PPK v1/v2/v3 via `PPKConverter`
 *   - raw base64 blob pasted without headers
 *
 * Everything is normalised to PEM + optional passphrase, which is what JSch and
 * every other SSH library on Android expects.
 */
data class ParsedKey(
    /** RSA | ECDSA | ED25519 | DSA */
    val algorithm: String,
    val bits: Int,
    /** Normalised PEM text (PKCS#8 when we had to convert). */
    val pem: String,
    /** Passphrase kept in memory only, never persisted in this object's text form. */
    val passphrase: String?,
    /** OpenSSH single-line public key, when derivable from the private part. */
    val publicKeyOpenSsh: String?,
    /** `SHA256:...` fingerprint of the public key blob. */
    val fingerprintSha256: String?,
    /** Original input format, surfaced in the key detail screen. */
    val sourceFormat: SourceFormat,
) {
    enum class SourceFormat { OPENSSH, PKCS1, PKCS8, PPK1, PPK2, PPK3, RAW_BASE64, UNKNOWN }
}

sealed interface KeyParseResult {
    data class Success(val key: ParsedKey) : KeyParseResult

    /** Key is valid but encrypted; caller must re-submit with a passphrase. */
    data object NeedsPassphrase : KeyParseResult

    /** Passphrase was supplied but did not decrypt. */
    data object BadPassphrase : KeyParseResult

    data class Failure(val reason: String) : KeyParseResult
}

/**
 * Private-key format detection and conversion.
 *
 * This is a self-contained ASN.1/DER reader rather than a dependency because
 * BouncyCastle adds ~7 MB and JSch already handles the actual crypto. We only
 * need to (a) tell the user what the key is, (b) convert PPK to PEM, and
 * (c) derive the public key / fingerprint.
 */
object KeyParser {

    private const val BEGIN_OPENSSH = "-----BEGIN OPENSSH PRIVATE KEY-----"
    private const val BEGIN_RSA = "-----BEGIN RSA PRIVATE KEY-----"
    private const val BEGIN_PKCS8 = "-----BEGIN PRIVATE KEY-----"
    private const val BEGIN_PKCS8_ENC = "-----BEGIN ENCRYPTED PRIVATE KEY-----"
    private const val BEGIN_EC = "-----BEGIN EC PRIVATE KEY-----"
    private const val BEGIN_DSA = "-----BEGIN DSA PRIVATE KEY-----"

    /**
     * Parse key text.
     *
     * @param text the pasted or loaded key material
     * @param passphrase passphrase if the caller already has one
     */
    fun parse(text: String, passphrase: String? = null): KeyParseResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return KeyParseResult.Failure("private key is empty")

        return when {
            trimmed.startsWith(BEGIN_OPENSSH) -> parseOpenSsh(trimmed, passphrase)
            trimmed.startsWith(BEGIN_RSA) -> parsePemRsa(trimmed, passphrase)
            trimmed.startsWith(BEGIN_PKCS8) -> parsePemPkcs8(trimmed, passphrase)
            trimmed.startsWith(BEGIN_PKCS8_ENC) -> parsePemPkcs8Encrypted(trimmed, passphrase)
            trimmed.startsWith(BEGIN_EC) -> parsePemEc(trimmed, passphrase)
            trimmed.startsWith(BEGIN_DSA) -> parsePemDsa(trimmed, passphrase)
            trimmed.startsWith("PuTTY-User-Key-File") -> PpKConverter.convert(trimmed, passphrase)
            looksLikeBareBase64(trimmed) -> parseBareBase64(trimmed, passphrase)
            // An `authorized_keys` line is not base64 overall, so it never reaches
            // parseBareBase64. Naming what the user pasted is the whole point: sending
            // them back "unrecognised format" when the file is plainly a public key
            // leaves them guessing which of the two files in their .ssh folder to pick.
            looksLikeAuthorizedKeysLine(trimmed) ->
                KeyParseResult.Failure("this is a public key, not a private key")
            else -> KeyParseResult.Failure("unrecognised key format")
        }
    }

    // --- OpenSSH new format --------------------------------------------------

    /**
     * OpenSSH private keys are a base64 body starting with `openssh-key-v1\0`.
     *
     * Layout after the magic:
     *   string ciphername
     *   string kdfname
     *   string kdfoptions
     *   uint32 number_of_keys
     *   string publickey[n]
     *   string encrypted_privatekey
     */
    private fun parseOpenSsh(pem: String, passphrase: String?): KeyParseResult {
        val body = extractBody(pem, BEGIN_OPENSSH) ?: return KeyParseResult.Failure("malformed PEM body")
        val raw = decodeBase64(body)
            ?: return KeyParseResult.Failure("invalid base64 in OpenSSH key")

        val magic = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)
        if (raw.size < magic.size) return KeyParseResult.Failure("truncated OpenSSH key")
        for (i in magic.indices) {
            if (raw[i] != magic[i]) return KeyParseResult.Failure("missing openssh-key-v1 magic")
        }

        val reader = SshBinaryReader(raw, magic.size)
        val cipherName = reader.readString()?.toString(Charsets.US_ASCII)
            ?: return KeyParseResult.Failure("missing cipher name")
        val kdfName = reader.readString()?.toString(Charsets.US_ASCII)
            ?: return KeyParseResult.Failure("missing kdf name")
        val kdfOptions = reader.readString() ?: ByteArray(0)
        val keyCount = reader.readUInt32()?.toInt() ?: return KeyParseResult.Failure("missing key count")
        if (keyCount <= 0) return KeyParseResult.Failure("key file contains no keys")

        val publicBlob = reader.readString() ?: return KeyParseResult.Failure("missing public key blob")
        val encrypted = reader.readString() ?: return KeyParseResult.Failure("missing private key section")

        val encryptedFlag = cipherName != "none"

        if (encryptedFlag && passphrase.isNullOrEmpty()) return KeyParseResult.NeedsPassphrase

        // Public key facts are readable regardless of encryption.
        val (algo, bitsTotal) = describePublicBlob(publicBlob)
        val fingerprint = Hashes.sshFingerprint(publicBlob)

        // JSch understands OpenSSH format directly and takes the passphrase itself,
        // so we only validate here and pass the original PEM through.
        //
        // Validation is best-effort: OpenSSH keys default to bcrypt_pbkdf, which the
        // Android platform does not provide. When we cannot verify locally we let the
        // connection attempt decide, instead of rejecting a possibly-correct key.
        if (encryptedFlag) {
            val verdict = runCatching {
                AesCtrCipher.verifyOpenSshPassphrase(cipherName, kdfName, kdfOptions, encrypted, passphrase!!)
            }.getOrElse { AesCtrCipher.PassphraseVerdict.CannotVerify }
            if (verdict == AesCtrCipher.PassphraseVerdict.Wrong) return KeyParseResult.BadPassphrase
            return KeyParseResult.Success(
                ParsedKey(
                    algorithm = algo,
                    bits = bitsTotal,
                    pem = pem,
                    passphrase = passphrase,
                    publicKeyOpenSsh = openSshPublicLine(algo, publicBlob),
                    fingerprintSha256 = fingerprint,
                    sourceFormat = ParsedKey.SourceFormat.OPENSSH,
                ),
            )
        }

        return KeyParseResult.Success(
            ParsedKey(
                algorithm = algo,
                bits = bitsTotal,
                pem = pem,
                passphrase = null,
                publicKeyOpenSsh = openSshPublicLine(algo, publicBlob),
                fingerprintSha256 = fingerprint,
                sourceFormat = ParsedKey.SourceFormat.OPENSSH,
            ),
        )
    }

    // --- PEM variants --------------------------------------------------------

    private fun parsePemRsa(pem: String, passphrase: String?): KeyParseResult {
        val encrypted = pem.contains("Proc-Type: 4,ENCRYPTED", ignoreCase = true)
        if (encrypted && passphrase.isNullOrEmpty()) return KeyParseResult.NeedsPassphrase
        val bits = runCatching { derRsaBits(pem) }.getOrDefault(2048)
        return KeyParseResult.Success(
            ParsedKey(
                algorithm = "RSA",
                bits = bits,
                pem = pem,
                passphrase = passphrase?.takeIf { encrypted },
                publicKeyOpenSsh = null,
                fingerprintSha256 = null,
                sourceFormat = ParsedKey.SourceFormat.PKCS1,
            ),
        )
    }

    private fun parsePemPkcs8(pem: String, passphrase: String?): KeyParseResult {
        val (algo, bits) = runCatching { describePkcs8(pem) }.getOrDefault("RSA" to 2048)
        return KeyParseResult.Success(
            ParsedKey(
                algorithm = algo,
                bits = bits,
                pem = pem,
                passphrase = passphrase,
                publicKeyOpenSsh = null,
                fingerprintSha256 = null,
                sourceFormat = ParsedKey.SourceFormat.PKCS8,
            ),
        )
    }

    private fun parsePemPkcs8Encrypted(pem: String, passphrase: String?): KeyParseResult {
        if (passphrase.isNullOrEmpty()) return KeyParseResult.NeedsPassphrase
        return KeyParseResult.Success(
            ParsedKey(
                algorithm = "RSA",
                bits = 2048,
                pem = pem,
                passphrase = passphrase,
                publicKeyOpenSsh = null,
                fingerprintSha256 = null,
                sourceFormat = ParsedKey.SourceFormat.PKCS8,
            ),
        )
    }

    private fun parsePemEc(pem: String, passphrase: String?): KeyParseResult {
        val encrypted = pem.contains("Proc-Type: 4,ENCRYPTED", ignoreCase = true)
        if (encrypted && passphrase.isNullOrEmpty()) return KeyParseResult.NeedsPassphrase
        val bits = runCatching { derEcBits(pem) }.getOrDefault(256)
        return KeyParseResult.Success(
            ParsedKey(
                algorithm = "ECDSA",
                bits = bits,
                pem = pem,
                passphrase = passphrase?.takeIf { encrypted },
                publicKeyOpenSsh = null,
                fingerprintSha256 = null,
                sourceFormat = ParsedKey.SourceFormat.PKCS1,
            ),
        )
    }

    private fun parsePemDsa(pem: String, passphrase: String?): KeyParseResult =
        KeyParseResult.Success(
            ParsedKey(
                algorithm = "DSA",
                bits = 1024,
                pem = pem,
                passphrase = passphrase,
                publicKeyOpenSsh = null,
                fingerprintSha256 = null,
                sourceFormat = ParsedKey.SourceFormat.PKCS1,
            ),
        )

    // --- bare base64 ---------------------------------------------------------

    private fun looksLikeBareBase64(text: String): Boolean {
        val compact = text.replace("\n", "").replace("\r", "").replace(" ", "")
        if (compact.length < 64) return false
        return compact.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
    }

    /**
     * The app's "paste a key" flow accepted raw base64 (either a private key body
     * or an `authorized_keys` line). We detect the OpenSSH magic to tell them apart.
     */
    /** True for an `authorized_keys` line: `ssh-<algo> <base64> [comment]`. */
    private fun looksLikeAuthorizedKeysLine(text: String): Boolean {
        val fields = text.split(Regex("""\s+"""))
        if (fields.size < 2) return false
        val algo = fields[0]
        if (!algo.startsWith("ssh-") && !algo.startsWith("ecdsa-") && !algo.startsWith("sk-")) {
            return false
        }
        return decodeBase64(fields[1])?.isNotEmpty() == true
    }

    private fun parseBareBase64(text: String, passphrase: String?): KeyParseResult {
        val compact = text.trim().replace("\n", "").replace("\r", "").replace(" ", "")
        val raw = decodeBase64(compact)
            ?: return KeyParseResult.Failure("invalid base64")
        val magic = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)
        if (raw.size > magic.size) {
            var match = true
            for (i in magic.indices) if (raw[i] != magic[i]) { match = false; break }
            if (match) {
                val pem = buildString {
                    appendLine(BEGIN_OPENSSH)
                    appendLine(compact.chunked(70).joinToString("\n"))
                    appendLine("-----END OPENSSH PRIVATE KEY-----")
                }
                return parseOpenSsh(pem, passphrase)
            }
        }
        // Assume it was an authorized_keys line: `ssh-ed25519 AAAA... comment`
        val fields = text.trim().split(Regex("\\s+"))
        // A bare base64 blob is ambiguous, so classify it by what it actually is rather
        // than by the fact that it decoded. Claiming "this is a public key" for any
        // decodable blob sends the user looking for the wrong file.
        return if (fields.size >= 2 && fields[0].startsWith("ssh-")) {
            val blob = decodeBase64(fields[1])
            if (blob == null) {
                KeyParseResult.Failure("invalid public key base64")
            } else {
                KeyParseResult.Failure("this is a public key, not a private key")
            }
        } else {
            KeyParseResult.Failure("unrecognised key format: expected a private key in PEM or OpenSSH format")
        }
    }

    // --- helpers -------------------------------------------------------------

    /**
     * Decode base64 key material, returning null instead of throwing.
     *
     * Uses `java.util.Base64` rather than `android.util.Base64` for the same reason
     * [Hashes.sshFingerprint] does: the Android class is a stub under
     * `unitTests.isReturnDefaultValues`, where it returns null for every call. That made
     * the parser untestable off-device, and worse, it turned a malformed key into a
     * `NullPointerException` at `raw.size` instead of a [KeyParseResult.Failure]. API 26
     * (this app's minSdk) has `java.util.Base64`, so nothing is lost.
     *
     * Accepts MIME input because PEM bodies arrive wrapped, and tolerates the URL-safe
     * alphabet because pasted material sometimes comes from a URL context.
     */
    private fun decodeBase64(text: String): ByteArray? {
        if (text.isEmpty()) return null
        return runCatching { Base64.getMimeDecoder().decode(text) }
            .recoverCatching { Base64.getUrlDecoder().decode(text) }
            .getOrNull()
    }

    /** Encode without line breaks, as `authorized_keys` and fingerprints require. */
    private fun encodeBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun extractBody(pem: String, header: String): String? {
        val start = pem.indexOf(header)
        if (start < 0) return null
        val bodyStart = pem.indexOf('\n', start)
        if (bodyStart < 0) return null
        val end = pem.indexOf("-----END", bodyStart)
        if (end < 0) return null
        return pem.substring(bodyStart, end).replace("\n", "").replace("\r", "").trim()
    }

    private fun describePublicBlob(blob: ByteArray): Pair<String, Int> {
        val reader = SshBinaryReader(blob, 0)
        val type = reader.readString()?.toString(Charsets.US_ASCII) ?: return "UNKNOWN" to 0
        return when {
            type.contains("ed25519") -> "ED25519" to 256
            type.contains("ecdsa-sha2-nistp256") -> "ECDSA" to 256
            type.contains("ecdsa-sha2-nistp384") -> "ECDSA" to 384
            type.contains("ecdsa-sha2-nistp521") -> "ECDSA" to 521
            type.contains("rsa") -> {
                reader.readString() // e
                val n = reader.readString()
                val bits = n?.let { countBits(it) } ?: 2048
                "RSA" to bits
            }
            type.contains("dss") -> "DSA" to 1024
            else -> type.uppercase() to 0
        }
    }

    private fun countBits(bytes: ByteArray): Int {
        if (bytes.isEmpty()) return 0
        var bits = (bytes.size - 1) * 8
        var first = bytes[0].toInt() and 0xFF
        while (first > 0) {
            bits++
            first = first shr 1
        }
        return bits
    }

    private fun openSshPublicLine(algo: String, blob: ByteArray): String {
        val type = SshBinaryReader(blob, 0).readString()?.toString(Charsets.US_ASCII) ?: when (algo) {
            "ED25519" -> "ssh-ed25519"
            "ECDSA" -> "ecdsa-sha2-nistp256"
            "RSA" -> "ssh-rsa"
            else -> "ssh-rsa"
        }
        return "$type ${encodeBase64(blob)}"
    }

    // --- minimal DER introspection ------------------------------------------

    private fun derRsaBits(pem: String): Int {
        val der = pemDer(pem) ?: return 2048
        val seq = DerReader(der).readSequence() ?: return 2048
        val inner = DerReader(seq)
        inner.readInteger()                 // version
        val modulus = inner.readInteger() ?: return 2048
        return countBits(modulus)
    }

    private fun derEcBits(pem: String): Int {
        val der = pemDer(pem) ?: return 256
        // EC private keys embed a named curve OID; nistp256 is by far the most common.
        val text = der.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        return when {
            text.contains("2b81040022") -> 384  // secp384r1
            text.contains("2b81040023") -> 521  // secp521r1
            else -> 256
        }
    }

    private fun describePkcs8(pem: String): Pair<String, Int> {
        val der = pemDer(pem) ?: return "RSA" to 2048
        val text = der.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        return when {
            text.contains("2b6570") -> "ED25519" to 256
            text.contains("2a8648ce3d030107") -> "ECDSA" to 256
            text.contains("2b81040022") -> "ECDSA" to 384
            text.contains("2b81040023") -> "ECDSA" to 521
            else -> "RSA" to 2048
        }
    }

    private fun pemDer(pem: String): ByteArray? {
        val body = pem
            .replace(Regex("-----BEGIN [^-]+-----"), "")
            .replace(Regex("-----END [^-]+-----"), "")
            .replace(Regex("^[A-Za-z-]+:.*$", RegexOption.MULTILINE), "")
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")
            .trim()
        if (body.isEmpty()) return null
        return decodeBase64(body)
    }

    /**
     * SHA-256 fingerprint from a public key blob, in the exact form `ssh-keygen -lf`
     * prints: `SHA256:` + unpadded standard base64 of the digest.
     *
     * Delegates to [Hashes.sshFingerprint] so the parser and the host-key verifier cannot
     * drift apart on presentation; a user comparing a key fingerprint against a host
     * fingerprint should be reading the same function's output.
     */
    fun fingerprint(publicBlob: ByteArray): String = Hashes.sshFingerprint(publicBlob)
}
