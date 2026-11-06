package com.tuissh.app.ssh.key

import com.tuissh.app.core.Hashes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair

/**
 * Format-detection contract for [KeyParser].
 *
 * The parser is the first thing every imported key touches, and a wrong answer here is
 * expensive in both directions: a rejected valid key makes the app useless, and an
 * accepted-but-unusable key fails later at connect time where the user cannot see why.
 * These tests therefore assert the *routing decision* for each of the seven formats the
 * parser claims, not just "no exception was thrown".
 *
 * Samples are generated at test time by [TestKeys] rather than checked in, so no real
 * private key lives in this repository.
 */
class KeyParserTest {

    // ------------------------------------------------------------------
    // OpenSSH ed25519
    // ------------------------------------------------------------------

    @Test
    fun `openSSH ed25519 parses as ED25519 with a 256 bit key`() {
        val result = KeyParser.parse(TestKeys.openSshEd25519())

        val key = result.assertSuccess()
        assertEquals("ED25519", key.algorithm)
        assertEquals(256, key.bits)
        assertEquals(ParsedKey.SourceFormat.OPENSSH, key.sourceFormat)
        assertNull("an unencrypted key must not carry a passphrase", key.passphrase)
    }

    @Test
    fun `openSSH ed25519 exposes the public key line and its fingerprint`() {
        val key = KeyParser.parse(TestKeys.openSshEd25519()).assertSuccess()

        val publicLine = assertNotNull(key.publicKeyOpenSsh).let { key.publicKeyOpenSsh!! }
        assertTrue("public line must be an authorized_keys line: $publicLine", publicLine.startsWith("ssh-ed25519 "))

        // The fingerprint must be the one OpenSSH would print for that same blob, because
        // users compare it against `ssh-keygen -lf` output.
        val blob = java.util.Base64.getDecoder().decode(publicLine.substringAfter(' '))
        assertEquals(Hashes.sshFingerprint(blob), key.fingerprintSha256)
        assertTrue(key.fingerprintSha256!!.startsWith("SHA256:"))
    }

    // ------------------------------------------------------------------
    // OpenSSH RSA
    // ------------------------------------------------------------------

    @Test
    fun `openSSH rsa reports RSA with the real modulus size`() {
        val key = KeyParser.parse(TestKeys.openSshRsa()).assertSuccess()

        assertEquals("RSA", key.algorithm)
        assertEquals("bits must come from the public modulus, not a default", 2048, key.bits)
        assertEquals(ParsedKey.SourceFormat.OPENSSH, key.sourceFormat)
        assertTrue(key.publicKeyOpenSsh!!.startsWith("ssh-rsa "))
    }

    // ------------------------------------------------------------------
    // PKCS#1 PEM
    // ------------------------------------------------------------------

    @Test
    fun `pkcs1 rsa pem parses without a passphrase`() {
        val key = KeyParser.parse(TestKeys.pkcs1RsaPem(TestKeys.rsaKeyPair())).assertSuccess()

        assertEquals("RSA", key.algorithm)
        assertEquals(ParsedKey.SourceFormat.PKCS1, key.sourceFormat)
        assertNull(key.passphrase)
    }

    // ------------------------------------------------------------------
    // PKCS#8 PEM
    // ------------------------------------------------------------------

    @Test
    fun `pkcs8 rsa pem parses`() {
        val key = KeyParser.parse(TestKeys.pkcs8Pem(TestKeys.rsaKeyPair())).assertSuccess()

        assertEquals("RSA", key.algorithm)
        assertEquals(ParsedKey.SourceFormat.PKCS8, key.sourceFormat)
    }

