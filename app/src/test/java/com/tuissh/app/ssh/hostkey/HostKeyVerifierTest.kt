package com.tuissh.app.ssh.hostkey

import com.tuissh.app.core.Hashes
import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoResult
import com.tuissh.app.data.dao.KnownHostDao
import com.tuissh.app.data.model.KnownHostEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Host-key fingerprint contract, written around the failure mode recorded as C12.
 *
 * The old implementation fingerprinted `host:port + algorithm label`. That scheme is
 * blind to the attack it exists to stop: an attacker who presents a different key of
 * the *same* algorithm produces an identical label, so the stored value still matched
 * and the connection was marked trusted. These tests fix the observable behaviour at the
 * key bytes, so the regression cannot come back quietly.
 */
class HostKeyVerifierTest {

    // ------------------------------------------------------------------
    // The C12 regression
    // ------------------------------------------------------------------

    @Test
    fun `two different keys of the same algorithm have different fingerprints`() {
        val first = ed25519Key(seed = 1)
        val second = ed25519Key(seed = 2)

        assertEquals("the samples must be the same algorithm", first.keyType, second.keyType)
        assertNotEquals(
            "a same-algorithm key swap must change the fingerprint (this is C12)",
            first.fingerprint,
            second.fingerprint,
        )
    }

    @Test
    fun `a same-algorithm key swap is reported as Changed`() = runBlocking {
        val store = FakeKnownHostDao()
        val verifier = HostKeyVerifier(store)
        val honest = ed25519Key(seed = 1)
        val attacker = ed25519Key(seed = 2)

        // First connect: user trusts the honest key.
        assertTrue(verifier.evaluate(HOST, PORT, honest) is HostKeyVerifier.Outcome.UnknownHost)
        verifier.trust(HOST, PORT, honest)

        val verdict = verifier.evaluate(HOST, PORT, attacker)

        assertTrue("same-algorithm swap must not be trusted, got $verdict", verdict is HostKeyVerifier.Outcome.Changed)
        val changed = verdict as HostKeyVerifier.Outcome.Changed
        assertEquals(honest.fingerprint, changed.previous.fingerprintSha256)
        assertEquals(attacker.fingerprint, changed.currentFingerprint)
        assertEquals(attacker.fingerprint, changed.error.currentSha256)
    }

    @Test
    fun `the same key yields the same fingerprint every time`() {
        // Determinism matters because the value is persisted: a fingerprint that drifted
        // between runs would report every host as changed on every connect.
        val key = ed25519Key(seed = 99)

        assertEquals(key.fingerprint, ed25519Key(seed = 99).fingerprint)
        assertEquals(key.fingerprint, key.fingerprint)
    }

    @Test
    fun `an unchanged key is trusted on the second connect`() = runBlocking {
        val verifier = HostKeyVerifier(FakeKnownHostDao())
        val key = ed25519Key(seed = 9)

        verifier.trust(HOST, PORT, key)

        assertEquals(HostKeyVerifier.Outcome.Trusted, verifier.evaluate(HOST, PORT, key))
    }

    @Test
    fun `trusting a key refreshes the last-seen timestamp`() = runBlocking {
        val store = FakeKnownHostDao()
        val verifier = HostKeyVerifier(store)
        val key = ed25519Key(seed = 3)
        verifier.trust(HOST, PORT, key)
        val before = store.touchCount

        verifier.evaluate(HOST, PORT, key)

        assertEquals(1, store.touchCount - before)
    }

    // ------------------------------------------------------------------
    // OpenSSH-compatible formatting
    // ------------------------------------------------------------------

    @Test
    fun `fingerprint is SHA256 over the blob in OpenSSH presentation`() {
        val key = ed25519Key(seed = 4)

        // The digest itself is provider-independent (java.security.MessageDigest), so the
        // expected value is computed with the JDK rather than through Hashes: the point of
        // the assertion is the *presentation*, which is what ssh-keygen prints.
        val expected = "SHA256:" + Base64.getEncoder()
            .encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(key.blob))
            .trimEnd('=')

