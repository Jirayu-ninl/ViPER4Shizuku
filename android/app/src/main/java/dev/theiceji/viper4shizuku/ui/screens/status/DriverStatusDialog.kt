package dev.theiceji.viper4shizuku.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import dev.theiceji.viper4shizuku.R
import dev.theiceji.viper4shizuku.ui.components.DialogCard
import dev.theiceji.viper4shizuku.ui.components.InfoRow
import dev.theiceji.viper4shizuku.ui.components.RowDivider
import dev.theiceji.viper4shizuku.ui.screens.main.DriverStatus

@Composable
fun DriverStatusDialog(
    driverStatus: DriverStatus,
    onDismiss: () -> Unit,
) {
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
                Text(stringResource(R.string.menu_engine_status))
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_close),
                    )
                }
            }
        },
        text = {
            if (!driverStatus.installed) {
                Text(
                    text = stringResource(R.string.engine_not_running),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                DialogCard {
                    InfoRow(
                        label = stringResource(R.string.engine_version_name),
                        value = driverStatus.versionName,
                    )
                    RowDivider()
                    InfoRow(
                        label = stringResource(R.string.engine_architecture),
                        value = driverStatus.architecture,
                    )
                    RowDivider()
                    InfoRow(
                        label = stringResource(R.string.engine_streaming),
                        value =
                            if (driverStatus.streaming) {
                                stringResource(R.string.status_active)
                            } else {
                                stringResource(R.string.status_inactive)
                            },
                    )
                    RowDivider()
                    InfoRow(
                        label = stringResource(R.string.engine_sampling_rate),
                        value =
                            if (driverStatus.samplingRate > 0) {
                                "${driverStatus.samplingRate} Hz"
                            } else {
                                stringResource(R.string.status_unknown)
                            },
                    )
                    RowDivider()
                    InfoRow(
                        label = stringResource(R.string.engine_processed_frames),
                        value = driverStatus.processedFrames.toString(),
                    )
                    RowDivider()
                    InfoRow(
                        label = stringResource(R.string.engine_kernel_id),
                        value =
                            if (driverStatus.kernelId != 0) {
                                "0x%08X".format(driverStatus.kernelId)
                            } else {
                                stringResource(R.string.label_none)
                            },
                    )
                }
            }
        },
        confirmButton = {},
    )
}
