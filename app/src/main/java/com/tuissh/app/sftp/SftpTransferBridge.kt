package com.tuissh.app.sftp

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tuissh.app.core.LogTag
import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoLog
import com.tuissh.app.core.NeoResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * Bridges Android's Storage Access Framework to SFTP transfers.
 *
 * The original app used the Files app provider directly. On Android the equivalent
 * is a content `Uri` returned by `ActivityResultContracts.OpenDocument` /
 * `CreateDocument`, so this class is the single place that knows how to turn a
 * `content://` reference into the streams [SftpClient] expects.
 *
 * Nothing here keeps a Uri alive: callers pass the Uri, get a stream, and the stream
 * is closed when the transfer finishes.
 */
class SftpTransferBridge(private val context: Context) {

    /** Metadata SAF reports for a picked document. */
    data class PickedFile(
        val uri: Uri,
        /** Display name, used as the remote filename. */
        val name: String,
        /** Size in bytes, or -1 when the provider does not report one. */
        val size: Long,
    )

    data class TransferOutcome(
        val bytes: Long,
        val remotePath: String,
        val cancelled: Boolean = false,
    )

    // ------------------------------------------------------------------
    // Reading a picked document
    // ------------------------------------------------------------------

    /**
     * Read display name and size for a Uri.
     *
     * Providers are not required to report a size; [PickedFile.size] is -1 then and
     * the transfer still works.
     */
    fun describe(uri: Uri): PickedFile? = runCatching {
        val resolver = context.contentResolver
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
            val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
            name?.let { PickedFile(uri, it, size) }
        }
    }.getOrNull()

    /** Fallback name when the provider reports nothing usable. */
    fun fallbackName(uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "upload.bin"

    // ------------------------------------------------------------------
    // Upload
    // ------------------------------------------------------------------

    /**
     * Upload a picked document to [remotePath].
     *
     * The stream is opened here and closed here, so a caller cannot leak it by
     * forgetting to close on the error path.
     */
    suspend fun upload(
        client: SftpClient,
        uri: Uri,
        remotePath: String,
    ): NeoResult<TransferOutcome> = withContext(Dispatchers.IO) {
        val stream = open(uri)
            ?: return@withContext NeoResult.Err(
                NeoError.SftpFailure("upload", "cannot open the selected file"),
            )
        stream.use { input ->
            when (val result = client.upload(input, remotePath)) {
                is NeoResult.Ok -> {
                    NeoLog.i(LogTag.SFTP, "uploaded $remotePath (${result.value} bytes)")
                    NeoResult.Ok(TransferOutcome(result.value, remotePath))
                }
                is NeoResult.Err -> result
            }
        }
    }

    /**
     * Download [remotePath] into a document the user created with the SAF picker.
     *
     * The picker already made an empty file, so the mode is `"wt"` (write + truncate):
     * `"w"` would append on some providers and corrupt an existing document.
     */
    suspend fun download(
        client: SftpClient,
        remotePath: String,
        destination: Uri,
    ): NeoResult<TransferOutcome> = withContext(Dispatchers.IO) {
        val sink = runCatching {
            context.contentResolver.openOutputStream(destination, "wt")
        }.getOrNull() ?: return@withContext NeoResult.Err(
            NeoError.SftpFailure("download", "cannot write to the destination"),
        )
        sink.use { out ->
            when (val result = client.download(remotePath, out)) {
                is NeoResult.Ok -> {
                    NeoLog.i(LogTag.SFTP, "downloaded $remotePath (${result.value} bytes)")
                    NeoResult.Ok(TransferOutcome(result.value, remotePath))
                }
                is NeoResult.Err -> result
            }
        }
    }

    // ------------------------------------------------------------------
    // Local streams
    // ------------------------------------------------------------------

    /** Open a picked document for reading, or null when the provider refused. */
    fun open(uri: Uri): InputStream? = runCatching {
        context.contentResolver.openInputStream(uri)
    }.getOrNull()

    /** Open a document for writing. Mode `"wt"` truncates an existing file. */
    fun openForWrite(uri: Uri): OutputStream? = runCatching {
        context.contentResolver.openOutputStream(uri, "wt")
    }.getOrNull()

    /**
     * Suggested filename for a remote path, used to prefill the SAF creator.
     * Falls back to "download.bin" for paths that end in a slash.
     */
    fun suggestedName(remotePath: String): String =
        remotePath.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() } ?: "download.bin"

    /** MIME type hint for a filename; SAF ignores unknown values gracefully. */
    fun mimeTypeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt", "log", "conf", "md" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "html", "htm" -> "text/html"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "gz", "tgz" -> "application/gzip"
            "tar" -> "application/x-tar"
            "sh" -> "application/x-sh"
            else -> "application/octet-stream"
        }
    }

    /** True when the provider allows the content resolver to read this Uri. */
    fun canRead(uri: Uri): Boolean {
        val resolver: ContentResolver = context.contentResolver
        return runCatching { resolver.openInputStream(uri)?.use { true } ?: false }.getOrDefault(false)
    }
}
