package com.tuissh.app.ssh

import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoResult
import com.tuissh.app.data.crypto.CredentialVault
import com.tuissh.app.data.dao.PrivateKeyDao
import com.tuissh.app.data.dao.ServerDao
import com.tuissh.app.data.model.PrivateKeyEntity
import com.tuissh.app.data.model.ServerEntity
import com.tuissh.app.ssh.key.KeyParseResult
import com.tuissh.app.ssh.key.KeyParser
import com.tuissh.app.ssh.key.ParsedKey

/**
 * Turns a stored [ServerEntity] into an [SshConnectSpec].
 *
 * All credential decryption happens here and nowhere else, so there is exactly one
 * place to audit for plaintext handling.
 */
class SshSpecFactory(
    private val vault: CredentialVault,
    private val serverDao: ServerDao,
    private val privateKeyDao: PrivateKeyDao,
) {

    sealed interface Outcome {
        data class Ready(val spec: SshConnectSpec) : Outcome

        /** The UI must collect a password or passphrase before retrying. */
        data class NeedsSecret(val error: NeoError) : Outcome

        data class Failed(val error: NeoError) : Outcome
    }

    /**
     * Build a connect spec.
     *
     * @param suppliedPassword password the user just typed, when auth is ask-on-connect
     * @param suppliedPassphrase key passphrase the user just typed
     */
    suspend fun build(
        server: ServerEntity,
        suppliedPassword: String? = null,
        suppliedPassphrase: String? = null,
    ): Outcome {
        if (server.host.isBlank()) return Outcome.Failed(NeoError.DnsFailure)
        if (server.username.isBlank()) return Outcome.Failed(NeoError.AuthFailed)

        val auth = when (server.authType) {
            ServerEntity.AUTH_PASSWORD -> {
                val cipher = server.passwordCipher
                val password = when {
                    suppliedPassword != null -> suppliedPassword
                    cipher != null -> vault.decrypt(cipher).getOrNull()
                    else -> null
                }
                if (password.isNullOrEmpty()) {
                    // No usable stored password. `savePassword` only records whether a
                    // secret *was* stored, and it defaults to true — so treating it as
                    // "the password is missing and that is fatal" leaves ask-on-connect
                    // servers permanently unreachable with "Authentication failed".
                    // An empty vault always means: ask the user.
                    return Outcome.Ready(placeholder(server, SshConnectSpec.Auth.AskOnConnect))
                }
                SshConnectSpec.Auth.Password(password)
            }

            ServerEntity.AUTH_PRIVATE_KEY -> {
                val keyId = server.privateKeyId
                    ?: return Outcome.Failed(NeoError.InvalidPrivateKey("no key selected"))
                val key = privateKeyDao.findById(keyId)
                    ?: return Outcome.Failed(NeoError.InvalidPrivateKey("key not found"))

                when (val parsed = loadKey(key, suppliedPassphrase)) {
                    is KeyParseResult.Success -> SshConnectSpec.Auth.PrivateKey(
                        pem = parsed.key.pem,
                        passphrase = parsed.key.passphrase,
                    )
                    is KeyParseResult.NeedsPassphrase -> return Outcome.NeedsSecret(
                        NeoError.KeyPassphraseRequired(key.id),
                    )
                    is KeyParseResult.BadPassphrase -> return Outcome.Failed(
                        NeoError.InvalidPrivateKey("wrong passphrase"),
                    )
                    is KeyParseResult.Failure -> return Outcome.Failed(
                        NeoError.InvalidPrivateKey(parsed.reason),
                    )
                }
            }

            ServerEntity.AUTH_KEYBOARD_INTERACTIVE ->
                SshConnectSpec.Auth.KeyboardInteractive

            ServerEntity.AUTH_ASK_ON_CONNECT ->
                if (suppliedPassword != null) SshConnectSpec.Auth.Password(suppliedPassword)
                else SshConnectSpec.Auth.AskOnConnect

            ServerEntity.AUTH_NONE -> SshConnectSpec.Auth.None

            else -> return Outcome.Failed(
                NeoError.AuthFailed,
            )
        }

        return Outcome.Ready(
            SshConnectSpec(
                serverId = server.id,
                name = server.name,
                host = server.host,
                port = server.port,
                username = server.username,
                auth = auth,
                terminalType = server.terminalType,
                keepaliveSeconds = server.keepaliveSeconds,
                connectTimeoutSeconds = server.connectTimeoutSeconds,
                startupScript = server.startupScript,
                defaultWorkdir = server.defaultWorkdir,
                agentForwarding = server.agentForwarding,
            ),
        )
    }

    private fun placeholder(server: ServerEntity, auth: SshConnectSpec.Auth): SshConnectSpec =
        SshConnectSpec(
            serverId = server.id,
            name = server.name,
            host = server.host,
            port = server.port,
            username = server.username,
            auth = auth,
        )

    /**
     * Load and parse a stored key.
     *
     * The key material lives encrypted in `CipherBlobEntity`; the passphrase is
     * never stored and always has to come from the user for protected keys.
     */
    private suspend fun loadKey(key: PrivateKeyEntity, suppliedPassphrase: String?): KeyParseResult {
        val material = readKeyMaterial(key)
            ?: return KeyParseResult.Failure("key material unreadable")
        return KeyParser.parse(material, suppliedPassphrase)
    }

    /**
     * Read the decrypted key text.
     *
     * `CipherBlobEntity` holds the AES-GCM payload for the PEM body. This indirection
     * means the plaintext exists only inside this call.
     */
    private suspend fun readKeyMaterial(key: PrivateKeyEntity): String? {
        // The blob id is stored on the key row; the vault owns the key material.
        // Import flow writes `ciphertext` at insert time, so decrypt is enough.
        return vault.decryptOrNull(blobFor(key))
    }

    private suspend fun blobFor(key: PrivateKeyEntity): String? = keyCipherLookup?.invoke(key.cipherBlobId)

    /** Injected so this factory does not depend on the blob DAO directly. */
    var keyCipherLookup: (suspend (Long) -> String?)? = null

    companion object {
        /** Parse a pasted key for the import screen without persisting anything. */
        fun parseForKeyImport(text: String, passphrase: String?): KeyParseResult =
            KeyParser.parse(text, passphrase)

        /** Public key line to show in the key detail screen. */
        fun publicLine(key: ParsedKey): String? = key.publicKeyOpenSsh
    }
}
