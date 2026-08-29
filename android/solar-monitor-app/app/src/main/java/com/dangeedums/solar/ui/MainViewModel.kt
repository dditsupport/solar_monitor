package com.dangeedums.solar.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dangeedums.solar.SolarApp
import com.dangeedums.solar.data.Device
import com.dangeedums.solar.data.DeviceStore
import com.dangeedums.solar.ble.BleScanner
import com.dangeedums.solar.cloud.CloudClient
import com.dangeedums.solar.sync.BulkSyncManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class ScanUiState(
    val scanning: Boolean = false,
    val nearby: List<Device> = emptyList(),
    val error: String? = null,
    val needsPermission: Boolean = false,
)

class MainViewModel(
    application: Application,
    private val store: DeviceStore,
    private val scanner: BleScanner,
    private val bulkSync: BulkSyncManager,
    private val cloud: CloudClient,
) : AndroidViewModel(application) {

    val savedDevices: Flow<List<Device>> = store.devices

    // device_id -> cloud friendly_name, refreshed on demand (see
    // refreshCloudNames()). Empty when logged out or before the first fetch —
    // displayDevices then just falls back to each device's local name.
    private val _cloudNames = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * Saved devices with their name overridden by the signed-in user's cloud
     * friendly_name where one is known (matched by the firmware-derived
     * device_id, not the BLE MAC). Falls back to the locally-remembered name
     * for devices that aren't registered, or when nobody is logged in.
     */
    val displayDevices: Flow<List<Device>> = combine(savedDevices, _cloudNames) { local, names ->
        local.map { d ->
            val friendly = d.id?.let { names[it] }
            if (friendly.isNullOrBlank() || friendly == d.name) d else d.copy(name = friendly)
        }
    }

    /**
     * Best-effort refresh of cloud friendly names, meant to be called each
     * time the saved-devices screen is shown (login state can change on the
     * Cloud tab in between visits). Clears the cache on a not-logged-in
     * response so a logout reverts the list to local names promptly; a
     * network failure leaves the previous cache as-is rather than blanking
     * names over a transient blip. This is a display nicety, not core
     * functionality.
     */
    fun refreshCloudNames() {
        viewModelScope.launch {
            runCatching { cloud.devices() }
                .onSuccess { resp ->
                    _cloudNames.value = if (resp.ok) {
                        resp.devices.associate { it.device_id to it.friendly_name }
                    } else {
                        emptyMap()
                    }
                }
        }
    }

    /**
     * Live state of the BLE relay pass. Owned by the application (not this
     * ViewModel) so an in-flight pass survives configuration changes.
     */
    val bulkSyncUi: StateFlow<BulkSyncManager.Ui> = bulkSync.ui

    /**
     * Upload pending rows from every saved device. Bound to the **Sync now**
     * button; the pass checks Bluetooth before it touches any device.
     */
    fun syncAllNow() = bulkSync.syncNow()

    fun dismissSyncBanner() = bulkSync.dismiss()

    private val _scanState = MutableStateFlow(ScanUiState())
    val scanState: StateFlow<ScanUiState> = _scanState.asStateFlow()

    private var scanJob: Job? = null

    fun startScan() {
        if (!scanner.hasScanPermission()) {
            _scanState.value = _scanState.value.copy(
                needsPermission = true, scanning = false, error = null,
            )
            return
        }
        if (!scanner.isBluetoothEnabled) {
            // Clear scanning so the UI shows the Bluetooth-off notice instead of
            // the "Scanning…" placeholder.
            _scanState.value = ScanUiState(
                scanning = false,
                error = "Bluetooth is turned off. Turn it on, then tap Scan.",
            )
            return
        }
        if (scanJob?.isActive == true) return
        _scanState.value = ScanUiState(scanning = true)
        scanJob = viewModelScope.launch {
            try {
                scanner.nearby().collect { list ->
                    _scanState.value = _scanState.value.copy(nearby = list, error = null)
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // Normal: the user navigated away or tapped Stop. Not an error —
                // rethrow so structured concurrency unwinds cleanly and we don't
                // surface "StandaloneCoroutine was cancelled" to the user.
                throw ce
            } catch (t: Throwable) {
                _scanState.value = _scanState.value.copy(
                    scanning = false,
                    error = friendlyScanError(t),
                )
            }
        }
    }

    private fun friendlyScanError(t: Throwable): String {
        val msg = t.message.orEmpty()
        return when {
            msg.contains("code=2", ignoreCase = true) ->
                "Couldn't start scanning. Try turning Bluetooth off and on again."
            msg.contains("code=1", ignoreCase = true) ->
                "A scan is already running. Wait a moment and try again."
            msg.contains("permission", ignoreCase = true) ->
                "Bluetooth permission is required to scan for devices."
            msg.isBlank() ->
                "Couldn't scan for devices. Make sure Bluetooth is on and try again."
            else -> msg
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _scanState.value = _scanState.value.copy(scanning = false)
    }

    fun onPermissionGranted() {
        _scanState.value = _scanState.value.copy(needsPermission = false)
        startScan()
    }

    fun addDevice(device: Device) {
        viewModelScope.launch {
            store.add(device)
            android.widget.Toast.makeText(
                getApplication(),
                "Added ${device.name}",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun removeDevice(address: String) {
        viewModelScope.launch { store.remove(address) }
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = application as SolarApp
                MainViewModel(application, app.deviceStore, app.bleScanner, app.bulkSync, app.cloudClient)
            }
        }
    }
}
