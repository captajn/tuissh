package com.tuissh.app.data.repo

import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoResult
import com.tuissh.app.data.crypto.CredentialVault
import com.tuissh.app.data.dao.CipherBlobDao
import com.tuissh.app.data.dao.ForwardDao
import com.tuissh.app.data.dao.PrivateKeyDao
import com.tuissh.app.data.dao.ServerDao
import com.tuissh.app.data.dao.ServerGroupDao
import com.tuissh.app.data.model.CipherBlobEntity
import com.tuissh.app.data.model.ForwardEntity
import com.tuissh.app.data.model.PrivateKeyEntity
import com.tuissh.app.data.model.ServerEntity
import com.tuissh.app.data.model.ServerGroupEntity
import kotlinx.coroutines.flow.Flow

/**
 * Single entry point for server, group, key and forward persistence.
 *
 * View models never touch DAOs or the vault directly. That keeps encryption of
 * passwords and key material in one place and makes the "is this stored in
 * plaintext?" question answerable by reading one file.
 */
class ServerRepository(
    private val serverDao: ServerDao,
    private val groupDao: ServerGroupDao,
    private val keyDao: PrivateKeyDao,
    private val blobDao: CipherBlobDao,
    private val forwardDao: ForwardDao,
    private val vault: CredentialVault,
) {

    // --- reads -------------------------------------------------------------

    fun observeServers(): Flow<List<ServerEntity>> = serverDao.observeAll()

    fun observeGroups(): Flow<List<ServerGroupEntity>> = groupDao.observeAll()

    fun observeServer(id: Long): Flow<ServerEntity?> = serverDao.observeById(id)

    fun observeKeys(): Flow<List<PrivateKeyEntity>> = keyDao.observeAll()

    fun observeForwards(serverId: Long): Flow<List<ForwardEntity>> =
        forwardDao.observeForServer(serverId)

    suspend fun server(id: Long): ServerEntity? = serverDao.findById(id)

    suspend fun servers(ids: List<Long>): List<ServerEntity> = serverDao.findByIds(ids)

    suspend fun allServers(): List<ServerEntity> = serverDao.findAll()

    suspend fun search(query: String): List<ServerEntity> =
        if (query.isBlank()) serverDao.findAll() else serverDao.search(query)

    // --- writes ------------------------------------------------------------

    /**
     * Insert or update a server.
     *
     * A non-empty [plainPassword] is encrypted before it reaches the database.
     * Passing `null` leaves the stored secret untouched; passing `""` clears it.
     */
    suspend fun saveServer(server: ServerEntity, plainPassword: String? = null): NeoResult<Long> {
        return try {
            var row = server.copy(updatedAt = System.currentTimeMillis())

            when {
                plainPassword == null -> Unit
                plainPassword.isEmpty() -> row = row.copy(passwordCipher = null, savePassword = false)
                else -> {
                    val encrypted = vault.encrypt(plainPassword)
                    if (encrypted is NeoResult.Err) return NeoResult.Err(encrypted.error)
                    row = row.copy(passwordCipher = encrypted.getOrThrow(), savePassword = true)
                }
            }

            val id = serverDao.upsert(row)
            NeoResult.Ok(if (id > 0) id else row.id)
        } catch (e: Exception) {
            NeoResult.Err(NeoError.Io("save server: ${e.message}"))
        }
    }

    suspend fun deleteServer(id: Long): NeoResult<Unit> = try {
        forwardDao.deleteForServer(id)
        serverDao.deleteById(id)
        NeoResult.Ok(Unit)
    } catch (e: Exception) {
        NeoResult.Err(NeoError.Io("delete server: ${e.message}"))
    }

    suspend fun setFavorite(id: Long, favorite: Boolean) = serverDao.setFavorite(id, favorite)

    suspend fun saveGroup(group: ServerGroupEntity): NeoResult<Long> = try {
        NeoResult.Ok(groupDao.insert(group))
    } catch (e: Exception) {
        NeoResult.Err(NeoError.Io("save group: ${e.message}"))
    }

    suspend fun deleteGroup(id: Long): NeoResult<Unit> = try {
        val group = groupDao.findAll().firstOrNull { it.id == id }
        group?.let { groupDao.delete(it) }
        NeoResult.Ok(Unit)
    } catch (e: Exception) {
        NeoResult.Err(NeoError.Io("delete group: ${e.message}"))
    }

    // --- private keys ------------------------------------------------------

    /**
     * Store a private key.
     *
     * The PEM body is encrypted with the vault; only the fingerprint and public
     * half stay in plaintext columns so the list can render without decrypting.
     */
    suspend fun importKey(
        name: String,
        pem: String,
        algorithm: String,
        bits: Int,
        passphrase: String?,
        publicKeyOpenSsh: String?,
        fingerprintSha256: String?,
    ): NeoResult<Long> {
        val encrypted = vault.encrypt(pem)
        if (encrypted is NeoResult.Err) return NeoResult.Err(encrypted.error)
        return try {
            val blobId = blobDao.insert(CipherBlobEntity(ciphertext = encrypted.getOrThrow()))
            val id = keyDao.upsert(
                PrivateKeyEntity(
                    name = name,
                    algorithm = algorithm,
                    bits = bits,
                    publicKey = publicKeyOpenSsh,
                    fingerprintSha256 = fingerprintSha256,
                    hasPassphrase = !passphrase.isNullOrEmpty(),
                    cipherBlobId = blobId,
                ),
            )
            NeoResult.Ok(id)
        } catch (e: Exception) {
            NeoResult.Err(NeoError.Io("import key: ${e.message}"))
        }
    }

    suspend fun keyMaterial(keyId: Long): String? {
        val key = keyDao.findById(keyId) ?: return null
        val blob = blobDao.findById(key.cipherBlobId) ?: return null
        return vault.decryptOrNull(blob.ciphertext)
    }

    suspend fun deleteKey(keyId: Long): NeoResult<Unit> {
        return try {
            val key = keyDao.findById(keyId)
            if (key != null) {
                val inUse = keyDao.usageCount(keyId)
                if (inUse > 0) {
                    NeoResult.Err(NeoError.InvalidPrivateKey("key is used by $inUse server(s)"))
                } else {
                    keyDao.delete(key)
                    blobDao.deleteById(key.cipherBlobId)
                    NeoResult.Ok(Unit)
                }
            } else {
                NeoResult.Ok(Unit)
            }
        } catch (e: Exception) {
            NeoResult.Err(NeoError.Io("delete key: ${e.message}"))
        }
    }

    // --- forwards ----------------------------------------------------------

    suspend fun saveForward(forward: ForwardEntity): NeoResult<Long> = try {
        NeoResult.Ok(forwardDao.upsert(forward))
    } catch (e: Exception) {
        NeoResult.Err(NeoError.Io("save forward: ${e.message}"))
    }

    suspend fun deleteForward(id: Long): NeoResult<Unit> = try {
        forwardDao.deleteById(id)
        NeoResult.Ok(Unit)
    } catch (e: Exception) {
        NeoResult.Err(NeoError.Io("delete forward: ${e.message}"))
    }

    suspend fun forwardsFor(serverId: Long): List<ForwardEntity> = forwardDao.findByServer(serverId)

    suspend fun autoStartForwards(): List<ForwardEntity> = forwardDao.findAutoStart()

    // --- housekeeping ------------------------------------------------------

    /** Drop vault blobs that no key references any more. */
    suspend fun pruneOrphanBlobs() = blobDao.deleteOrphans()

    suspend fun counts(): Pair<Int, Int> = serverDao.count() to keyDao.findAll().size
}
