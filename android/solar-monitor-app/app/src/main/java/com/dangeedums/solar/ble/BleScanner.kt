package com.dangeedums.solar.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.dangeedums.solar.data.Device
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Thin wrapper over Android's BluetoothLeScanner.
 *
 * `nearby()` is a cold Flow: collecting starts a scan, cancellation stops it.
 * Results are deduplicated by MAC address and emitted as the discovered set
 * grows. We filter on the firmware's custom service UUID — that's the most
 * reliable way to find Solar Monitor devices because Android 12+ does not
 * always surface the advertising name on first sight of the device.
 *
 * Matching is on the service UUID only (a ScanFilter, so Android drops
 * everything else before it reaches us). A match with no name yet is kept
 * under a placeholder name built from its address.
 */
class BleScanner(private val context: Context) {

    private val manager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager.adapter

    val isBluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    /**
     * Connecting to an already-known MAC needs only BLUETOOTH_CONNECT on
     * Android 12+ (no scan, so no BLUETOOTH_SCAN / location). The Sync now
     * pass uses this — it reconnects to saved addresses directly.
     */
    fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true  // BLUETOOTH is a normal (install-time) permission below API 31
        }
    }

    fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun nearby(): Flow<List<Device>> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val discovered = LinkedHashMap<String, Device>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handle(result)
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::handle)
            }
            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan failed: code=$errorCode"))
            }

            private fun handle(result: ScanResult) {
                val name = try {
                    result.device.name ?: result.scanRecord?.deviceName
                } catch (_: SecurityException) {
                    null
                }
                // The scan filter already matched our service UUID, so this is a
                // Solar Monitor even when Android has not surfaced its name yet;
                // keep it under a placeholder name built from the address.
                val effectiveName = name?.takeIf { it.isNotBlank() }
                    ?: "Solar (${result.device.address.takeLast(5)})"
                val key = result.device.address
                discovered[key] = Device(
                    name = effectiveName,
                    address = key,
                    id = deviceIdFromAdvertisedName(name),
                )
                trySend(discovered.values.toList())
            }
        }

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleUuids.SERVICE))
                .build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(filters, settings, callback)

        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (_: SecurityException) {
                // permission revoked mid-scan
            }
        }
    }
}

/**
 * The firmware advertises as `Solar-<LAST6MAC>` and derives its canonical
 * device_id as `solar-<last6mac>` from the same bytes (see identity.cpp), so
 * the id can be recovered from the advertised name alone — no connection
 * needed. That lets a just-added device be matched against its cloud record
 * straight away, before the first BLE connect fills the id in authoritatively.
 *
 * Returns null when the name is missing or not in the expected shape, in
 * which case the id stays unknown until the device is opened.
 */
internal fun deviceIdFromAdvertisedName(advertisedName: String?): String? {
    val name = advertisedName?.trim().orEmpty()
    val match = Regex("^Solar-([0-9A-Fa-f]{6})$").find(name) ?: return null
    return "solar-" + match.groupValues[1].lowercase()
}
