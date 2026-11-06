package com.tuissh.app.ssh.hostkey

import com.tuissh.app.core.Hashes
import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoResult
import com.tuissh.app.core.Truncate
import com.tuissh.app.data.dao.KnownHostDao
import com.tuissh.app.data.model.KnownHostEntity

/**
 * A server host key exactly as it arrived over the wire.
 *
 * The SSH handshake advertises *two* things that look like a "key type" and are easy
 * to confuse:
 *   - the negotiated key-exchange signature algorithm, e.g. `rsa-sha2-256`, which the
 *     JSch wrapper reports via `session.hostKey.type`;
 *   - the algorithm name embedded in the public-key blob itself, e.g. `ssh-rsa`.
 *
 * OpenSSH keys these apart (`known_hosts` is keyed by blob type, `ssh-keygen -lf`
 * prints the blob type), and so does this type: [keyType] is the blob type and is the
 * only value used for the storage key. Using the signature algorithm as the storage key
 * would make the same host appear to change its key whenever a server or client changed
 * its rsa-sha2 negotiation.
 *
 * @property keyType algorithm name inside the blob, e.g. `ssh-ed25519`
 * @property blob the wire-format public key: `string keyType` followed by the
 *   algorithm-specific fields. This is the byte string a fingerprint is defined over,
 *   so it is the only thing that can distinguish two keys of the same algorithm.
 */
data class HostKeyMaterial(
    val keyType: String,
    val blob: ByteArray,
) {
    /** OpenSSH `SHA256:` fingerprint of [blob]; comparable with `ssh-keygen -lf`. */
    val fingerprint: String
        get() = Hashes.sshFingerprint(blob)

    /**
     * The `known_hosts`-style entry name for this key: `host:port:blobType`.
     *
     * The blob type is appended because one server may legitimately offer several
     * key algorithms; without it, trusting the ed25519 key would make the RSA key
     * look like a changed key on the next negotiation. Port is always included so a
     * container forwarding host port 2222 to a different machine is not conflated
     * with the host's real sshd on 22.
     */
    fun storageKey(host: String, port: Int): String =
        "${Truncate.hostKey(host, port)}:$keyType"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HostKeyMaterial) return false
        return keyType == other.keyType && blob.contentEquals(other.blob)
    }

    override fun hashCode(): Int = 31 * keyType.hashCode() + blob.contentHashCode()

    /** Never prints the blob: these objects end up in logs and in failure messages. */
    override fun toString(): String = "HostKeyMaterial($keyType, $fingerprint)"
}

/**
 * Contract the SSH session needs from host-key verification.
 *
 * The security property lives in the method signature: a trust decision is only
 * meaningful when it is made about the *key bytes* the server actually presented.
 * An earlier version of this interface accepted only `(host, port, keyType)` and
 * therefore could not see a same-algorithm key swap at all (tracked as C12).
 */
interface HostTrustStore {

    /**
     * Decide whether [material] is a key this user has already trusted.
     *
     * Returns [HostKeyVerifier.Outcome.UnknownHost] — not `Trusted` — for a stored
     * value that predates the blob-based scheme, because such a value proves nothing
     * about the key currently being presented.
     */
    suspend fun evaluate(host: String, port: Int, material: HostKeyMaterial): HostKeyVerifier.Outcome

    /** Persist trust for [material], replacing any previous key for the same host/port/algorithm. */
    suspend fun trust(host: String, port: Int, material: HostKeyMaterial): NeoResult<Unit>

    /**
     * Decide what to do when the transport exposed no key blob at all.
     *
     * A key we cannot fingerprint cannot be checked against anything, so the only safe
     * answer is "unknown host, ask the user". Implementations must not return
     * `Trusted` here: doing so would accept a silent man-in-the-middle at the moment
     * the library fails to expose the one value the check depends on.
     *
     * This is a default method so an in-memory test double can implement the blob-based
     * API alone; production behaviour is overridden by [HostKeyVerifier].
     */
    suspend fun evaluateUnverifiable(host: String, port: Int, keyType: String): HostKeyVerifier.Outcome =
        HostKeyVerifier.Outcome.UnknownHost(host, port, keyType, HostKeyVerifier.UNVERIFIABLE_FINGERPRINT)