        assertEquals(expected, key.fingerprint)
    }

    @Test
    fun `fingerprint has no base64 padding`() {
        // Padding must be stripped or the value will not match `ssh-keygen -lf`, which is
        // the entire point of showing it to the user.
        repeat(8) { seed ->
            assertTrue(ed25519Key(seed).fingerprint.none { it == '=' })
        }
    }

    @Test
    fun `fingerprint of the same blob differs from a digest of the label`() {
        // The old scheme hashed host:port + label. Two distinct keys of one algorithm
        // produced one value; now the blob decides, so the two never coincide.
        val key = ed25519Key(seed = 5)
        val labelHash = "SHA256:" + Base64.getEncoder()
            .encodeToString(Hashes.sha256("hostkey-label:v1\u0000$HOST:$PORT\u0000${key.keyType}".toByteArray()))
            .trimEnd('=')

        assertNotEquals(labelHash, key.fingerprint)
    }

    // ------------------------------------------------------------------
    // Backward compatibility with old label-hash rows
    // ------------------------------------------------------------------

    @Test
    fun `an old-format stored value is treated as unknown, not trusted`() = runBlocking {
        val store = FakeKnownHostDao()
        val key = ed25519Key(seed = 6)
        // What the previous release wrote: SHA256 over the label string.
        store.seed(
            KnownHostEntity(
                hostKey = key.storageKey(HOST, PORT),
                host = HOST,
                port = PORT,
                keyType = key.keyType,
                publicKeyBase64 = "",
                fingerprintSha256 = LABEL_HASH,
            ),
        )

        val verdict = HostKeyVerifier(store).evaluate(HOST, PORT, key)

        assertTrue(
            "a pre-C12 row must be re-confirmed, not silently trusted: $verdict",
            verdict is HostKeyVerifier.Outcome.Changed,
        )
    }

    @Test
    fun `an old-format stored value never compares equal to a new fingerprint`() {
        // `isBlobFingerprint` gates the equality check; a legacy value must fail the
        // gate even if it were somehow identical text, because it proves nothing.
        assertTrue(HostKeyVerifier.isBlobFingerprint("SHA256:abc"))
        assertTrue(!HostKeyVerifier.isBlobFingerprint(null))
        assertTrue(!HostKeyVerifier.isBlobFingerprint(""))
        assertTrue(!HostKeyVerifier.isBlobFingerprint("SHA256:"))
    }

    @Test
    fun `an old-format row is not shown to the user as if it were a key fingerprint`() = runBlocking {
        val store = FakeKnownHostDao()
        val key = ed25519Key(seed = 7)
        store.seed(
            KnownHostEntity(
                hostKey = key.storageKey(HOST, PORT),
                host = HOST,
                port = PORT,
                keyType = key.keyType,
                publicKeyBase64 = "",
                fingerprintSha256 = LABEL_HASH,
            ),
        )

        val changed = HostKeyVerifier(store).evaluate(HOST, PORT, key) as HostKeyVerifier.Outcome.Changed

        assertNotEquals("the stale label hash must not be presented as a previous key", LABEL_HASH, changed.error.previousSha256)
        assertTrue(changed.error.previousSha256.isNotBlank())
    }

    @Test
    fun `re-trusting after a legacy row upgrades it to a real fingerprint`() = runBlocking {
        val store = FakeKnownHostDao()
        val verifier = HostKeyVerifier(store)
        val key = ed25519Key(seed = 8)
        store.seed(
            KnownHostEntity(
                hostKey = key.storageKey(HOST, PORT),
                host = HOST,
                port = PORT,
                keyType = key.keyType,
                publicKeyBase64 = "",
                fingerprintSha256 = LABEL_HASH,
            ),
        )

        verifier.trust(HOST, PORT, key)

        assertEquals(key.fingerprint, store.findByHostKey(key.storageKey(HOST, PORT))?.fingerprintSha256)
        assertEquals(HostKeyVerifier.Outcome.Trusted, verifier.evaluate(HOST, PORT, key))
    }

    // ------------------------------------------------------------------
    // Per-host, per-algorithm keying
    // ------------------------------------------------------------------

    @Test
    fun `a server offering two algorithms keeps both trusted`() = runBlocking {
        // A server legitimately presents ed25519 or RSA depending on negotiation. Keying
        // by host:port alone would make each connection look like a key change.
        val verifier = HostKeyVerifier(FakeKnownHostDao())
        val ed = ed25519Key(seed = 10)
        val rsa = HostKeyMaterial("ssh-rsa", rsaBlob(modulus = 0x11))

        verifier.trust(HOST, PORT, ed)
        verifier.trust(HOST, PORT, rsa)

        assertEquals(HostKeyVerifier.Outcome.Trusted, verifier.evaluate(HOST, PORT, ed))
        assertEquals(HostKeyVerifier.Outcome.Trusted, verifier.evaluate(HOST, PORT, rsa))
    }

    @Test
    fun `the same key on a different port is a different host entry`() = runBlocking {
        val verifier = HostKeyVerifier(FakeKnownHostDao())
        val key = ed25519Key(seed = 11)
        verifier.trust(HOST, PORT, key)

        val otherPort = verifier.evaluate(HOST, PORT + 1, key)

        assertTrue("a tunnel on another port must be confirmed separately: $otherPort", otherPort is HostKeyVerifier.Outcome.UnknownHost)
    }

    @Test
    fun `host comparison is case insensitive`() = runBlocking {
        val verifier = HostKeyVerifier(FakeKnownHostDao())
        val key = ed25519Key(seed = 12)
        verifier.trust(HOST, PORT, key)

        assertEquals(HostKeyVerifier.Outcome.Trusted, verifier.evaluate(HOST.uppercase(), PORT, key))
    }

    // ------------------------------------------------------------------
    // Missing blob
    // ------------------------------------------------------------------

    @Test
    fun `a key without a blob is never trusted`() = runBlocking {
        // If the transport cannot hand over the key bytes, nothing can be verified. The
        // safe answer is to ask, never to accept.
        val verifier = HostKeyVerifier(FakeKnownHostDao())

        val outcome = verifier.evaluateUnverifiable(HOST, PORT, "ssh-ed25519")

        assertTrue(outcome is HostKeyVerifier.Outcome.UnknownHost)
        assertEquals(HostKeyVerifier.UNVERIFIABLE_FINGERPRINT, (outcome as HostKeyVerifier.Outcome.UnknownHost).fingerprint)
    }

    @Test
    fun `trust persists the blob so it can be shown without reconnecting`() = runBlocking {
        val store = FakeKnownHostDao()
        val key = ed25519Key(seed = 13)

        HostKeyVerifier(store).trust(HOST, PORT, key)

        val stored = store.findByHostKey(key.storageKey(HOST, PORT))!!
        assertEquals(Base64.getEncoder().encodeToString(key.blob), stored.publicKeyBase64)
        assertEquals(key.keyType, stored.keyType)
    }

    @Test
    fun `trust failure is reported as a typed error`() = runBlocking {
        val verifier = HostKeyVerifier(FakeKnownHostDao(failOnUpsert = true))

        val result = verifier.trust(HOST, PORT, ed25519Key(seed = 14))

        assertTrue(result is NeoResult.Err)
        assertTrue((result as NeoResult.Err).error is NeoError.Io)
    }

    // ------------------------------------------------------------------
    // Blob type extraction
    // ------------------------------------------------------------------

    @Test
    fun `key type is read from the blob, not from the negotiated signature algorithm`() {
        // JSch reports `rsa-sha2-512` for the rsa-sha2 extension while the blob still
        // says `ssh-rsa`. Keying storage by the former would make every server with the
        // extension look like it changed keys.
        val blob = rsaBlob(modulus = 0x22)

        assertEquals("ssh-rsa", SshKeyBlob.keyType(blob))
    }

    @Test
    fun `material holds the type and the blob in that order, and the fingerprint follows the blob`() {
        // Guards the value-object contract itself: an argument pair swapped between
        // String and ByteArray would otherwise compile in positional form and produce a
        // fingerprint over the wrong bytes, which is silently the worst possible outcome.
        val blob = TestBlobs.ed25519(seed = 21)

        val material = HostKeyMaterial(keyType = "ssh-ed25519", blob = blob)

        assertEquals("ssh-ed25519", material.keyType)
        assertTrue("the blob must be the bytes we passed in", material.blob.contentEquals(blob))
        assertEquals(Hashes.sshFingerprint(blob), material.fingerprint)
        assertEquals(HOST.lowercase() + ":" + PORT + ":ssh-ed25519", material.storageKey(HOST, PORT))
    }

    @Test
    fun `a non-blob is rejected as a key type source`() {
        assertTrue(SshKeyBlob.keyType(ByteArray(0)) == null)
        assertTrue(SshKeyBlob.keyType(byteArrayOf(0, 0, 0, 100)) == null)
        assertTrue(SshKeyBlob.keyType(ByteArray(32)) == null)
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun ed25519Key(seed: Int): HostKeyMaterial = HostKeyMaterial(
        keyType = "ssh-ed25519",
        blob = TestBlobs.ed25519(seed),
    )

    private fun rsaBlob(modulus: Int): ByteArray = TestBlobs.rsa(modulus)

    /**
     * In-memory [KnownHostDao].
     *
     * A Room database cannot run on the JVM, and the behaviour under test is the
     * verifier's decisions, not SQLite's. The fake keeps the one query the verifier uses
     * (`findByHostKey`) and records touches so the "already seen" path is observable.
     */
    private class FakeKnownHostDao(
        private val failOnUpsert: Boolean = false,
    ) : KnownHostDao {

        private val rows = mutableMapOf<String, KnownHostEntity>()
        var touchCount: Int = 0
            private set

        fun seed(entity: KnownHostEntity) {
            rows[entity.hostKey] = entity
        }

        override fun observeAll(): Flow<List<KnownHostEntity>> = flow { emit(rows.values.toList()) }

        override suspend fun findByHostKey(hostKey: String): KnownHostEntity? = rows[hostKey]

        override suspend fun findAll(): List<KnownHostEntity> = rows.values.toList()

        override suspend fun upsert(entry: KnownHostEntity): Long {
            if (failOnUpsert) error("disk full")
            rows[entry.hostKey] = entry
            return entry.id
        }

        override suspend fun touch(id: Long, at: Long) {
            touchCount++
        }

        override suspend fun deleteById(id: Long) {
            rows.entries.removeIf { it.value.id == id }
        }

        override suspend fun clear() {
            rows.clear()
        }
    }

    private companion object {
        const val HOST = "example.com"
        const val PORT = 22

        /** Representative of what the old `fingerprintForLabel` wrote. */
        const val LABEL_HASH = "SHA256:q83vMjCk3lq0UZ0h8dR0Yy3E0Jm8bWcP4pQe8jT3wZk"
    }
}

/** Wire-format public key blobs for tests, built without any crypto provider. */
internal object TestBlobs {

    /** `string "ssh-ed25519" || string key(32)`, with the seed byte spread deterministically. */
    fun ed25519(seed: Int): ByteArray {
        val body = ByteArray(32) { ((seed * 31 + it) and 0xFF).toByte() }
        return concat(string("ssh-ed25519"), string(body))
    }

    /** `string "ssh-rsa" || mpint e(65537) || mpint n`, where n starts with [modulus]. */
    fun rsa(modulus: Int): ByteArray {
        val exponent = byteArrayOf(0x01, 0x00, 0x01)
        val n = ByteArray(256) { if (it == 0) modulus.toByte() else 0x42 }
        return concat(string("ssh-rsa"), string(exponent), string(n))
    }

    private fun string(value: String): ByteArray = string(value.toByteArray(Charsets.US_ASCII))

    private fun string(value: ByteArray): ByteArray = concat(uint32(value.size), value)

    private fun uint32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var offset = 0
        for (part in parts) {
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }
}
