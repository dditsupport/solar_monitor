package com.dangeedums.solar.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.dangeedums.solar.SolarApp
import com.dangeedums.solar.ble.DeviceInfoBle
import com.dangeedums.solar.ble.SolarGatt
import com.dangeedums.solar.ble.peripheralForAddress
import com.dangeedums.solar.cloud.CloudClient
import com.dangeedums.solar.sync.BulkSyncManager
import com.dangeedums.solar.sync.DeviceSyncer
import com.juul.kable.NotConnectedException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

enum class ConnState  { Idle, Connecting, Authenticating, Connected, Disconnected, Failed }
enum class SyncStage  { Idle, Reading, Forwarding, Acking, Done, Failed }
enum class ClaimStage { Idle, Submitting, Done, Conflict, Failed }

data class DeviceDetailUi(
    val connState: ConnState = ConnState.Idle,
    val info: DeviceInfoBle? = null,
    val wifi: com.dangeedums.solar.ble.WifiStatus? = null,
    val error: String? = null,
    val syncStage: SyncStage = SyncStage.Idle,
    val syncRows: Int = 0,
    val syncMessage: String = "",
    val claimStage: ClaimStage = ClaimStage.Idle,
    val claimMessage: String = "",
    val commandRunning: Boolean = false,
    val commandMessage: String = "",
)

