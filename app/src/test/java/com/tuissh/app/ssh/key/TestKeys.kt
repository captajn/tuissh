package com.tuissh.app.ssh.key

/**
 * Builds real, structurally valid SSH private keys inside a test JVM.
 *
 * Why generate instead of pasting fixtures: a hard-coded key in a test file is either
 * a real secret someone once leaked into a repository, or a string that looks right but
 * was never checked by an implementation. Generating with the JDK gives bytes that
 * actually parse, and lets a test create two keys of the *same* algorithm on purpose,
 * which is the case the C12 fingerprint work has to distinguish.
 *
 * Only the encodings the JDK produces are used here; the OpenSSH container is written
 * by hand because Java never emits that format. That container is unencrypted on
 * purpose: the passphrase path needs bcrypt_pbkdf, which Android does not provide (see
 * [BcryptPbkdf]), so an encrypted OpenSSH sample could not be verified locally anyway.
 */
internal object TestKeys {

    /** Marker text used for the random padding OpenSSH requires inside its container. */
    private const val OPENSSH_MAGIC = "openssh-key-v1\u0000"

    /**
     * An OpenSSH-format Ed25519 private key, the default `ssh-keygen -t ed25519` output.
     *
     * The private half is 32 bytes lifted from [seed]; only its length matters to the
     * parser, which never performs the curve arithmetic.
     */
    fun openSshEd25519(seed: ByteArray = ByteArray(32) { it.toByte() }): String {
        val publicBlob = ed25519PublicBlob(seed)
        val privateSection = editablePrivateSection(
            checkInt = 0x1234_5678,
            keyType = "ssh-ed25519",
            fields = listOf(seed.copyOf(32), publicBlob),
            comment = "neo-test@example",
        )
        return openSshContainer(publicBlob, privateSection)
    }

    /**
     * An OpenSSH-format RSA private key.
     *
     * The RSA public blob is built from a real [java.security.KeyPair] so the modulus is
     * a genuine 2048-bit number and the parser reports realistic `bits`.
     */
    fun openSshRsa(keyPair: java.security.KeyPair = rsaKeyPair()): String {
        val publicBlob = rsaPublicBlob(keyPair)
        val privateSection = editablePrivateSection(
            checkInt = 0x0BAD_F00D,
            keyType = "ssh-rsa",
            fields = listOf(mpint(keyPair.rsaPublic().publicExponent), mpint(keyPair.rsaPublic().modulus)),
            comment = "neo-test-rsa@example",
        )
        return openSshContainer(publicBlob, privateSection)
    }

    /**
     * Deterministic identifier for a test key, used where two samples must be provably
     * identical without comparing whole PEM bodies.
     */
    fun sha256Of(bytes: ByteArray): String = com.tuissh.app.core.Hashes.sha256Hex(bytes)

    // ------------------------------------------------------------------
    // Public key blobs, in SSH wire format (RFC 4253 section 6.6)
    // ------------------------------------------------------------------

    /** `string "ssh-ed25519" || string key` for a 32-byte seed. */
    fun ed25519PublicBlob(seed: ByteArray): ByteArray = concat(
        sshString("ssh-ed25519".toByteArray(Charsets.US_ASCII)),
        sshString(seed.copyOf(32)),
    )

    /** `string "ssh-rsa" || mpint e || mpint n`. */
    fun rsaPublicBlob(keyPair: java.security.KeyPair): ByteArray {
        val public = keyPair.rsaPublic()
        return concat(
            sshString("ssh-rsa".toByteArray(Charsets.US_ASCII)),
            sshString(mpint(public.publicExponent)),
            sshString(mpint(public.modulus)),
        )
    }

