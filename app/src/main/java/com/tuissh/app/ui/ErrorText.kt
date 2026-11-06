package com.tuissh.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tuissh.app.R
import com.tuissh.app.core.NeoError

/**
 * Maps a typed [NeoError] to a localised user-facing message.
 *
 * Errors travel through the app as data, not as English strings, so the message can
 * always be rendered in the user's language. The raw `NeoError.message` stays
 * English for logs; only this function produces what goes on screen.
 */
@Composable
fun NeoError.localized(): String = when (this) {
    NeoError.ConnectionTimeout -> stringResource(R.string.err_connection_timeout)
    NeoError.HostUnreachable -> stringResource(R.string.err_host_unreachable)
    NeoError.ConnectionRefused -> stringResource(R.string.err_connection_refused)
    NeoError.ConnectionLost -> stringResource(R.string.err_connection_lost)
    NeoError.NotConnected -> stringResource(R.string.terminal_disconnected)
    NeoError.DnsFailure -> stringResource(R.string.err_dns_failure)
    NeoError.AuthFailed -> stringResource(R.string.err_auth_failed)
    NeoError.AuthCancelled -> stringResource(R.string.err_auth_cancelled)
    NeoError.AuthTimedOut -> stringResource(R.string.err_auth_timeout)
    NeoError.NoMatchingAuthMethod -> stringResource(R.string.err_auth_failed)
    NeoError.AgentForwardingRefused -> stringResource(R.string.err_auth_failed)
    is NeoError.HostKeyChanged -> stringResource(R.string.err_host_key_changed)
    is NeoError.InvalidPrivateKey -> stringResource(R.string.err_invalid_key)
    is NeoError.KeyPassphraseRequired -> stringResource(R.string.err_passphrase_required)
    NeoError.SftpUnavailable -> stringResource(R.string.err_sftp_unavailable)
    is NeoError.SftpFailure -> message.orEmpty()
    is NeoError.PathNotFound -> message.orEmpty()
    is NeoError.IsDirectory -> message.orEmpty()
    is NeoError.BastionNotFound -> stringResource(R.string.err_bastion_not_found)
    is NeoError.BastionUnreachable ->
        stringResource(R.string.err_bastion_unreachable, host, detail)
    is NeoError.CyclicBastion -> stringResource(R.string.err_cyclic_bastion)
    is NeoError.InvalidProxy -> stringResource(R.string.err_connection_refused)
    is NeoError.PortBindFailed -> stringResource(R.string.err_connection_refused)
    NeoError.MoshServerMissing -> stringResource(R.string.err_mosh_missing)
    NeoError.MoshPermissionDenied -> stringResource(R.string.err_mosh_missing)
    NeoError.MoshStartupTimeout -> stringResource(R.string.err_mosh_missing)
    NeoError.MoshBadStartupOutput -> stringResource(R.string.err_mosh_missing)
    NeoError.MoshProtocolMismatch -> stringResource(R.string.err_mosh_missing)
    is NeoError.MoshFailure -> message.orEmpty()
    NeoError.DockerUnavailable -> stringResource(R.string.err_docker_unavailable)
    is NeoError.DockerFailure -> message.orEmpty()
    is NeoError.EngineUnavailable -> stringResource(R.string.err_engine_unavailable, binary)
    NeoError.EnginePermissionDenied -> stringResource(R.string.err_engine_permission)
    NeoError.EngineDaemonDown -> stringResource(R.string.err_engine_daemon_down)
    is NeoError.EngineCommandFailed -> detail
    is NeoError.UnsupportedRunCommand -> message.orEmpty()
    is NeoError.AiNotConfigured -> message.orEmpty()
    is NeoError.AiHttp -> message.orEmpty()
    is NeoError.AiProtocol -> message.orEmpty()
    is NeoError.Io -> message.orEmpty()
    is NeoError.Unknown -> stringResource(R.string.common_unknown_error)
}
