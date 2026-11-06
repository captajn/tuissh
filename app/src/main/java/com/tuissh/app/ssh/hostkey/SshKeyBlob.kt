package com.tuissh.app.ssh.hostkey

/**
 * Reads the algorithm name out of an SSH public-key blob.
 *
 * Kept here rather than in the transport package because the only thing that needs to
 * agree on the "real" key type is the trust store: JSch hands back the key-exchange
 * signature algorithm (`rsa-sha2-512`), while `known_hosts` and `ssh-keyscan` are keyed
 * by the name inside the blob (`ssh-rsa`). Mismatching those two makes the same host
 * look new every time the server renegotiates, so the parse lives next to its purpose.
 */
internal object SshKeyBlob {

    /**
     * Algorithm name at the start of [blob], or null when the blob is too short or
     * does not start with a printable `string`.
     *
     * Only the first length-prefixed string is read: everything after it is
     * algorithm-specific, and nothing here needs to understand it.
     */
    fun keyType(blob: ByteArray): String? {
        if (blob.size < 4) return null
        val length = ((blob[0].toInt() and 0xFF) shl 24) or
            ((blob[1].toInt() and 0xFF) shl 16) or
            ((blob[2].toInt() and 0xFF) shl 8) or
            (blob[3].toInt() and 0xFF)
        // A key type name is short and printable; a longer or non-positive length means
        // this is not a public-key blob at all.
        if (length !in 1..(blob.size - 4) || length > MAX_TYPE_LENGTH) return null
        val bytes = blob.copyOfRange(4, 4 + length)
        if (bytes.any { it < 0x21 || it > 0x7E }) return null
        return String(bytes, Charsets.US_ASCII)
    }

    /** Longest key type name worth accepting; `ecdsa-sha2-nistp256` is 19 characters. */
    private const val MAX_TYPE_LENGTH = 64
}
