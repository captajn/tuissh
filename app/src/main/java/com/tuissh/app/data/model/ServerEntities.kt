package com.tuissh.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A saved SSH server.
 *
 * Field set derived from `SSHServerModel` / `SSHServerExtModel` reflection strings
 * recovered from the original binary (`moshServerCommand`, `moshUDPPort`,
 * `bastionServer`, `sshKeepaliveInterval`, `enableAgentForwarding`, ...).
 */
@Entity(
    tableName = "servers",
    indices = [
        Index("groupId"),
        Index("host", "port"),
        Index("name"),
    ],
)
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    // --- identity ---------------------------------------------------------
    var name: String,
    var host: String,
    var username: String,
    var port: Int = 22,

    // --- credentials ------------------------------------------------------
    /** NONE | PASSWORD | PRIVATE_KEY | KEYBOARD_INTERACTIVE | AGENT | ASK_ON_CONNECT */
    var authType: String = AUTH_PASSWORD,
    /**
     * Encrypted blob. Plaintext passwords are never stored; see [CredentialVault].
     * Empty when [authType] is ASK_ON_CONNECT.
     */
    var passwordCipher: String? = null,
    var privateKeyId: Long? = null,
    var savePassword: Boolean = false,
    var agentForwarding: Boolean = false,

    // --- grouping / appearance -------------------------------------------
    var groupId: Long? = null,
    var icon: String? = null,
    var iconColor: String? = null,
    var tags: String = "",
    var note: String = "",

    // --- terminal preferences --------------------------------------------
    var terminalType: String = "xterm-256color",
    var startupScript: String? = null,
    var encoding: String = "UTF-8",
    var terminalThemeId: Long? = null,
    var lightThemeId: Long? = null,
    var fontSizeOverride: Float? = null,
    var defaultWorkdir: String? = null,

    // --- network ----------------------------------------------------------
    var keepaliveSeconds: Int = 30,
    /** Bastion / ProxyJump target server id. */
    var bastionServerId: Long? = null,
    var proxyId: Long? = null,
    var connectTimeoutSeconds: Int = 15,

    // --- mosh -------------------------------------------------------------
    var useMosh: Boolean = false,
    /** Custom `mosh-server new ...` command line. */
    var moshServerCommand: String? = null,
    /** `60000` or `60000:61000`. Empty lets mosh-server choose. */
    var moshUdpPort: String? = null,

    // --- wake on lan ------------------------------------------------------
    var macAddress: String? = null,
    var wolBroadcast: String? = null,
    var wolPort: Int = 9,

    // --- failover / health ------------------------------------------------
    var failoverPort: Int? = null,
    var autoReconnect: Boolean = true,

    // --- ordering / bookkeeping -------------------------------------------
    var sortIndex: Int = 0,
    var favorite: Boolean = false,
    var offline: Boolean = false,
    var lastConnectedAt: Long = 0,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val AUTH_PASSWORD = "password"
        const val AUTH_PRIVATE_KEY = "private_key"
        const val AUTH_KEYBOARD_INTERACTIVE = "keyboard_interactive"
        const val AUTH_AGENT = "agent"
        const val AUTH_NONE = "none"
        const val AUTH_ASK_ON_CONNECT = "ask_on_connect"

        val AUTH_TYPES = listOf(
            AUTH_PASSWORD,
            AUTH_PRIVATE_KEY,
            AUTH_KEYBOARD_INTERACTIVE,
            AUTH_AGENT,
            AUTH_ASK_ON_CONNECT,
        )
    }
}

/** Server group / folder, mirroring `SSHServerGroupModel`. */
@Entity(tableName = "server_groups", indices = [Index(value = ["name"], unique = true)])
data class ServerGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    var name: String,
    var icon: String? = null,
    var colorHex: String? = null,
    var sortIndex: Int = 0,
    var createdAt: Long = System.currentTimeMillis(),
)

/** A stored private key. The material itself lives encrypted in [CipherBlobEntity]. */
@Entity(tableName = "private_keys", indices = [Index(value = ["name"])])
data class PrivateKeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    var name: String,
    /** RSA | ECDSA | ED25519 | DSA */
    var algorithm: String,
    var bits: Int = 0,
    /** Public key in OpenSSH `authorized_keys` single-line form. */
    var publicKey: String? = null,
    var fingerprintSha256: String? = null,
    var hasPassphrase: Boolean = false,
    var cipherBlobId: Long,
    var createdAt: Long = System.currentTimeMillis(),
    var usedByServers: Int = 0,
)

/** Generic encrypted blob storage (passwords, private keys, tokens). */
@Entity(tableName = "cipher_blobs")
data class CipherBlobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** AES-GCM ciphertext, base64. IV is prefixed to the payload. */
    var ciphertext: String,
    var createdAt: Long = System.currentTimeMillis(),
)

