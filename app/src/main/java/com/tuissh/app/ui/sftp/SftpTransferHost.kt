package com.tuissh.app.ui.sftp

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tuissh.app.R
import com.tuissh.app.core.NeoFormat
import com.tuissh.app.core.NeoResult
import com.tuissh.app.sftp.SftpClient
import com.tuissh.app.sftp.SftpTransferBridge
import kotlinx.coroutines.launch

/**
 * Upload and download wiring for the SFTP browser.
 *
 * Wraps the two SAF launchers so [SftpBrowserScreen] stays a pure view:
 *  - upload picks an existing document with `OpenDocument`
 *  - download creates one with `CreateDocument`, because the app has no storage
 *    permission and must let the user choose where the file lands
 *
 * State is returned through [Callbacks] rather than hoisted, so the screen can show
 * progress without owning a coroutine scope for transfers.
 */
@Composable
fun SftpTransferHost(
    viewModel: SftpBrowserViewModel,
    onStatus: (String?) -> Unit,
    content: @Composable (Callbacks) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bridge = remember(context) { SftpTransferBridge(context) }

    var busy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf<String?>(null) }
    var pendingDownload by remember { mutableStateOf<SftpClient.Entry?>(null) }

    // --- upload -----------------------------------------------------------
    val pickUpload = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = bridge.describe(uri)?.name ?: bridge.fallbackName(uri)
        val base = viewModel.state.value.path
        val target = if (base.endsWith("/")) "$base$name" else "$base/$name"

        busy = true
        busyLabel = name
        scope.launch {
            val result = bridge.upload(viewModel.client(), uri, target)
            busy = false
            busyLabel = null
            when (result) {
                is NeoResult.Ok -> {
                    onStatus(
                        stringResUploadDone(name, NeoFormat.bytes(result.value.bytes)),
                    )
                    viewModel.refresh()
                }
                is NeoResult.Err -> onStatus(result.error.message)
            }
        }
    }

    // --- download ---------------------------------------------------------
    val createDownload = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        val entry = pendingDownload
        pendingDownload = null
        if (uri == null || entry == null) return@rememberLauncherForActivityResult

        busy = true
        busyLabel = entry.name
        scope.launch {
            val result = bridge.download(viewModel.client(), entry.path, uri)
            busy = false
            busyLabel = null
            when (result) {
                is NeoResult.Ok -> onStatus(
                    "Đã tải xuống ${entry.name} (${NeoFormat.bytes(result.value.bytes)})",
                )
                is NeoResult.Err -> onStatus(result.error.message)
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            busyLabel?.let {
                Text(
                    text = stringResource(R.string.sftp_transferring, it),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        content(
            Callbacks(
                requestUpload = { pickUpload.launch(arrayOf("*/*")) },
                requestDownload = { entry ->
                    pendingDownload = entry
                    createDownload.launch(bridge.suggestedName(entry.name))
                },
                busy = busy,
            ),
        )
    }
}

/** Actions the browser screen can trigger. */
data class Callbacks(
    val requestUpload: () -> Unit,
    val requestDownload: (SftpClient.Entry) -> Unit,
    val busy: Boolean,
)

private fun stringResUploadDone(name: String, size: String): String = "Đã tải lên $name ($size)"
