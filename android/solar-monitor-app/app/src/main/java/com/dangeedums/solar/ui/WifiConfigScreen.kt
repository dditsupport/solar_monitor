package com.dangeedums.solar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dangeedums.solar.ble.WifiScanResult

@Composable
fun WifiConfigScreen(vm: WifiConfigViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Configure Wi-Fi", style = MaterialTheme.typography.titleLarge,
                 modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.requestScan() }) {
                if (ui.scanning) CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                else             Icon(Icons.Default.Refresh, contentDescription = "Scan")
            }
        }

        ui.status?.let { st ->
            Spacer(Modifier.height(4.dp))
            Card(elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                 modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Device status: ${st.status}", fontWeight = FontWeight.Medium)
                    st.ssid?.let   { Text("SSID: $it",     style = MaterialTheme.typography.bodySmall) }
                    st.next?.let   { Text("Next: $it",     style = MaterialTheme.typography.bodySmall) }
                    st.detail?.let { Text("Detail: $it",   style = MaterialTheme.typography.bodySmall,
                                          color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("Networks", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))

        if (ui.networks.isEmpty()) {
            Text(
                if (ui.scanning) "Scanning…"
                else "Tap the refresh icon to scan via the device.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp),
                       modifier = Modifier.weight(1f, fill = false)) {
                items(ui.networks, key = { it.ssid + it.rssi }) { net ->
                    NetworkRow(net = net,
                               selected = net.ssid == ui.selected,
                               onClick = { vm.selectSsid(net.ssid) })
                }
            }
        }

        if (ui.selected.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = ui.password,
                onValueChange = vm::setPassword,
                label = { Text("Password for ${ui.selected}") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.saveCredentials() }, modifier = Modifier.fillMaxWidth()) {
                Text("Save & connect")
            }
        }
    }
}

@Composable
private fun NetworkRow(net: WifiScanResult, selected: Boolean, onClick: () -> Unit) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier  = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (net.isEncrypted) {
                Icon(Icons.Default.Lock, contentDescription = "Encrypted",
                     modifier = Modifier.padding(end = 8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(net.ssid, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                Text("RSSI ${net.rssi} dBm",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onClick) {
                Text(if (selected) "Selected" else "Use")
            }
        }
    }
}
