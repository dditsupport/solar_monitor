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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) : AndroidViewModel(application) {

    val savedDevices: Flow<List<Device>> = store.devices

    private val _scanState = MutableStateFlow(ScanUiState())
    val scanState: StateFlow<ScanUiState> = _scanState.asStateFlow()

    private var scanJob: Job? = null

    fun startScan() {
        if (!scanner.hasScanPermission()) {
            _scanState.value = _scanState.value.copy(needsPermission = true)
            return
        }
        if (!scanner.isBluetoothEnabled) {
            _scanState.value = _scanState.value.copy(error = "Bluetooth is off")
            return
        }
        if (scanJob?.isActive == true) return
        _scanState.value = ScanUiState(scanning = true)
        scanJob = viewModelScope.launch {
            try {
                scanner.nearby().collect { list ->
                    _scanState.value = _scanState.value.copy(nearby = list, error = null)
                }
            } catch (t: Throwable) {
                _scanState.value = _scanState.value.copy(error = t.message ?: "scan error")
            }
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
        viewModelScope.launch { store.add(device) }
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
                MainViewModel(application, app.deviceStore, app.bleScanner)
            }
        }
    }
}
