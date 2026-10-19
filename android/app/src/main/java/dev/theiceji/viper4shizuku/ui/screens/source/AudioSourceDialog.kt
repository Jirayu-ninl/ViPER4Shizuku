package dev.theiceji.viper4shizuku.ui.screens.source

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import dev.theiceji.viper4shizuku.R
import dev.theiceji.viper4shizuku.service.CaptureState
import dev.theiceji.viper4shizuku.service.PlayerState
import dev.theiceji.viper4shizuku.ui.components.ActionRow
import dev.theiceji.viper4shizuku.ui.components.DialogCard
import dev.theiceji.viper4shizuku.ui.components.DialogSectionLabel
import dev.theiceji.viper4shizuku.ui.components.InfoRow
import dev.theiceji.viper4shizuku.ui.components.RowDivider
import dev.theiceji.viper4shizuku.ui.components.UiDimens

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
private const val SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/download/"

/**
 * Where the audio ViPER4Shizuku processes comes from: other apps' media captured through Shizuku
 * (system audio), or a local file ViPER4Shizuku plays itself (the in-app player).
 */
@Composable
fun AudioSourceDialog(
    captureState: CaptureState,
    playerState: PlayerState,
    onRequestShizukuPermission: () -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onPickFile: () -> Unit,
    onTogglePause: () -> Unit,
    onStopPlayer: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        modifier = Modifier.fillMaxWidth(0.9f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.menu_audio_source))
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_close),
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                DialogSectionLabel(label = stringResource(R.string.source_capture_section))
                CaptureCard(
                    state = captureState,
                    onOpenShizuku = { openShizuku(context) },
                    onRequestShizukuPermission = onRequestShizukuPermission,
                    onStartCapture = onStartCapture,
                    onStopCapture = onStopCapture,
                )
                captureState.error?.let { NoteText(stringResource(R.string.source_last_error, it), error = true) }
                NoteText(stringResource(R.string.capture_note))

                DialogSectionLabel(label = stringResource(R.string.source_player_section))
                PlayerCard(
                    state = playerState,
                    onPickFile = onPickFile,
                    onTogglePause = onTogglePause,
                    onStopPlayer = onStopPlayer,
                )
                playerState.error?.let { NoteText(stringResource(R.string.source_last_error, it), error = true) }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun CaptureCard(
    state: CaptureState,
    onOpenShizuku: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
) {
    DialogCard {
        InfoRow(
            label = stringResource(R.string.source_status),
            value =
                stringResource(
                    when (state.status) {
                        CaptureState.Status.SHIZUKU_UNAVAILABLE -> R.string.capture_status_unavailable
                        CaptureState.Status.PERMISSION_REQUIRED -> R.string.capture_status_permission
                        CaptureState.Status.READY -> R.string.capture_status_ready
                        CaptureState.Status.STARTING -> R.string.capture_status_starting
                        CaptureState.Status.CAPTURING -> R.string.capture_status_capturing
                    },
                ),
        )
        when (state.status) {
            CaptureState.Status.SHIZUKU_UNAVAILABLE -> {
                RowDivider()
                ActionRow(
                    label = stringResource(R.string.capture_open_shizuku),
                    subtitle = stringResource(R.string.capture_open_shizuku_desc),
                    onClick = onOpenShizuku,
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                )
            }

            CaptureState.Status.PERMISSION_REQUIRED -> {
                RowDivider()
                ActionRow(
                    label = stringResource(R.string.capture_grant),
                    subtitle = stringResource(R.string.capture_grant_desc),
                    onClick = onRequestShizukuPermission,
                    icon = Icons.Default.Key,
                )
            }

            CaptureState.Status.READY -> {
                RowDivider()
                ActionRow(
                    label = stringResource(R.string.capture_start),
                    subtitle = stringResource(R.string.capture_start_desc),
                    onClick = onStartCapture,
                    icon = Icons.Default.PlayArrow,
                )
            }

            CaptureState.Status.STARTING -> {}

            CaptureState.Status.CAPTURING -> {
                RowDivider()
                InfoRow(
                    label = stringResource(R.string.capture_latency),
                    value = stringResource(R.string.capture_latency_value, state.trackLatencyMs + state.backlogMs),
                )
                RowDivider()
                InfoRow(
                    label = stringResource(R.string.capture_underruns),
                    value = state.underruns.toString(),
                )
                RowDivider()
                InfoRow(
                    label = stringResource(R.string.capture_dropped),
                    value = state.framesDropped.toString(),
                )
                RowDivider()
                ActionRow(
                    label = stringResource(R.string.capture_stop),
                    subtitle = stringResource(R.string.capture_stop_desc),
                    onClick = onStopCapture,
                    icon = Icons.Default.Stop,
                )
            }
        }
    }
}

@Composable
private fun PlayerCard(
    state: PlayerState,
    onPickFile: () -> Unit,
    onTogglePause: () -> Unit,
    onStopPlayer: () -> Unit,
) {
    DialogCard {
        InfoRow(
            label = stringResource(R.string.player_now_playing),
            value = state.title?.takeIf { state.active } ?: stringResource(R.string.label_none),
        )
        RowDivider()
        ActionRow(
            label = stringResource(R.string.player_choose_file),
            subtitle = stringResource(R.string.player_choose_file_desc),
            onClick = onPickFile,
            icon = Icons.Default.AudioFile,
        )
        if (state.active) {
            RowDivider()
            if (state.isPlaying) {
                ActionRow(
                    label = stringResource(R.string.player_pause),
                    subtitle = stringResource(R.string.player_pause_desc),
                    onClick = onTogglePause,
                    icon = Icons.Default.Pause,
                )
            } else {
                ActionRow(
                    label = stringResource(R.string.player_play),
                    subtitle = stringResource(R.string.player_play_desc),
                    onClick = onTogglePause,
                    icon = Icons.Default.PlayArrow,
                )
            }
            RowDivider()
            ActionRow(
                label = stringResource(R.string.player_stop),
                subtitle = stringResource(R.string.player_stop_desc),
                onClick = onStopPlayer,
                icon = Icons.Default.Stop,
            )
        }
    }
}

@Composable
private fun NoteText(
    text: String,
    error: Boolean = false,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = UiDimens.Medium, vertical = UiDimens.Standard),
    )
}

/** Opens the Shizuku app to start it, or its download page when it is not installed. */
private fun openShizuku(context: Context) {
    val intent =
        context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, SHIZUKU_DOWNLOAD_URL.toUri())
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.capture_shizuku_missing, Toast.LENGTH_SHORT).show()
    }
}