/** Trusted host keys, mirroring `KnownHostStore` / `KnownHostVerificationManager`. */
@Entity(
    tableName = "known_hosts",
    indices = [Index(value = ["hostKey"], unique = true)],
)
data class KnownHostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** `${host.lowercase()}:${port}` */
    var hostKey: String,
    var host: String,
    var port: Int,
    /** `ssh-ed25519` / `rsa-sha2-256` / `ecdsa-sha2-nistp256` */
    var keyType: String,
    var publicKeyBase64: String,
    /** `SHA256:...` fingerprint shown in the mismatch alert. */
    var fingerprintSha256: String,
    var addedAt: Long = System.currentTimeMillis(),
    var lastSeenAt: Long = System.currentTimeMillis(),
)

/** Proxy definition (SOCKS5 endpoint, mirroring `ProxyModel`). */
@Entity(tableName = "proxies")
data class ProxyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    var name: String,
    var host: String,
    var port: Int = 1080,
    var username: String? = null,
    var passwordCipher: String? = null,
    var useForAi: Boolean = false,
    var createdAt: Long = System.currentTimeMillis(),
)

/** Persistent port-forward rule, mirroring `ForwardModel` + `TcpForwardManager`. */
@Entity(
    tableName = "forwards",
    indices = [Index("serverId")],
)
data class ForwardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    var serverId: Long,
    var name: String = "",
    /** local | remote | dynamic */
    var kind: String = KIND_LOCAL,
    var bindHost: String = "127.0.0.1",
    var bindPort: Int,
    var targetHost: String = "127.0.0.1",
    var targetPort: Int = 22,
    var autoStart: Boolean = false,
    var keepAlive: Boolean = true,
    var createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val KIND_LOCAL = "local"
        const val KIND_REMOTE = "remote"
        const val KIND_DYNAMIC = "dynamic"
    }
}

/** Saved shell script / snippet, mirroring `ScriptModel` + `ScriptGroupModel`. */
@Entity(tableName = "scripts", indices = [Index("groupId")])
data class ScriptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    var name: String,
    var body: String,
    var groupId: Long? = null,
    /**
     * When true the selected file/folder path is appended as the final argument,
     * replacing the `neopath` placeholder documented in the original app.
     */
    var appendPath: Boolean = false,
    var runOnAllServers: Boolean = false,
    var timeoutSeconds: Int = 60,
    var sortIndex: Int = 0,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "script_groups", indices = [Index(value = ["name"], unique = true)])
data class ScriptGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    var name: String,
    var sortIndex: Int = 0,
)

/** One execution record, mirroring `ScriptRunHistoryManager`. */
@Entity(tableName = "script_run_history", indices = [Index("scriptId"), Index("ranAt")])
data class ScriptRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    var scriptId: Long,
    var serverId: Long,
    var serverName: String,
    var command: String,
    var exitCode: Int,
    var output: String,
    var ranAt: Long = System.currentTimeMillis(),
    var durationMs: Long = 0,
)

/** Terminal colour theme, mirroring `TerminalCustomThemeModel`. */
@Entity(tableName = "terminal_themes", indices = [Index(value = ["name"], unique = true)])
data class TerminalThemeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    var name: String,
    var isDark: Boolean = true,
    var foreground: Int,
    var background: Int,
    var cursor: Int,
    var selection: Int,
    /** Comma separated 16 ANSI colours, 0xRRGGBB hex. */
    var ansiColors: String,
    var source: String = "custom",
    var builtIn: Boolean = false,
    var createdAt: Long = System.currentTimeMillis(),
)

/** Highlight rule for terminal output, mirroring `HighlightRuleModel`. */
@Entity(tableName = "highlight_rules")
data class HighlightRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    var name: String,
    var pattern: String,
    var isRegex: Boolean = true,
    var foreground: Int? = null,
    var background: Int? = null,
    var bold: Boolean = false,
    var enabled: Boolean = true,
    var sortIndex: Int = 0,
)

/** Recent terminal session, mirroring `RecentTerminalSessionModel`. */
@Entity(tableName = "recent_sessions", indices = [Index("serverId"), Index("openedAt")])
data class RecentSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    var serverId: Long,
    var serverName: String,
    var title: String? = null,
    var workdir: String? = null,
    var openedAt: Long = System.currentTimeMillis(),
)

/** Bookmarked / recent SFTP directory, mirroring `SFTPRecentLocationStore`. */
@Entity(tableName = "sftp_recent_locations", indices = [Index("serverId"), Index("visitedAt")])
data class SftpRecentLocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    var serverId: Long,
    var path: String,
    var visitedAt: Long = System.currentTimeMillis(),
)

/** Custom right-click menu entry for the SFTP browser (`SFTPCustomMenuStore`). */
@Entity(tableName = "sftp_custom_menus")
data class SftpCustomMenuEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    var label: String,
    /** Shell command template; `neopath` is replaced with the quoted path. */
    var command: String,
    var runOnDirectoryOnly: Boolean = false,
    var sortIndex: Int = 0,
)