    /**
     * @deprecated Verification without the key bytes cannot detect a same-algorithm key
     * swap (C12). Call [evaluate] with the blob; this shim exists only so that
     * label-only call sites keep compiling while they are migrated.
     */
    @Deprecated("Pass the host key blob to evaluate(); a label cannot identify a key.")
    suspend fun evaluateLabel(host: String, port: Int, keyType: String): HostKeyVerifier.Outcome =
        evaluateUnverifiable(host, port, keyType)

    /**
     * @deprecated Trusting without the key bytes stores nothing that can be verified
     * later. Call [trust] with the blob.
     */
    @Deprecated("Pass the host key blob to trust(); a label cannot identify a key.")
    suspend fun trustLabel(host: String, port: Int, keyType: String): NeoResult<Unit> =
        NeoResult.Err(NeoError.Io("refusing to trust host key without its blob"))
}

/**
 * Trust-on-first-use host key verification.
 *
 * Mirrors `KnownHostVerificationManager` / `KnownHostStore` and the
 * `KnownHostChangeRequest` alert flow.
 *
 * ## What is stored
 *
 * `known_hosts.fingerprintSha256` holds the OpenSSH fingerprint `SHA256:` +
 * base64(sha256(key blob)) without padding, i.e. exactly what `ssh-keygen -lf` and
 * `ssh-keyscan` print. Because the digest covers the blob rather than the algorithm
 * label, two different keys of the same algorithm fingerprint differently and a
 * substitution is detected. A label hash could only ever notice an algorithm change.
 *
 * ## Backward compatibility
 *
 * Values written by the old label-hashing code do not start with `SHA256:`. They are
 * treated as [Outcome.UnknownHost] rather than as a match or as a hard failure:
 *   - treating them as a match would keep the C12 hole open forever, because anything
 *     stored under the old scheme compares equal to a label, not to a key;
 *   - treating them as a mismatch would raise a "host key changed" alarm that is
 *     indistinguishable from a real man-in-the-middle, training users to click through
 *     the one dialog that matters.
 * Re-prompting costs the user one confirmation per host, once.
 */
