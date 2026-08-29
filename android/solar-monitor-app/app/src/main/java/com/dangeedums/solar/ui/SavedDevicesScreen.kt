package com.dangeedums.solar.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dangeedums.solar.R
import com.dangeedums.solar.data.Device
import com.dangeedums.solar.sync.BulkSyncManager
import com.dangeedums.solar.sync.BulkSyncManager.DeviceState

@Composable
fun SavedDevicesScreen(
    devices: List<Device>,
    onRemove: (String) -> Unit,
    onOpen:   (Device) -> Unit = {},
    syncUi: BulkSyncManager.Ui = BulkSyncManager.Ui(),
    onSyncNow: () -> Unit = {},
    onDismissSync: () -> Unit = {},
) {
    if (devices.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.empty_saved),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    // Per-device sync state, keyed by MAC so each card can show its own line.
    val statusByAddress = syncUi.statuses.associateBy { it.address }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.title_devices),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                // Uploads pending rows from every saved device, one at a time.
                // Disabled while a pass is running so repeat taps can't stack.
                Button(
                    onClick = onSyncNow,
                    enabled = syncUi.phase != BulkSyncManager.Phase.Running,
                ) {
                    Text(
                        if (syncUi.phase == BulkSyncManager.Phase.Running) "Syncing…"
                        else stringResource(R.string.action_sync_now)
                    )
                }
            }
        }
        item { BulkSyncBanner(syncUi, onSyncNow, onDismissSync) }
        items(devices, key = { it.address }) { device ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onOpen(device) },
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = device.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = device.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        device.id?.let { canonical ->
                            Text(text = canonical, style = MaterialTheme.typography.bodySmall)
                        }
                        statusByAddress[device.address]?.state?.let { state ->
                            Text(
                                text  = state.label(),
                                style = MaterialTheme.typography.bodySmall,
                                color = state.tint(),
                            )
                        }
                    }
                    IconButton(onClick = { onRemove(device.address) }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_remove))
                    }
                }
            }
        }
    }
}

/**
 * Summarises the BLE relay pass: progress while it walks the devices, the
 * outcome once done, and an actionable prompt when it couldn't run at all.
 * Renders nothing when there is nothing worth saying.
 */
@Composable
private fun BulkSyncBanner(
    ui: BulkSyncManager.Ui,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val message: String = when (ui.phase) {
        BulkSyncManager.Phase.Running ->
            "Uploading buffered readings — device ${ui.currentIndex} of ${ui.total}…"
        BulkSyncManager.Phase.Done -> when {
            ui.rowsUploaded > 0 && ui.failures > 0 ->
                "Uploaded ${ui.rowsUploaded} reading(s). ${ui.failures} device(s) couldn't be reached."
            ui.rowsUploaded > 0 -> "Uploaded ${ui.rowsUploaded} buffered reading(s)."
            ui.failures > 0     -> "Couldn't reach ${ui.failures} device(s). Rows are still safe on the device."
            else                -> "All devices are up to date."
        }
        BulkSyncManager.Phase.Skipped -> when (ui.skipReason) {
            BulkSyncManager.SkipReason.BluetoothOff ->
                "Bluetooth is off. Turn it on, then tap Sync now to upload buffered readings."
            BulkSyncManager.SkipReason.NoPermission ->
                "Bluetooth permission is needed to upload readings from your devices."
            else -> return  // NoDevices / None: nothing useful to say
        }
        BulkSyncManager.Phase.Idle -> return
    }

    val running = ui.phase == BulkSyncManager.Phase.Running
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
            if (running) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (ui.phase == BulkSyncManager.Phase.Skipped || ui.failures > 0) {
                        TextButton(onClick = onRetry) { Text("Retry") }
                    }
                    TextButton(onClick = onDismiss) { Text("Dismiss") }
                }
            }
        }
    }
}

private fun DeviceState.label(): String = when (this) {
    DeviceState.Waiting        -> "Waiting to sync…"
    DeviceState.Connecting     -> "Connecting…"
    DeviceState.Uploading      -> "Uploading…"
    DeviceState.NothingPending -> "Up to date"
    is DeviceState.Synced      -> "Synced $rows reading(s)"
    is DeviceState.Failed      -> message
}

@Composable
private fun DeviceState.tint(): Color = when (this) {
    is DeviceState.Failed -> MaterialTheme.colorScheme.error
    is DeviceState.Synced -> MaterialTheme.colorScheme.primary
    else                  -> MaterialTheme.colorScheme.onSurfaceVariant
}
