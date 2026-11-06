package com.tuissh.app.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Thread-safe boolean with the same shape as `NeoSwiftLib/AtomicBool.swift`. */
class AtomicBool(initial: Boolean = false) {

    private val ref = AtomicBoolean(initial)

    var value: Boolean
        get() = ref.get()
        set(v) {
            ref.set(v)
        }

    /** Set and return the previous value. */
    fun getAndSet(v: Boolean): Boolean = ref.getAndSet(v)

    /** True when the value was false and is now true. */
    fun compareAndSet(expect: Boolean, update: Boolean): Boolean = ref.compareAndSet(expect, update)
}

/** Small hashing helpers used by known-hosts, transfer verification and caching. */
object Hashes {

    private val HEX = "0123456789abcdef".toCharArray()

    fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha256Hex(bytes: ByteArray): String = toHex(sha256(bytes))

    fun md5Hex(bytes: ByteArray): String =
        toHex(MessageDigest.getInstance("MD5").digest(bytes))

    fun sha1Hex(bytes: ByteArray): String =
        toHex(MessageDigest.getInstance("SHA-1").digest(bytes))

    fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }

    fun fromHex(hex: String): ByteArray {
        val cleaned = hex.filter { it.isLetterOrDigit() }
        require(cleaned.length % 2 == 0) { "odd hex length" }
        return ByteArray(cleaned.length / 2) {
            cleaned.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * OpenSSH-style host key fingerprint: `SHA256:<base64-without-padding>`.
     *
     * Uses `java.util.Base64`, not `android.util.Base64`. The Android class is a stub
     * in JVM unit tests (the project sets `unitTests.isReturnDefaultValues = true`, so
     * it returns null), which turned every fingerprint into the literal string
     * "SHA256:null" and made host-key verification untestable. `java.util.Base64`
     * exists since API 26, which is this app's minSdk, so the switch costs nothing and
     * makes the security-critical path verifiable off-device.
     */
    fun sshFingerprint(rawKeyBlob: ByteArray): String {
        val digest = sha256(rawKeyBlob)
        val b64 = java.util.Base64.getEncoder().withoutPadding().encodeToString(digest)
        return "SHA256:$b64"
    }

    private val random = SecureRandom()

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }

    fun randomHex(count: Int): String = toHex(randomBytes(count))

    /** Constant-time comparison, used for MAC / checksum verification. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}

/** Port-range parsing shared by Mosh UDP port config and port forwarding. */
object PortRange {

    data class Range(val start: Int, val end: Int) {
        val count: Int get() = end - start + 1
        fun contains(port: Int) = port in start..end
    }

    /**
     * Accepts `60000`, `60000-61000`, `60000:61000` or `60000,60001`.
     * Returns null when the text is not a valid port or range inside 1..65535.
     */
    fun parse(text: String): Range? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (t.contains(',') || t.contains(' ')) {
            val ports = t.split(',', ' ').mapNotNull { it.trim().toIntOrNull() }
            if (ports.isEmpty()) return null
            if (ports.any { it !in 1..65535 }) return null
            return Range(ports.min(), ports.max())
        }
        val sep = when {
            t.contains('-') -> '-'
            t.contains(':') -> ':'
            else -> null
        }
        return if (sep == null) {
            val p = t.toIntOrNull() ?: return null
            if (p !in 1..65535) null else Range(p, p)
        } else {
            val parts = t.split(sep)
            if (parts.size != 2) return null
            val a = parts[0].trim().toIntOrNull() ?: return null
            val b = parts[1].trim().toIntOrNull() ?: return null
            if (a !in 1..65535 || b !in 1..65535 || a > b) null else Range(a, b)
        }
    }

    fun isValidPort(text: String): Boolean = parse(text) != null

    fun format(range: Range): String =
        if (range.start == range.end) range.start.toString() else "${range.start}-${range.end}"
}

/** Helper for the `1234 KB` style "first N bytes" preview truncation. */
object Truncate {

    fun text(value: String, max: Int, suffix: String = "…"): String =
        if (value.length <= max) value else value.take(max) + suffix

    fun middle(value: String, max: Int): String {
        if (value.length <= max) return value
        val keep = (max - 1) / 2
        return value.take(keep) + "…" + value.takeLast(keep)
    }

    /** Stable lowercase key for case-insensitive host maps. */
    fun hostKey(host: String, port: Int): String =
        "${host.lowercase(Locale.US)}:$port"
}
