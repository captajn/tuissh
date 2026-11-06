package com.tuissh.app.ssh

import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived, process-wide memory of the credentials the vault could not supply.
 *
 * The terminal, the SFTP browser and the container list each open their own SSH
 * session. When the vault holds no password for a server, every one of those paths
 * asked the user again, so switching tabs on one server could cost three prompts for
 * one password. Remembering the answer for a few minutes turns that into one prompt.
 *
 * This is a deliberate security trade-off. The password sits in ordinary heap memory,
 * unencrypted, for at most [ttlMillis]. Memory is the one place the secret already has
 * to exist at this point: JSch needs the plaintext to run the auth exchange, so
 * holding it briefly in the same process adds no new exposure that a live connection
 * does not already create. A short window keeps it from accumulating into a
 * long-lived secret store. What is not acceptable is writing it anywhere it outlives
 * the process or escapes the sandbox: this class touches no disk, no DataStore, no
 * SavedStateHandle, no serialized state, and no logger. Read the value, use it, drop
 * it. Persistence remains the vault's job, and the vault encrypts.
 *
 * Instances rather than a bare object so a test can construct one with its own
 * [clock] and [ttlMillis] instead of reaching into shared mutable state. The app
 * shares [SessionCredentialCache.shared]; tests should prefer their own instance and
 * use [reset] only to clean up [shared].
 *
 * @param ttlMillis how long an entry stays readable; zero makes every read miss
 * @param clock source of the timestamps used for expiry, injectable for tests
 */
class SessionCredentialCache(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * A cached secret pair. Either half may be null: a password-only entry is normal,
     * and a passphrase-only entry is what a protected key leaves behind.
     */
    data class Credentials(
        val password: String?,
        val passphrase: String?,
    ) {
        /** True when neither half holds a usable secret, so caching it buys nothing. */
        val isEmpty: Boolean
            get() = password == null && passphrase == null
    }

    private class Entry(
        val credentials: Credentials,
        val writtenAt: Long,
    )

    private val entries = ConcurrentHashMap<Long, Entry>()

    /** How long a written entry stays readable. */
    val ttl: Long
        get() = ttlMillis

    /**
     * Remember credentials for one server, replacing whatever is held.
     *
     * The parameters are the complete credential pair for that server, so a null half
     * clears that field rather than storing a literal "null". Holding two secrets
     * independently would let a stale password outlive the attempt that replaced it:
     * a prompt for the password would keep passing the old passphrase to the key.
     * Callers that know only one of the two pass the other through from [read], where
     * they got both halves together. Empty strings are dropped for the same reason as
     * null: they are a cancelled field, not a secret.
     *
     * @param serverId row id of the server the secret belongs to
     * @param password plaintext password, or null to forget any cached password
     * @param passphrase plaintext key passphrase, or null to forget any cached one
     */
    fun write(serverId: Long, password: String? = null, passphrase: String? = null) {
        val fresh = Credentials(
            password = password?.takeIf { it.isNotEmpty() },
            passphrase = passphrase?.takeIf { it.isNotEmpty() },
        )
        if (fresh.isEmpty) {
            entries.remove(serverId)
        } else {
            entries[serverId] = Entry(fresh, clock())
        }
    }

    /**
     * Replace only the password, keeping any cached passphrase.
     *
     * Separate from [write] on purpose. [write] takes the complete pair and treats a
     * null half as "forget it", which is right for a fresh login but wrong for a
     * password prompt that fires while a passphrase is already cached — that would
     * silently drop the passphrase and force the user through a second dialog.
     */
    fun updatePassword(serverId: Long, password: String?) {
        val existing = read(serverId)
        val next = password?.takeIf { it.isNotEmpty() }
        if (next == null && existing?.passphrase == null) {
            entries.remove(serverId)
            return
        }
        entries[serverId] = Entry(
            Credentials(
                password = next,
                passphrase = existing?.passphrase,
            ),
            clock(),
        )
    }

    /**
     * Replace only the passphrase, keeping any cached password.
     *
     * See [updatePassword] for why this is not folded into [write].
     */
    fun updatePassphrase(serverId: Long, passphrase: String?) {
        val existing = read(serverId)
        val next = passphrase?.takeIf { it.isNotEmpty() }
        if (next == null && existing?.password == null) {
            entries.remove(serverId)
            return
        }
        entries[serverId] = Entry(
            Credentials(
                password = existing?.password,
                passphrase = next,
            ),
            clock(),
        )
    }
    /**
     * Credentials for one server, or null when nothing usable is held.
     *
     * Returns null as soon as the entry has outlived [ttl], and drops it on the way
     * out: a stale secret must not be handed to a new connection just because nothing
     * else has touched the cache since.
     *
     * @param serverId row id of the server to look up
     */
    fun read(serverId: Long): Credentials? {
        val entry = entries[serverId] ?: return null
        if (!isFresh(entry)) {
            entries.remove(serverId, entry)
            return null
        }
        return entry.credentials
    }

    /**
     * Forget one server.
     *
     * Call this when the user edits the server or removes it, so a changed host or a
     * deleted account cannot be reached with the old secret.
     *
     * @param serverId row id of the server to forget
     */
    fun clear(serverId: Long) {
        entries.remove(serverId)
    }

    /**
     * Forget every server.
     *
     * Worth calling when the app leaves the foreground: a process that is backgrounded
     * for minutes is exactly the case where a still-readable secret surprises someone.
     */
    fun clearAll() {
        entries.clear()
    }

    /** Number of entries currently held, expired ones excluded. */
    fun size(): Int {
        evictExpired()
        return entries.size
    }

    private fun isFresh(entry: Entry?): Boolean =
        entry != null && (clock() - entry.writtenAt) < ttlMillis

    private fun evictExpired() {
        for (id in entries.keys) {
            val entry = entries[id] ?: continue
            if (!isFresh(entry)) entries.remove(id, entry)
        }
    }

    companion object {
        /**
         * Fifteen minutes: long enough to cover moving between the terminal, the SFTP
         * browser and the container list on one server, short enough that a secret
         * left on a device someone else picks up stops working well before a session
         * would have expired on its own.
         */
        const val DEFAULT_TTL_MILLIS: Long = 15L * 60L * 1000L

        /**
         * The app-wide instance.
         *
         * Only [write], [read], [clear], [clearAll] and [reset] are exposed, so the
         * shared map cannot be rewired from outside.
         */
        private val instance = SessionCredentialCache()

        /** Read from the app-wide cache. */
        fun read(serverId: Long): Credentials? = instance.read(serverId)

        /** Write into the app-wide cache. */
        fun write(serverId: Long, password: String? = null, passphrase: String? = null) {
            instance.write(serverId, password, passphrase)
        }

        /** Forget one server in the app-wide cache. */
        fun clear(serverId: Long) {
            instance.clear(serverId)
        }

        /** Forget everything in the app-wide cache. */
        fun clearAll() {
            instance.clearAll()
        }

        /** Empty the app-wide cache. Exists for test isolation; production code uses [clearAll]. */
        fun reset() {
            instance.clearAll()
        }
    }
}