class HostKeyVerifier(
    private val knownHostDao: KnownHostDao,
) : HostTrustStore {

    sealed interface Outcome {
        data object Trusted : Outcome

        data class UnknownHost(
            val host: String,
            val port: Int,
            val keyType: String,
            val fingerprint: String,
        ) : Outcome

        data class Changed(
            val host: String,
            val port: Int,
            val previous: KnownHostEntity,
            val currentFingerprint: String,
            val error: NeoError.HostKeyChanged,
        ) : Outcome
    }

    /**
     * Evaluate the host key that `connect()` negotiated.
     *
     * A stored row that holds a pre-C12 label hash is reported as [Outcome.Changed] with
     * [displayableStored] hiding the meaningless old value, so the user re-confirms the
     * real key once. It is never reported as `Trusted`.
     *
     * @param material the key blob plus its algorithm, captured during the handshake
     */
    override suspend fun evaluate(
        host: String,
        port: Int,
        material: HostKeyMaterial,
    ): Outcome {
        val fingerprint = material.fingerprint
        val stored = knownHostDao.findByHostKey(material.storageKey(host, port))
            ?: return Outcome.UnknownHost(host, port, material.keyType, fingerprint)

        return when {
            isVerifiableRow(stored) && stored.fingerprintSha256 == fingerprint -> {
                knownHostDao.touch(stored.id)
                Outcome.Trusted
            }
            // Either the key really changed, or the row predates blob fingerprints.
            // Both must be re-confirmed by the user; they differ only in the wording
            // and in whether the old value can honestly be shown as a previous key.
            else -> Outcome.Changed(
                host = host,
                port = port,
                previous = stored,
                currentFingerprint = fingerprint,
                error = NeoError.HostKeyChanged(
                    host = "$host:$port",
                    previousSha256 = displayableStored(stored),
                    currentSha256 = fingerprint,
                ),
            )
        }
    }

    /**
     * A key whose blob never reached us.
     *
     * There is nothing to compare, so the answer is always "unknown": the user is
     * asked about this connection rather than the check being skipped.
     */
    override suspend fun evaluateUnverifiable(
        host: String,
        port: Int,
        keyType: String,
    ): Outcome = Outcome.UnknownHost(host, port, keyType, UNVERIFIABLE_FINGERPRINT)

    /**
     * Persist trust for the key just presented.
     *
     * The row also keeps the base64 blob in `publicKeyBase64` so a future
     * "show me what I trusted" screen does not have to reconnect to display it.
     */
    override suspend fun trust(
        host: String,
        port: Int,
        material: HostKeyMaterial,
    ): NeoResult<Unit> = try {
        knownHostDao.upsert(
            KnownHostEntity(
                hostKey = material.storageKey(host, port),
                host = host,
                port = port,
                keyType = material.keyType,
                publicKeyBase64 = encodeBlob(material.blob),
                fingerprintSha256 = material.fingerprint,
            ),
        )
        NeoResult.Ok(Unit)
    } catch (e: Exception) {
        NeoResult.Err(NeoError.Io("trust host key: ${e.message}"))
    }

    /**
     * Evaluate a key when only its blob is known.
     *
     * Convenience for callers that already hold an `authorized_keys` blob (key import,
     * tests) and would otherwise have to hand-build [HostKeyMaterial].
     */
    suspend fun evaluateBlob(
        host: String,
        port: Int,
        keyType: String,
        blob: ByteArray,
    ): Outcome = evaluate(host, port, HostKeyMaterial(keyType, blob))

    /** Drop the trusted key for this host and port. */
    suspend fun forget(host: String, port: Int): NeoResult<Unit> = try {
        knownHostDao.findAll()
            .filter { it.host.equals(host, ignoreCase = true) && it.port == port }
            .forEach { knownHostDao.deleteById(it.id) }
        NeoResult.Ok(Unit)
    } catch (e: Exception) {
        NeoResult.Err(NeoError.Io("forget host key: ${e.message}"))
    }

    companion object {
        /** OpenSSH's own prefix; anything else in the column is a pre-C12 value. */
        const val FINGERPRINT_PREFIX = "SHA256:"

        /**
         * Shown when there is no key material to fingerprint.
         *
         * Deliberately not a `SHA256:` value: it must never compare equal to a stored
         * fingerprint, and the user should see that the app could not compute one.
         */
        const val UNVERIFIABLE_FINGERPRINT = "(unavailable: no host key blob)"

        /**
         * True when this column value is a real key fingerprint.
         *
         * The old code could write `SHA256:...` too — it hashed a label and used the
         * same prefix — so the prefix alone cannot be the discriminator. What it can
         * never be is a label *hash*, so the prefix is still the only cheap signal
         * available; a stale label hash that happens to collide with the new value is
         * not a realistic outcome of a collision-resistant digest over different data.
         */
        fun isBlobFingerprint(stored: String?): Boolean =
            stored != null && stored.startsWith(FINGERPRINT_PREFIX) && stored.length > FINGERPRINT_PREFIX.length

        /**
         * What to show the user for a stored value.
         *
         * A pre-C12 label hash is meaningless next to a real fingerprint, so it is
         * replaced with "unknown" instead of being displayed as if it were a key print.
         */
        fun displayableStored(stored: KnownHostEntity): String =
            if (isVerifiableRow(stored)) stored.fingerprintSha256 else UNKNOWN_PREVIOUS

        /**
         * True when a stored row carries a fingerprint that can actually be compared.
         *
         * The prefix alone is not enough: the pre-C12 code also wrote `SHA256:` values,
         * they just hashed the algorithm label instead of the key bytes. The reliable
         * discriminator is the blob column — the old code never stored one, so a row with
         * an empty `publicKeyBase64` predates blob fingerprints no matter what its
         * fingerprint column looks like.
         */
        fun isVerifiableRow(row: KnownHostEntity): Boolean =
            isBlobFingerprint(row.fingerprintSha256) && row.publicKeyBase64.isNotEmpty()

        private const val UNKNOWN_PREVIOUS = "(no key fingerprint recorded for this host)"

        // java.util.Base64, not android.util.Base64: the Android class is a stub under
        // JVM unit tests (unitTests.isReturnDefaultValues = true) and returns null, which
        // silently stored an empty public key and made the trusted-key screen useless.
        private fun encodeBlob(blob: ByteArray): String =
            java.util.Base64.getEncoder().encodeToString(blob)
    }
}