    /**
     * SSH `mpint` of a non-negative [java.math.BigInteger].
     *
     * `BigInteger.toByteArray()` is two's complement, so a value whose high bit is set
     * comes back with a leading 0x00 sign byte — which is exactly the mpint encoding — and
     * a value that would be read as negative needs no further handling here because RSA
     * exponents and moduli are positive and their top bits are meaningful. A leading 0x00
     * is stripped, as RFC 4251 requires for non-negative mpints.
     */
    private fun mpint(value: java.math.BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte() && (bytes[1].toInt() and 0x80) == 0) {
            bytes.copyOfRange(1, bytes.size)
        } else {
            bytes
        }
    }

    /**
     * The RSA public half as [java.security.interfaces.RSAPublicKey].
     *
     * `KeyPair.public` is typed as the generic `PublicKey`, and `exponent`/`modulus` only
     * exist on the RSA interface. Failing loudly here beats a silent `null` mask.
     */
    private fun java.security.KeyPair.rsaPublic(): java.security.interfaces.RSAPublicKey =
        public as? java.security.interfaces.RSAPublicKey
            ?: error("expected an RSA key pair, got ${public.algorithm}")

    // ------------------------------------------------------------------
    // Key generation
    // ------------------------------------------------------------------

    fun rsaKeyPair(bits: Int = 2048): java.security.KeyPair {
        val generator = java.security.KeyPairGenerator.getInstance("RSA")
        generator.initialize(bits)
        return generator.generateKeyPair()
    }

    fun ecKeyPair(): java.security.KeyPair =
        java.security.KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    /** PKCS#1 `RSA PRIVATE KEY` PEM, the pre-OpenSSH-square-2 default of `openssl genrsa`. */
    fun pkcs1RsaPem(keyPair: java.security.KeyPair): String {
        val body = java.util.Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
            .encodeToString(keyPair.private.encoded.let { derPkcs1FromPkcs8(it) })
        return "-----BEGIN RSA PRIVATE KEY-----\n$body\n-----END RSA PRIVATE KEY-----\n"
    }

    /**
     * The same PKCS#1 body, wrapped in legacy OpenSSL "Proc-Type" encryption headers.
     *
     * The ciphertext is not real: [KeyParser] decides "this needs a passphrase" from the
     * headers alone and never decrypts PKCS#1 itself, so a truthful payload would only
     * add AES boilerplate without testing anything the parser does.
     */
    fun encryptedPkcs1RsaPem(keyPair: java.security.KeyPair): String {
        val body = java.util.Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
            .encodeToString(derPkcs1FromPkcs8(keyPair.private.encoded))
        return buildString {
            appendLine("-----BEGIN RSA PRIVATE KEY-----")
            appendLine("Proc-Type: 4,ENCRYPTED")
            appendLine("DEK-Info: AES-128-CBC,0123456789ABCDEF0123456789ABCDEF")
            appendLine()
            appendLine(body)
            appendLine("-----END RSA PRIVATE KEY-----")
        }
    }

    /** PKCS#8 `PRIVATE KEY` PEM (`openssl genpkey`). */
    fun pkcs8Pem(keyPair: java.security.KeyPair): String {
        val body = java.util.Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
            .encodeToString(keyPair.private.encoded)
        return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n"
    }

    /** PKCS#8 encrypted container, as OpenSSL 3 writes by default for `genpkey -aes256`. */
    fun encryptedPkcs8Pem(keyPair: java.security.KeyPair): String {
        val body = java.util.Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
            .encodeToString(WRAPPED_PKCS8_ENVELOPE)
        return "-----BEGIN ENCRYPTED PRIVATE KEY-----\n$body\n-----END ENCRYPTED PRIVATE KEY-----\n"
    }

    /**
     * DER for a PKCS#8 `EncryptedPrivateKeyInfo`.
     *
     * Hand-built because `EncryptedPrivateKeyInfo` is not in the Android platform API
     * this project targets: a minimal sequence of algorithm + octet string is enough to
     * prove the parser routes to the encrypted branch.
     */
    private val WRAPPED_PKCS8_ENVELOPE: ByteArray = der(
        0x30,
        concat(
            // AlgorithmIdentifier { id-PBES2 ... } — the OID is never inspected by the parser.
            der(0x30, der(0x06, byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x05, 0x0D))),
            der(0x04, ByteArray(48) { (it + 1).toByte() }),
        ),
    )

    // ------------------------------------------------------------------
    // OpenSSH container assembly
    // ------------------------------------------------------------------

    /**
     * Assemble the `openssh-key-v1` container around an already-encoded private section.
     *
     * `ciphername = none` and `kdfname = none`, so [KeyParser] treats the key as
     * unencrypted and reads the public blob directly. The two copies of `checkInt` are
     * what a real decryption would compare; they are written equal, as OpenSSH does.
     */
    private fun openSshContainer(publicBlob: ByteArray, privateSection: ByteArray): String {
        val raw = concat(
            OPENSSH_MAGIC.toByteArray(Charsets.US_ASCII),
            sshString("none".toByteArray(Charsets.US_ASCII)),
            sshString("none".toByteArray(Charsets.US_ASCII)),
            sshString(ByteArray(0)),
            uint32(1),
            sshString(publicBlob),
            sshString(privateSection),
        )
        val body = java.util.Base64.getMimeEncoder(70, byteArrayOf('\n'.code.toByte())).encodeToString(raw)
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n$body\n-----END OPENSSH PRIVATE KEY-----\n"
    }

    /**
     * OpenSSH private section: `uint32 checkint` twice, `string keytype`, then the
     * key-type-specific fields, a `string` comment and 1..8 bytes of padding.
     */
    private fun editablePrivateSection(
        checkInt: Int,
        keyType: String,
        fields: List<ByteArray>,
        comment: String,
    ): ByteArray {
        val body = concat(
            uint32(checkInt),
            uint32(checkInt),
            sshString(keyType.toByteArray(Charsets.US_ASCII)),
            *fields.map { sshString(it) }.toTypedArray(),
            sshString(comment.toByteArray(Charsets.UTF_8)),
        )
        // OpenSSH pads the section to the cipher block size with 1,2,3,... bytes.
        val padLength = 8 - (body.size % 8)
        return concat(body, ByteArray(padLength) { (it + 1).toByte() })
    }

    // ------------------------------------------------------------------
    // Wire primitives
    // ------------------------------------------------------------------

    fun sshString(payload: ByteArray): ByteArray = concat(uint32(payload.size), payload)

    fun uint32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var offset = 0
        for (part in parts) {
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }

    /** Minimal DER TLV writer for the one envelope above. */
    private fun der(tag: Int, content: ByteArray): ByteArray {
        require(content.size < 0x80) { "test DER writer only handles short lengths" }
        return concat(byteArrayOf(tag.toByte(), content.size.toByte()), content)
    }

    /**
     * Re-encode a PKCS#8 RSA key as PKCS#1 by pulling the inner `RSAPrivateKey`.
     *
     * The JDK only emits PKCS#8, so a real PKCS#1 file is produced by unwrapping the
     * `privateKey` OCTET STRING that PKCS#8 wraps around the PKCS#1 structure.
     */
    private fun derPkcs1FromPkcs8(pkcs8: ByteArray): ByteArray {
        // SEQUENCE { INTEGER version, SEQUENCE algorithm, OCTET STRING privateKey }
        var offset = 0
        fun readTlv(): Pair<Int, ByteArray> {
            val tag = pkcs8[offset++].toInt() and 0xFF
            var length = pkcs8[offset++].toInt() and 0xFF
            if (length and 0x80 != 0) {
                val count = length and 0x7F
                length = 0
                repeat(count) { length = (length shl 8) or (pkcs8[offset++].toInt() and 0xFF) }
            }
            val value = pkcs8.copyOfRange(offset, offset + length)
            offset += length
            return tag to value
        }
        require(readTlv().first == 0x30) { "PKCS#8 must start with a SEQUENCE" }
        offset = 0
        val outer = readTlv().second
        var inner = 0
        fun readOuter(): Pair<Int, ByteArray> {
            val tag = outer[inner++].toInt() and 0xFF
            var length = outer[inner++].toInt() and 0xFF
            if (length and 0x80 != 0) {
                val count = length and 0x7F
                length = 0
                repeat(count) { length = (length shl 8) or (outer[inner++].toInt() and 0xFF) }
            }
            val value = outer.copyOfRange(inner, inner + length)
            inner += length
            return tag to value
        }
        readOuter() // version
        readOuter() // algorithm
        val (tag, value) = readOuter()
        require(tag == 0x04) { "PKCS#8 privateKey must be an OCTET STRING" }
        return value
    }
}