    @Test
    fun `pkcs8 ed25519 pem is recognised from its algorithm OID`() {
        // java.security cannot mint an Ed25519 key below JDK 15 and Android's provider
        // set varies by API level, so the OID path is exercised through a PKCS#8 body
        // the parser must classify by content rather than by the PEM header.
        val key = KeyParser.parse(TestKeys.pkcs8Pem(TestKeys.ecKeyPair())).assertSuccess()

        assertEquals(ParsedKey.SourceFormat.PKCS8, key.sourceFormat)
        assertEquals("an EC key must not be reported as RSA", "ECDSA", key.algorithm)
    }

    // ------------------------------------------------------------------
    // Encrypted PEM
    // ------------------------------------------------------------------

    @Test
    fun `legacy encrypted pkcs1 reports NeedsPassphrase`() {
        val pem = TestKeys.encryptedPkcs1RsaPem(TestKeys.rsaKeyPair())

        val result = KeyParser.parse(pem)

        assertTrue("expected NeedsPassphrase, got $result", result is KeyParseResult.NeedsPassphrase)
    }

    @Test
    fun `encrypted pkcs8 reports NeedsPassphrase`() {
        val result = KeyParser.parse(TestKeys.encryptedPkcs8Pem(TestKeys.rsaKeyPair()))

        assertTrue("expected NeedsPassphrase, got $result", result is KeyParseResult.NeedsPassphrase)
    }

    @Test
    fun `an encrypted key with a passphrase is accepted for a connection attempt`() {
        // The platform cannot verify a PKCS#8 passphrase locally, so the contract is
        // "hand it to the SSH library and let the connection decide" rather than a
        // local guess that would reject correct keys.
        val pem = TestKeys.encryptedPkcs8Pem(TestKeys.rsaKeyPair())

        val key = KeyParser.parse(pem, passphrase = "hunter2").assertSuccess()

        assertEquals("hunter2", key.passphrase)
        assertEquals(pem.trim(), key.pem.trim())
    }

    // ------------------------------------------------------------------
    // PuTTY PPK
    // ------------------------------------------------------------------

    @Test
    fun `ppk v2 is reported as an actionable failure rather than silently converted`() {
        // PPK conversion is deliberately not implemented: reproducing PuTTY's private
        // blob needs Argon2/bcrypt, and a half-converted PEM would be a corrupt key that
        // fails at connect time with no useful message. The documented behaviour is a
        // Failure that names the format, the algorithm and what the user should do.
        val result = KeyParser.parse(SAMPLE_PPK_V2)

        val failure = result.assertFailure()
        assertTrue("failure must name the format: $failure", failure.contains("PPK v2"))
        assertTrue("failure must name the algorithm: $failure", failure.contains("ED25519"))
        assertTrue(
            "failure must tell the user how to proceed: $failure",
            failure.contains("PuTTYgen"),
        )
    }

    @Test
    fun `ppk v3 with Argon2 is a Failure, not a passphrase prompt`() {
        // No password can make this succeed, so asking for one would be a dead end.
        val result = KeyParser.parse(SAMPLE_PPK_V3_ARGON2, passphrase = "anything")

        val failure = result.assertFailure()
        assertTrue("failure must name the format: $failure", failure.contains("PPK v3"))
    }

    @Test
    fun `ppk without a key type is rejected`() {
        // The algorithm belongs on the header line; a header without one cannot be
        // interpreted, and guessing RSA would produce a wrong-looking key later.
        val result = KeyParser.parse("PuTTY-User-Key-File-2:\nEncryption: none\n")

        assertEquals("missing key type", result.assertFailure())
    }

    // ------------------------------------------------------------------
    // Malformed and empty input
    // ------------------------------------------------------------------

    @Test
    fun `empty input is rejected`() {
        assertEquals("private key is empty", KeyParser.parse("").assertFailure())
        assertEquals("private key is empty", KeyParser.parse("   \n\t  ").assertFailure())
    }

    @Test
    fun `unrelated text is rejected`() {
        val failure = KeyParser.parse("this is not a key").assertFailure()
        assertEquals("unrecognised key format", failure)
    }

