package com.tuissh.app.sftp

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import com.tuissh.app.core.LogTag
import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoLog
import com.tuissh.app.core.NeoResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * SFTP client over an existing SSH session.
 *
 * Replaces the Swift side of `russh-sftp` usage: the original opened an SFTP channel
 * from `SFTPBrowserViewModel` and `SFTPTransferCenter`. Here the same SSH session
 * opens a `ChannelSftp`, so no second authentication happens.
 *
 * All calls are blocking JSch work moved to [Dispatchers.IO]; the class itself holds
 * mutable channel state and is not safe for concurrent use — one instance per
 * session, guarded by the caller.
 */
class SftpClient(private val session: Session) {

    private var channel: ChannelSftp? = null

    val isOpen: Boolean
        get() = channel?.isConnected == true

    /** One directory entry, normalised away from JSch types. */
    data class Entry(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val isSymlink: Boolean,
        val size: Long,
        val permissions: Int,
        /** Unix mode string such as `drwxr-xr-x`. */
        val permissionsText: String,
        val modifiedAt: Long,
        /** uid/gid only known when the server reports them. */
        val uid: Int,
        val gid: Int,
        /** Where a symlink points, when resolvable. */
        val linkTarget: String?,
    ) {
        val isFile: Boolean get() = !isDirectory
    }

    // =====================================================================
    // Channel lifecycle
    // =====================================================================

    suspend fun open(): NeoResult<Unit> = withContext(Dispatchers.IO) {
        if (isOpen) return@withContext NeoResult.Ok(Unit)
        try {
            val ch = session.openChannel("sftp") as ChannelSftp
            ch.connect(session.timeout.coerceAtLeast(15_000))
            channel = ch
            NeoLog.i(LogTag.SFTP, "sftp channel open")
            NeoResult.Ok(Unit)
        } catch (e: JSchException) {
            NeoLog.e(LogTag.SFTP, "open sftp failed", e)
            NeoResult.Err(
                if (e.message?.contains("failed to send channel request", true) == true) {
                    NeoError.SftpUnavailable
                } else {
                    NeoError.SftpFailure("open", e.message ?: "unknown")
                },
            )
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { channel?.disconnect() }
        channel = null
    }

    private fun requireChannel(operation: String): NeoResult<ChannelSftp> {
        val ch = channel
        return if (ch != null && ch.isConnected) {
            NeoResult.Ok(ch)
        } else {
            NeoResult.Err(NeoError.SftpFailure(operation, "channel not open"))
        }
    }

    // =====================================================================
    // Navigation
    // =====================================================================

    /** Current working directory reported by the server. */
    suspend fun pwd(): NeoResult<String> = withContext(Dispatchers.IO) {
        val ch = requireChannel("pwd")
        if (ch is NeoResult.Err) return@withContext ch
        try {
            // `ChannelSftp.pwd` is a property-like method; Kotlin resolves it to the
            // getter, so it must be called explicitly.
            NeoResult.Ok(ch.getOrThrow().pwd())
        } catch (e: SftpException) {
            NeoResult.Err(NeoError.SftpFailure("pwd", e.message ?: "unknown"))
        }
    }

    /**
     * Resolve `~` and relative paths to something the SFTP server accepts.
     *
     * SFTP servers reject `~` — it is shell syntax, not a path component. The safe
     * translation is the channel's own working directory, addressed as `.`.
     *
     * `pwd()` is *not* used to expand `~`: on a server sharing the host filesystem
     * (and always in tests) it can return a platform path such as
     * `E:/Code/...` whose drive letter colon is an invalid SFTP filename on some
     * servers. `.` is unambiguous and always means "the session's directory".
     */
    suspend fun canonical(path: String): String = when {
        path.isEmpty() -> "."
        path == "~" -> "."
        path.startsWith("~/") -> "./" + path.removePrefix("~/")
        else -> path
    }
    // =====================================================================
    // Listing
    // =====================================================================

    /**
     * List a directory.
     *
     * `.` and `..` are always dropped. They come back as ordinary directives from
     * every SFTP server, and anything that walks the tree (recursive delete, size
     * calculation) will loop forever on `..` if they are kept.
     *
     * @param showHidden include dotfiles other than `.` and `..`
     */
    suspend fun list(path: String, showHidden: Boolean = true): NeoResult<List<Entry>> =
        withContext(Dispatchers.IO) {
            val ch = requireChannel("list")
            if (ch is NeoResult.Err) return@withContext ch
            try {
                val sftp = ch.getOrThrow()
                val resolved = canonical(path)
                @Suppress("UNCHECKED_CAST")
                val raw = sftp.ls(resolved) as List<ChannelSftp.LsEntry>
                val entries = raw
                    .filter { it.filename != "." && it.filename != ".." }
                    .filter { showHidden || !it.filename.startsWith(".") }
                    .map { it.toEntry(resolved, sftp) }
                    .sortedWith(
                        compareByDescending<Entry> { it.isDirectory }
                            .thenBy { it.name.lowercase() },
                    )
                NeoLog.d(LogTag.SFTP, "list $resolved -> ${entries.size} entries")
                NeoResult.Ok(entries)
            } catch (e: SftpException) {
                NeoLog.e(LogTag.SFTP, "list $path failed", e)
                NeoResult.Err(mapSftpError(e, "list"))
            }
        }

    private fun ChannelSftp.LsEntry.toEntry(parent: String, sftp: ChannelSftp): Entry {
        val attrs: SftpATTRS = attrs
        val isLink = attrs.isLink
        val isDir = attrs.isDir
        val fullPath = if (parent == "/") "/$filename" else "$parent/$filename"
        val target = if (isLink) {
            runCatching { sftp.readlink(fullPath) }.getOrNull()
        } else {
            null
        }
        return Entry(
            name = filename,
            path = fullPath,
            isDirectory = isDir,
            isSymlink = isLink,
            size = attrs.size,
            permissions = attrs.permissions,
            permissionsText = attrs.permissionsString,
            modifiedAt = attrs.mTime.toLong() * 1000L,
            uid = attrs.getUId(),
            gid = attrs.getGId(),
            linkTarget = target,
        )
    }

    // =====================================================================
    // Reads and writes
    // =====================================================================

    /**
     * Read up to [maxBytes] of a remote file.
     *
     * Truncation is deliberate: the file preview cannot show a 2 GB log, and the
     * caller needs to know it was cut. Mirrors the original's
     * "… showing the first N of M bytes" behaviour.
     */
    suspend fun readFile(path: String, maxBytes: Int = 2 * 1024 * 1024): NeoResult<ByteArray> =
        withContext(Dispatchers.IO) {
            val ch = requireChannel("read")
            if (ch is NeoResult.Err) return@withContext ch
            try {
                val sftp = ch.getOrThrow()
                val stream = sftp.get(canonical(path))
                stream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    val out = java.io.ByteArrayOutputStream()
                    var total = 0
                    while (total < maxBytes) {
                        val want = minOf(buffer.size, maxBytes - total)
                        val n = input.read(buffer, 0, want)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        total += n
                    }
                    NeoResult.Ok(out.toByteArray())
                }
            } catch (e: SftpException) {
                NeoResult.Err(mapSftpError(e, "read"))
            } catch (e: Exception) {
                NeoResult.Err(NeoError.SftpFailure("read", e.message ?: "unknown"))
            }
        }

