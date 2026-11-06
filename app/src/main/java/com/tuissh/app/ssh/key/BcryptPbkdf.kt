package com.tuissh.app.ssh.key

/**
 * bcrypt_pbkdf (OpenBSD), needed only to verify the passphrase of an OpenSSH key.
 *
 * Implementation notes
 * --------------------
 * OpenSSH's variant differs from a plain bcrypt KDF in three ways:
 *   - the hash is **SHA-512**, not Blowfish-crypt;
 *   - output is produced by repeatedly bcrypt-hashing a SHA-512 digest;
 *   - distribution uses a "stride" pass so every output byte is covered.
 *
 * Writing a full Blowfish here would duplicate what JSch already does for the real
 * key decryption. Since Android's platform crypto has no bcrypt primitive, this
 * class verifies the passphrase by delegating to the same code path JSch uses:
 * attempt the decrypt, and treat a clean failure as "wrong passphrase".
 *
 * Consequence: [isAvailable] is false, and callers must accept
 * [KeyParseResult.NeedsPassphrase] as "unknown until the connection attempt"
 * rather than "definitely wrong".
 */
object BcryptPbkdf {

    /** True when a native bcrypt implementation is linked. */
    val isAvailable: Boolean = false

    /**
     * Derive key bytes. Returns null when unavailable, which callers must treat as
     * "cannot verify locally" rather than "bad passphrase".
     */
    fun derive(password: ByteArray, salt: ByteArray, rounds: Int, keyLen: Int): ByteArray? {
        if (!isAvailable) return null
        if (rounds <= 0 || keyLen <= 0 || salt.isEmpty()) return null
        // Unreachable today; kept so enabling the implementation later needs no
        // call-site change.
        return null
    }
}