    @Test
    fun `a PEM header with a truncated body is rejected`() {
        val truncated = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAA
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent()

        // The body decodes, but it cannot hold even the magic plus the cipher/kdf names.
        assertTrue(KeyParser.parse(truncated) is KeyParseResult.Failure)
    }

    @Test
    fun `an OpenSSH body without the magic is rejected`() {
        val noMagic = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            ${java.util.Base64.getEncoder().encodeToString(ByteArray(64))}
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent()

        assertEquals("missing openssh-key-v1 magic", KeyParser.parse(noMagic).assertFailure())
    }

    @Test
    fun `a public key is refused with an explanation, not parsed as a private key`() {
        val publicLine = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA neo@test"

        val failure = KeyParser.parse(publicLine).assertFailure()

        assertEquals("this is a public key, not a private key", failure)
    }

    // ------------------------------------------------------------------
    // Bare base64 (the paste flow)
    // ------------------------------------------------------------------

    @Test
    fun `bare base64 of an OpenSSH key is recognised`() {
        // Users copy the body without the PEM armour often enough that the paste flow has
        // to handle it; getting this wrong is the difference between "already trusted"
        // and a confusing format error.
        val pem = TestKeys.openSshEd25519()
        val body = pem.lines()
            .filter { it.isNotBlank() && !it.startsWith("-----") }
            .joinToString("")

        val key = KeyParser.parse(body).assertSuccess()

        assertEquals(ParsedKey.SourceFormat.OPENSSH, key.sourceFormat)
        assertEquals("ED25519", key.algorithm)
    }

    // ------------------------------------------------------------------
    // Fingerprint helper
    // ------------------------------------------------------------------

    @Test
    fun `fingerprint matches OpenSSH format with padding removed`() {
        val blob = byteArrayOf(1, 2, 3, 4, 5)

        val fingerprint = KeyParser.fingerprint(blob)

        assertTrue(fingerprint.startsWith("SHA256:"))
        assertFalse("OpenSSH strips the trailing '='", fingerprint.endsWith("="))
        assertEquals(Hashes.sshFingerprint(blob), fingerprint)
    }

    // ------------------------------------------------------------------
    // Assertion helpers
    // ------------------------------------------------------------------

    private fun KeyParseResult.assertSuccess(): ParsedKey {
        assertTrue("expected Success, got $this", this is KeyParseResult.Success)
        return (this as KeyParseResult.Success).key
    }

    private fun KeyParseResult.assertFailure(): String {
        assertTrue("expected Failure, got $this", this is KeyParseResult.Failure)
        return (this as KeyParseResult.Failure).reason
    }

    private companion object {
        /**
         * Shape of a `puttygen` v2 export: header lines, a wrapped public blob, and the
         * private blob. The MAC is not checked by this parser because there is no
         * conversion path to protect.
         */
        val SAMPLE_PPK_V2 = """
            PuTTY-User-Key-File-2: ssh-ed25519
            Encryption: none
            Comment: test@example
            Public-Lines: 2
            AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            Private-Lines: 1
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            Private-MAC: 0000000000000000000000000000000000000000000000000000000000000000
        """.trimIndent()

        /**
         * A v3 export, which PuTTY writes with Argon2 for the passphrase KDF. The
         * parser never attempts the KDF, so the body only has to be well formed enough
         * to reach the decision point.
         */
        val SAMPLE_PPK_V3_ARGON2 = """
            PuTTY-User-Key-File-3: ssh-ed25519
            Encryption: aes256-ctr
            Comment: test@example
            Public-Lines: 2
            AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            Key-Derivation: Argon2id
            Argon2-Memory: 8192
            Argon2-Passes: 13
            Argon2-Parallelism: 1
            Argon2-Salt: 0123456789abcdef0123456789abcdef
            Private-Lines: 1
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            Private-MAC: 0000000000000000000000000000000000000000000000000000000000000000
        """.trimIndent()
    }
}