class DeviceDetailViewModel(
    application: Application,
    private val address: String,
    private val cloud: CloudClient,
    private val syncer: DeviceSyncer,
    private val bulkSync: BulkSyncManager,
) : AndroidViewModel(application) {

    private val peripheral = peripheralForAddress(address)
    val gatt = SolarGatt(peripheral)

    private val _ui = MutableStateFlow(DeviceDetailUi())
    val ui: StateFlow<DeviceDetailUi> = _ui.asStateFlow()

    init {
        connect()
    }

    fun connect() {
        if (_ui.value.connState == ConnState.Connecting) return
        // A Sync-now-all pass may still be holding the radio (possibly on this
        // very device). Opening a device outranks it.
        bulkSync.cancelForUserAction()
        _ui.value = _ui.value.copy(connState = ConnState.Connecting, error = null)
        viewModelScope.launch {
            try {
                withTimeout(20_000) { gatt.connect() }
                // BLE is closed until we prove we hold the pre-shared key. Every
                // read/write below would otherwise be rejected by the firmware.
                _ui.value = _ui.value.copy(connState = ConnState.Authenticating)
                val authed = withTimeout(15_000) { gatt.authenticate() }
                if (!authed) {
                    runCatching { gatt.disconnect() }
                    _ui.value = _ui.value.copy(
                        connState = ConnState.Failed,
                        error = "Authentication failed — the app's pre-shared key doesn't match this device.",
                    )
                    return@launch
                }
                _ui.value = _ui.value.copy(connState = ConnState.Connected)
                refreshInfo()
                // Set wall time from the phone — best-effort, helps the device
                // if its RTC is missing/dead.
                runCatching { gatt.setWallTime(nowIso()) }
                // Keep the Wi-Fi line in Device Info live as the device
                // connects / drops, without a manual refresh.
                gatt.observeWifiStatus()
                    .onEach { _ui.value = _ui.value.copy(wifi = it) }
                    .catch { /* connection ended; ignore */ }
                    .launchIn(viewModelScope)
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(connState = ConnState.Failed, error = t.message ?: "connect failed")
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            runCatching { gatt.disconnect() }
            _ui.value = _ui.value.copy(connState = ConnState.Disconnected)
        }
    }

    fun refreshInfo() {
        viewModelScope.launch { readInfoNow() }
    }

    /** Suspending device-info read so callers can await it (e.g. after a sync). */
    private suspend fun readInfoNow() {
        runCatching { gatt.readDeviceInfo() }
            .onSuccess { _ui.value = _ui.value.copy(info = it, error = null) }
            .onFailure { _ui.value = _ui.value.copy(error = "read info: ${it.message}") }
        // Best-effort Wi-Fi status so the Device Info card can show
        // connected / disconnected without the user opening Configure Wi-Fi.
        runCatching { gatt.readWifiStatus() }.getOrNull()?.let {
            _ui.value = _ui.value.copy(wifi = it)
        }
    }

    /**
     * BLE-relay sync for this one device, driven by the shared [DeviceSyncer]
     * so it behaves identically to the all-devices pass.
     *
     * Passes `trustUnsyncedCount = false`: the user explicitly asked, so read
     * the data stream even if the device's pending counter says zero.
     */
    fun syncNow() {
        viewModelScope.launch {
            try {
                _ui.value = _ui.value.copy(
                    syncStage = SyncStage.Reading, syncRows = 0,
                    syncMessage = "Subscribing to data stream…",
                )
                val result = syncer.syncConnected(gatt, trustUnsyncedCount = false) { p ->
                    _ui.value = when (p) {
                        DeviceSyncer.Progress.Reading -> _ui.value.copy(
                            syncStage = SyncStage.Reading,
                            syncMessage = "Subscribing to data stream…",
                        )
                        is DeviceSyncer.Progress.Forwarding -> _ui.value.copy(
                            syncStage = SyncStage.Forwarding,
                            syncRows = p.rows,
                            syncMessage = "Forwarding ${p.rows} row(s)…",
                        )
                        is DeviceSyncer.Progress.Acking -> _ui.value.copy(
                            syncStage = SyncStage.Acking,
                            syncMessage = "Acking seq ${p.seq}…",
                        )
                    }
                }
                when (result) {
                    is DeviceSyncer.Result.Synced -> {
                        // The syncer already waited for the firmware to act on
                        // the ACK, so the refreshed count is the post-truncate one.
                        readInfoNow()
                        _ui.value = _ui.value.copy(
                            syncStage = SyncStage.Done,
                            syncRows = result.rows,
                            syncMessage = "Synced ${result.rows} row(s).",
                        )
                    }
                    DeviceSyncer.Result.NothingPending ->
                        _ui.value = _ui.value.copy(
                            syncStage = SyncStage.Done,
                            syncMessage = "Nothing to sync.",
                        )
                    is DeviceSyncer.Result.Failed ->
                        _ui.value = _ui.value.copy(
                            syncStage = SyncStage.Failed,
                            syncMessage = result.message,
                        )
                }
            } catch (t: NotConnectedException) {
                _ui.value = _ui.value.copy(syncStage = SyncStage.Failed,
                                            syncMessage = "Connection lost.",
                                            connState = ConnState.Disconnected)
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(syncStage = SyncStage.Failed,
                                            syncMessage = t.message ?: "sync failed")
            }
        }
    }

    /**
     * Register/claim this device with the cloud server under the currently-
     * logged-in user. Requires the user to have already signed in on the
     * Cloud tab (otherwise the server returns 401 / no CSRF token).
     */
    fun claimToCloud(friendlyName: String, location: String?, capacityKw: Double?) {
        val deviceId = _ui.value.info?.deviceId
        if (deviceId.isNullOrBlank()) {
            _ui.value = _ui.value.copy(
                claimStage = ClaimStage.Failed,
                claimMessage = "Read device info first.",
            )
            return
        }
        // No CSRF check here — claimDevice() lazy-refreshes the token if the
        // session cookie is still alive. If the session is genuinely dead the
        // server will respond 401 and we surface that as a friendly message
        // in the onSuccess block below.
        _ui.value = _ui.value.copy(
            claimStage = ClaimStage.Submitting,
            claimMessage = "Registering $deviceId…",
        )
        viewModelScope.launch {
            runCatching {
                cloud.claimDevice(
                    deviceId     = deviceId,
                    friendlyName = friendlyName.ifBlank { deviceId },
                    location     = location?.ifBlank { null },
                    capacityKw   = capacityKw,
                )
            }.onSuccess { resp ->
                _ui.value = when {
                    resp.ok -> _ui.value.copy(
                        claimStage = ClaimStage.Done,
                        claimMessage = if (resp.created) "Registered & bound to your account."
                                       else "Updated & bound to your account.",
                    )
                    resp.error == "owned_by_other_user" -> _ui.value.copy(
                        claimStage = ClaimStage.Conflict,
                        claimMessage = "This device is owned by another user. Ask an admin to re-bind it.",
                    )
                    resp.error == "login_required" || resp.error == "unauthorized" ->
                        _ui.value.copy(
                            claimStage = ClaimStage.Failed,
                            claimMessage = "Sign in on the Cloud tab first, then try again.",
                        )
                    resp.error == "bad_csrf" ->
                        _ui.value.copy(
                            claimStage = ClaimStage.Failed,
                            claimMessage = "Session expired. Sign out & in on the Cloud tab, then retry.",
                        )
                    else -> _ui.value.copy(
                        claimStage = ClaimStage.Failed,
                        claimMessage = resp.error ?: "claim failed",
                    )
                }
            }.onFailure {
                _ui.value = _ui.value.copy(
                    claimStage = ClaimStage.Failed,
                    claimMessage = it.message ?: "network error",
                )
            }
        }
    }

    fun resetClaimState() {
        _ui.value = _ui.value.copy(claimStage = ClaimStage.Idle, claimMessage = "")
    }

    /**
     * Zeroes the PZEM's cumulative energy counter. The firmware only queues
     * the request here — the actual Modbus reset happens on the device's next
     * sample (within ~1s) so it can't race that same task's own PZEM reads.
     * Today/session totals self-heal once the counter rolls backward.
     */
    fun resetPzemEnergy() {
        _ui.value = _ui.value.copy(commandRunning = true, commandMessage = "Resetting energy counter…")
        viewModelScope.launch {
            runCatching { gatt.resetPzemEnergy() }
                .onSuccess { r ->
                    _ui.value = _ui.value.copy(
                        commandRunning = false,
                        commandMessage = if (r.ok) "Energy counter reset requested — applies within a second."
                                         else "Reset failed: ${r.error ?: "unknown error"}",
                    )
                    if (r.ok) {
                        kotlinx.coroutines.delay(1500)
                        readInfoNow()
                    }
                }
                .onFailure {
                    _ui.value = _ui.value.copy(
                        commandRunning = false,
                        commandMessage = "Reset failed: ${it.message ?: "network error"}",
                    )
                }
        }
    }

    /**
     * Factory reset: wipes Wi-Fi credentials, backend host/log-interval
     * overrides, and boot/sync history from the device, then it reboots —
     * the BLE link drops right after. The firmware only queues the request
     * here; the actual flash erase + reboot happen moments later in the
     * device's main loop, not inside the BLE write itself. Device identity
     * (MAC-derived) survives, so the device will show up again under the
     * same name once reconnected, but Wi-Fi will need reconfiguring.
     */
    fun eraseNvs() {
        _ui.value = _ui.value.copy(commandRunning = true, commandMessage = "Requesting device data erase…")
        viewModelScope.launch {
            runCatching { gatt.eraseNvs() }
                .onSuccess { r ->
                    _ui.value = _ui.value.copy(
                        commandRunning = false,
                        commandMessage = if (r.ok)
                            "Erase requested. The device will wipe its data and reboot — " +
                            "reconnect and reconfigure Wi-Fi afterward."
                        else "Erase failed: ${r.error ?: "unknown error"}",
                        connState = if (r.ok) ConnState.Disconnected else _ui.value.connState,
                    )
                }
                .onFailure {
                    _ui.value = _ui.value.copy(
                        commandRunning = false,
                        commandMessage = "Erase request failed: ${it.message ?: "network error"}",
                    )
                }
        }
    }

    fun clearCommandMessage() {
        _ui.value = _ui.value.copy(commandMessage = "")
    }

    private fun nowIso(): String =
        OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch { runCatching { gatt.disconnect() } }
    }

    companion object {
        fun factory(application: Application, address: String) = viewModelFactory {
            initializer {
                val app = application as SolarApp
                DeviceDetailViewModel(
                    application, address,
                    app.cloudClient, app.deviceSyncer, app.bulkSync,
                )
            }
        }
    }
}
