package com.tuissh.app.di

import android.content.Context
import com.tuissh.app.data.NeoDatabase
import com.tuissh.app.data.crypto.CredentialVault
import com.tuissh.app.data.repo.AiConversationRepository
import com.tuissh.app.data.repo.ServerRepository
import com.tuissh.app.ssh.SshSpecFactory
import com.tuissh.app.ssh.hostkey.HostKeyVerifier
import com.tuissh.app.ssh.proxy.ProxyChainResolver

/**
 * Hand-rolled dependency container.
 *
 * Deliberately not Hilt: the graph is small, and a constructor-parameter container
 * keeps the build fast and the wiring readable. Swap for Hilt only if the graph
 * outgrows this file.
 */
object Graph {

    @Volatile
    private var built: Services? = null

    data class Services(
        val database: NeoDatabase,
        val vault: CredentialVault,
        val repository: ServerRepository,
        val hostKeyVerifier: HostKeyVerifier,
        val proxyChainResolver: ProxyChainResolver,
        val specFactory: SshSpecFactory,
        val aiConversations: AiConversationRepository,
    )

    fun init(context: Context): Services {
        built?.let { return it }
        return synchronized(this) {
            built ?: build(context.applicationContext).also { built = it }
        }
    }

    fun get(): Services =
        built ?: error("Graph.init(context) must run before use")

    private fun build(context: Context): Services {
        val db = NeoDatabase.get(context)
        val vault = CredentialVault(context)

        val repository = ServerRepository(
            serverDao = db.serverDao(),
            groupDao = db.serverGroupDao(),
            keyDao = db.privateKeyDao(),
            blobDao = db.cipherBlobDao(),
            forwardDao = db.forwardDao(),
            vault = vault,
        )

        val aiConversations = AiConversationRepository(db.aiConversationDao())

        val specFactory = SshSpecFactory(
            vault = vault,
            serverDao = db.serverDao(),
            privateKeyDao = db.privateKeyDao(),
        ).apply {
            // Wire the blob lookup so key material can be decrypted without the
            // factory depending on the DAO directly.
            keyCipherLookup = { blobId -> db.cipherBlobDao().findById(blobId)?.ciphertext }
        }

        return Services(
            database = db,
            vault = vault,
            repository = repository,
            hostKeyVerifier = HostKeyVerifier(db.knownHostDao()),
            proxyChainResolver = ProxyChainResolver(db.serverDao()),
            specFactory = specFactory,
            aiConversations = aiConversations,
        )
    }
}
