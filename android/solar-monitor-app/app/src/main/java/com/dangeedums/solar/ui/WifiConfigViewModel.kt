package com.dangeedums.solar.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dangeedums.solar.ble.SolarGatt
import com.dangeedums.solar.ble.WifiScanResult
import com.dangeedums.solar.ble.WifiStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class WifiConfigUi(
    val scanning: Boolean = false,
    val networks: List<WifiScanResult> = emptyList(),
    val selected: String = "",
    val password: String = "",
    val status: WifiStatus? = null,
    val log: List<String> = emptyList(),
)

/**
 * Reuses the parent DeviceDetailViewModel's SolarGatt instance — passed in so
 * we don't open a second BLE connection. Caller is responsible for keeping
 * the parent's connection alive while this screen is on-screen.
 */
class WifiConfigViewModel(private val gatt: SolarGatt) : ViewModel() {

    private val _ui = MutableStateFlow(WifiConfigUi())
    val ui: StateFlow<WifiConfigUi> = _ui.asStateFlow()

    init {
        // Live status updates from the device (Wi-Fi Status NOTIFY).
        gatt.observeWifiStatus()
            .onEach { st ->
                _ui.value = _ui.value.copy(
                    status = st,
                    log = (_ui.value.log + "status: ${st.status}").takeLast(20),
                )
            }
            .catch { /* swallow; user can re-open if connection drops */ }
            .launchIn(viewModelScope)

        // Live scan results from the device (Wi-Fi Scan NOTIFY).
        gatt.observeWifiScan()
            .onEach { list ->
                _ui.value = _ui.value.copy(networks = list, scanning = false)
            }
            .catch { /* swallow */ }
            .launchIn(viewModelScope)

        // Kick off an initial scan so the user doesn't have to tap refresh
        // every time they open the screen. Manual SSID entry still works
        // if the scan returns nothing.
        requestScan()
    }

    fun requestScan() {
        _ui.value = _ui.value.copy(scanning = true, networks = emptyList())
        viewModelScope.launch {
            runCatching { gatt.writeWifiConfig("""{"action":"scan"}""") }
                .onFailure {
                    _ui.value = _ui.value.copy(scanning = false,
                        log = (_ui.value.log + "scan write failed: ${it.message}").takeLast(20))
                }
        }
    }

    fun selectSsid(ssid: String) {
        _ui.value = _ui.value.copy(selected = ssid)
    }

    fun setPassword(pw: String) {
        _ui.value = _ui.value.copy(password = pw)
    }

    fun saveCredentials() {
        val ssid = _ui.value.selected
        val pw   = _ui.value.password
        if (ssid.isBlank()) return
        viewModelScope.launch {
            val json = buildString {
                append("""{"ssid":"""); append(quote(ssid))
                append(""","password":"""); append(quote(pw)); append("""}""")
            }
            runCatching { gatt.writeWifiConfig(json) }
                .onFailure {
                    _ui.value = _ui.value.copy(
                        log = (_ui.value.log + "save failed: ${it.message}").takeLast(20))
                }
        }
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        s.forEach { c ->
            when (c) {
                '"', '\\' -> sb.append('\\').append(c)
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