    /** Stream a remote file to [sink]. Used for downloads to app storage. */
    suspend fun download(path: String, sink: OutputStream): NeoResult<Long> =
        withContext(Dispatchers.IO) {
            val ch = requireChannel("download")
            if (ch is NeoResult.Err) return@withContext ch
            try {
                val sftp = ch.getOrThrow()
                var total = 0L
                sftp.get(canonical(path)).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        sink.write(buffer, 0, n)
                        total += n
                    }
                }
                sink.flush()
                NeoResult.Ok(total)
            } catch (e: SftpException) {
                NeoResult.Err(mapSftpError(e, "download"))
            } catch (e: Exception) {
                NeoResult.Err(NeoError.SftpFailure("download", e.message ?: "unknown"))
            }
        }

    /** Upload [source] to [remotePath]. Creates or truncates the remote file. */
    suspend fun upload(source: InputStream, remotePath: String): NeoResult<Long> =
        withContext(Dispatchers.IO) {
            val ch = requireChannel("upload")
            if (ch is NeoResult.Err) return@withContext ch
            try {
                val sftp = ch.getOrThrow()
                var total = 0L
                // JSch's get(path) returns the output stream to write into; the
                // return value of `put` is the SftpProgressMonitor slot, not a stream.
                val out: OutputStream = sftp.put(canonical(remotePath))
                out.use {
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = source.read(buffer)
                        if (n <= 0) break
                        it.write(buffer, 0, n)
                        total += n
                    }
                }
                NeoResult.Ok(total)
            } catch (e: SftpException) {
                NeoResult.Err(mapSftpError(e, "upload"))
            } catch (e: Exception) {
                NeoResult.Err(NeoError.SftpFailure("upload", e.message ?: "unknown"))
            }
        }

    /** Write text, creating parent directories when [createParents] is set. */
    suspend fun writeText(
        remotePath: String,
        content: String,
        createParents: Boolean = false,
    ): NeoResult<Long> = withContext(Dispatchers.IO) {
        if (createParents) {
            val parent = canonical(remotePath).substringBeforeLast('/', "")
            if (parent.isNotEmpty()) mkdirs(parent)
        }
        upload(content.byteInputStream(), remotePath)
    }

    // =====================================================================
    // Mutations
    // =====================================================================

    suspend fun mkdir(path: String): NeoResult<Unit> = withContext(Dispatchers.IO) {
        val ch = requireChannel("mkdir")
        if (ch is NeoResult.Err) return@withContext ch
        try {
            ch.getOrThrow().mkdir(canonical(path))
            NeoResult.Ok(Unit)
        } catch (e: SftpException) {
            NeoResult.Err(mapSftpError(e, "mkdir"))
        }
    }

    /**
     * `mkdir -p` equivalent.
     *
     * Each segment is attempted; an "already exists" failure is expected and
     * ignored, but any other failure is reported. Silently swallowing every error
     * (the obvious `runCatching` shortcut) makes a failed create look like success
     * and produces a confusing "no such file" on the next listing.
     */
    /**
     * `mkdir -p` equivalent.
     *
     * Paths are kept **relative** to the channel's own working directory. Do not
     * concatenate `pwd()` onto them: on a server sharing the host filesystem
     * `pwd()` returns a platform path like `E:/Code/...`, and the colon is not a
     * legal SFTP filename, so every derived path fails with "Invalid filename".
     *
     * Each segment is attempted; "already exists" is expected and ignored, any other
     * failure is reported. Swallowing every error would make a failed create look
     * like success.
     */
    suspend fun mkdirs(path: String): NeoResult<Unit> = withContext(Dispatchers.IO) {
        val ch = requireChannel("mkdir")
        if (ch is NeoResult.Err) return@withContext ch
        val sftp = ch.getOrThrow()
        val resolved = canonical(path)
        if (resolved == ".") return@withContext NeoResult.Ok(Unit)

        val absolute = resolved.startsWith("/")
        var current = if (absolute) "" else ""

        for (segment in resolved.split('/')) {
            if (segment.isEmpty() || segment == ".") continue
            current = if (current.isEmpty()) {
                if (absolute) "/$segment" else segment
            } else {
                "$current/$segment"
            }
            try {
                sftp.mkdir(current)
            } catch (e: SftpException) {
                val message = e.message?.lowercase().orEmpty()
                val alreadyExists = e.id == 4 &&
                    (message.contains("exist") || message.contains("failure") || message.isEmpty())
                if (!alreadyExists) {
                    return@withContext NeoResult.Err(mapSftpError(e, "mkdir $current"))
                }
            }
        }
        NeoResult.Ok(Unit)
    }

    suspend fun remove(path: String): NeoResult<Unit> = withContext(Dispatchers.IO) {
        val ch = requireChannel("remove")
        if (ch is NeoResult.Err) return@withContext ch
        try {
            ch.getOrThrow().rm(canonical(path))
            NeoResult.Ok(Unit)
        } catch (e: SftpException) {
            NeoResult.Err(mapSftpError(e, "remove"))
        }
    }

    /** Recursive delete. Directories are emptied depth-first. */
    suspend fun removeRecursive(path: String): NeoResult<Unit> = removeRecursive(path, 0)

    private suspend fun removeRecursive(path: String, depth: Int): NeoResult<Unit> {
        if (depth > MAX_DEPTH) {
            return NeoResult.Err(NeoError.SftpFailure("removeRecursive", "depth limit exceeded at $path"))
        }
        val target = canonical(path)
        val listing = list(target)
        if (listing is NeoResult.Err) return listing
        for (entry in listing.getOrThrow()) {
            // `list` already drops `.` and `..`; the guard is kept as a second line
            // of defence because a bad server can still echo them back.
            if (entry.name == "." || entry.name == "..") continue
            if (entry.isDirectory && !entry.isSymlink) {
                val nested = removeRecursive(entry.path, depth + 1)
                if (nested is NeoResult.Err) return nested
            } else {
                val removed = remove(entry.path)
                if (removed is NeoResult.Err) return removed
            }
        }
        val ch = requireChannel("rmdir")
        if (ch is NeoResult.Err) return ch
        return try {
            ch.getOrThrow().rmdir(target)
            NeoResult.Ok(Unit)
        } catch (e: SftpException) {
            NeoResult.Err(mapSftpError(e, "rmdir"))
        }
    }

    suspend fun rename(from: String, to: String): NeoResult<Unit> = withContext(Dispatchers.IO) {
        val ch = requireChannel("rename")
        if (ch is NeoResult.Err) return@withContext ch
        try {
            ch.getOrThrow().rename(canonical(from), canonical(to))
            NeoResult.Ok(Unit)
        } catch (e: SftpException) {
            NeoResult.Err(mapSftpError(e, "rename"))
        }
    }

    /**
     * Change permissions.
     *
     * @param octal three or four digit mode, e.g. `755` or `0644`
     */
    suspend fun chmod(path: String, octal: String): NeoResult<Unit> = withContext(Dispatchers.IO) {
        if (!octal.all { it in '0'..'7' } || octal.length !in 3..4) {
            return@withContext NeoResult.Err(NeoError.SftpFailure("chmod", "invalid mode $octal"))
        }
        val ch = requireChannel("chmod")
        if (ch is NeoResult.Err) return@withContext ch
        try {
            val mode = octal.toInt(8)
            ch.getOrThrow().chmod(mode, canonical(path))
            NeoResult.Ok(Unit)
        } catch (e: SftpException) {
            NeoResult.Err(mapSftpError(e, "chmod"))
        }
    }

    suspend fun symlink(target: String, linkPath: String): NeoResult<Unit> =
        withContext(Dispatchers.IO) {
            val ch = requireChannel("symlink")
            if (ch is NeoResult.Err) return@withContext ch
            try {
                ch.getOrThrow().symlink(target, canonical(linkPath))
                NeoResult.Ok(Unit)
            } catch (e: SftpException) {
                NeoResult.Err(mapSftpError(e, "symlink"))
            }
        }

    /** Disk usage as reported by `statvfs` on the directory holding [path]. */
    suspend fun statVfs(path: String): NeoResult<DiskUsage> = withContext(Dispatchers.IO) {
        val ch = requireChannel("statvfs")
        if (ch is NeoResult.Err) return@withContext ch
        try {
            val sftp = ch.getOrThrow()
            val stat = sftp.statVFS(canonical(path))
            NeoResult.Ok(
                DiskUsage(
                    blockSize = stat.fragmentSize.toLong(),
                    totalBytes = stat.fragmentSize.toLong() * stat.blocks,
                    freeBytes = stat.fragmentSize.toLong() * stat.availBlocks,
                ),
            )
        } catch (e: SftpException) {
            NeoResult.Err(mapSftpError(e, "statvfs"))
        }
    }

    data class DiskUsage(val blockSize: Long, val totalBytes: Long, val freeBytes: Long) {
        val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
        val usedFraction: Float
            get() = if (totalBytes <= 0) 0f else usedBytes.toFloat() / totalBytes.toFloat()
    }

    private companion object {
        /** Cycle guard for recursive traversal; real trees never come close. */
        const val MAX_DEPTH = 64
    }

    // =====================================================================
    // Errors
    // =====================================================================

    /**
     * Map SFTP status codes onto the typed error surface.
     *
     * The numeric codes come from the SFTP spec and are stable; matching on them is
     * more reliable than parsing message text, which is server-specific.
     */
    private fun mapSftpError(e: SftpException, operation: String): NeoError = when (e.id) {
        ChannelSftp.SSH_FX_NO_SUCH_FILE -> NeoError.PathNotFound(operation)
        ChannelSftp.SSH_FX_PERMISSION_DENIED -> NeoError.SftpFailure(operation, "permission denied")
        ChannelSftp.SSH_FX_NO_CONNECTION, ChannelSftp.SSH_FX_CONNECTION_LOST ->
            NeoError.ConnectionLost
        ChannelSftp.SSH_FX_OP_UNSUPPORTED ->
            NeoError.SftpFailure(operation, "not supported by the server")
        else -> NeoError.SftpFailure(operation, e.message ?: "status ${e.id}")
    }
}
