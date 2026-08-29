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
import com.dangeedums.solar.data.DeviceStore
import com.dangeedums.solar.sync.BulkSyncManager
import com.dangeedums.solar.sync.DeviceSyncer
import com.juul.kable.NotConnectedException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

enum class ConnState  { Idle, Connecting, Authenticating, Connected, Disconnected, Failed }
enum class SyncStage  { Idle, Reading, Forwarding, Acking, Done, Failed }
enum class ClaimStage { Idle, Submitting, Done, Failed }

/**
 * Whether "Erase device data" may run. The wipe restarts the firmware's seq
 * counter at 1, and solar_readings keys on (device_id, seq) — so any cloud row
 * that outlives the wipe shadows a new reading, which ingest.php then drops
 * while still acking it, and the firmware deletes its only copy. The device is
 * therefore only erased when this app can clear those rows in the same breath,
 * which takes a live session that owns the device.
 */
enum class EraseGate {
    Unknown,        // device_id not read yet
    Checking,
    Allowed,        // signed in, and this device is ours (or we're admin)
    NotRegistered,  // signed in; device unknown to the cloud, so nothing to clear
    NeedsLogin,
    NotYours,
    Unreachable,
}

/** The two states with no cloud rows at risk: ours to delete, or none exist. */
val EraseGate.permitsErase: Boolean
    get() = this == EraseGate.Allowed || this == EraseGate.NotRegistered

/** Attempts at the post-wipe cloud cleanup before reporting it unfinished. */
private const val CLOUD_CLEAR_ATTEMPTS = 3

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
    val eraseGate: EraseGate = EraseGate.Unknown,
    val eraseGateMessage: String = "",
)

class DeviceDetailViewModel(
    application: Application,
    private val address: String,
    private val cloud: CloudClient,
    private val syncer: DeviceSyncer,
    private val bulkSync: BulkSyncManager,
    private val store: DeviceStore,
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
            .onSuccess {
                _ui.value = _ui.value.copy(info = it, error = null)
                persistDeviceId(it.deviceId)
            }
            .onFailure { _ui.value = _ui.value.copy(error = "read info: ${it.message}") }
        // Best-effort Wi-Fi status so the Device Info card can show
        // connected / disconnected without the user opening Configure Wi-Fi.
        runCatching { gatt.readWifiStatus() }.getOrNull()?.let {
            _ui.value = _ui.value.copy(wifi = it)
        }
    }

    /**
     * Saves the firmware-derived device_id onto this device's entry in the
     * saved-devices store. Scanning alone often can't fill this in (the BLE
     * advertised name isn't always captured — see BleScanner's MAC-based
     * fallback name), so it stays null for a saved device until the first
     * successful connect. Without it, nothing that matches on device_id
     * (e.g. the cloud friendly_name overlay on the My devices list) can find
     * this device.
     */
    private suspend fun persistDeviceId(deviceId: String) {
        val current = store.devices.first().firstOrNull { it.address == address } ?: return
        if (current.id == deviceId) return
        store.add(current.copy(id = deviceId))
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
     * Cloud tab (otherwise the server returns 401 / no CSRF token). Claiming
     * is self-service reassignment: if the device already belongs to a
     * different user, this hands it to whoever is logged in now.
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
     *
     * Requires a cloud session that owns this device — see [EraseGate]. The
     * gate is re-checked here rather than trusted from the last screen refresh.
     */
    fun eraseNvs() {
        val deviceId = _ui.value.info?.deviceId
        _ui.value = _ui.value.copy(commandRunning = true, commandMessage = "Checking cloud sign-in…")
        viewModelScope.launch {
            // Preflight. A session can lapse between opening this screen and
            // confirming the dialog, and the wipe can't be taken back once the
            // firmware has queued it — so check before touching the device,
            // not after.
            val gate = evaluateEraseGate()
            if (!gate.permitsErase) {
                _ui.value = _ui.value.copy(
                    commandRunning = false,
                    commandMessage = "Erase cancelled — ${eraseGateText(gate)}",
                )
                return@launch
            }
            _ui.value = _ui.value.copy(commandMessage = "Requesting device data erase…")
            runCatching { gatt.eraseNvs() }
                .onSuccess { r ->
                    if (!r.ok) {
                        _ui.value = _ui.value.copy(
                            commandRunning = false,
                            commandMessage = "Erase failed: ${r.error ?: "unknown error"}",
                        )
                        return@onSuccess
                    }
                    // The device's seq counter restarts at 1 after the wipe, and
                    // the server keys readings on (device_id, seq) — stale rows
                    // would shadow every new reading and get it silently dropped.
                    // Clear them so both sides start from the same clean slate.
                    val cloudNote = clearCloudData(deviceId)
                    _ui.value = _ui.value.copy(
                        commandRunning = false,
                        commandMessage = "Erase requested. The device will wipe its data and reboot — " +
                            "reconnect and reconfigure Wi-Fi afterward. $cloudNote",
                        connState = ConnState.Disconnected,
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

    /**
     * Re-evaluates whether the erase may run, and publishes the reason when it
     * may not. devices.php returns exactly the set this account is allowed to
     * reset — every device for an admin, owned devices otherwise — so
     * membership in it mirrors the ownership check inside reset_device_data.php
     * without needing a second contract.
     */
    fun checkEraseGate() {
        viewModelScope.launch { evaluateEraseGate() }
    }

    private suspend fun evaluateEraseGate(): EraseGate {
        val deviceId = _ui.value.info?.deviceId
        if (deviceId.isNullOrBlank()) return applyEraseGate(EraseGate.Unknown)
        _ui.value = _ui.value.copy(eraseGate = EraseGate.Checking, eraseGateMessage = "")
        val gate = runCatching { cloud.devices() }.fold(
            onSuccess = { resp ->
                when {
                    !resp.ok -> EraseGate.NeedsLogin
                    resp.devices.any { it.device_id == deviceId } -> EraseGate.Allowed
                    // Signed in, but not in our list: either the cloud has never
                    // heard of this device (no rows, so erasing is harmless) or
                    // it is someone else's (rows we cannot delete). device_names
                    // .php needs no session and separates the two.
                    isRegisteredInCloud(deviceId) -> EraseGate.NotYours
                    else -> EraseGate.NotRegistered
                }
            },
            onFailure = { EraseGate.Unreachable },
        )
        return applyEraseGate(gate)
    }

    /** Defaults to true: an unanswerable lookup should block the erase, not wave it through. */
    private suspend fun isRegisteredInCloud(deviceId: String): Boolean =
        runCatching { cloud.deviceNames(listOf(deviceId)) }
            .getOrNull()?.names?.containsKey(deviceId) ?: true

    private fun applyEraseGate(gate: EraseGate): EraseGate {
        _ui.value = _ui.value.copy(eraseGate = gate, eraseGateMessage = eraseGateText(gate))
        return gate
    }

    private fun eraseGateText(gate: EraseGate): String = when (gate) {
        EraseGate.Allowed       -> ""
        EraseGate.Checking      -> "Checking your cloud sign-in…"
        EraseGate.NotRegistered ->
            "This device isn't registered in the cloud, so the erase affects the device only."
        EraseGate.NeedsLogin    ->
            "Sign in on the Cloud tab first. The wipe restarts this device's reading counter, " +
            "so its cloud readings have to be cleared at the same time."
        EraseGate.NotYours      ->
            "This device belongs to another account, so its cloud readings can't be cleared from " +
            "here. Ask its owner or an admin to run the erase."
        EraseGate.Unreachable   ->
            "Can't reach the server to check your sign-in. Reconnect and try again."
        EraseGate.Unknown       ->
            "The device ID hasn't been read yet — reconnect to this device and try again."
    }

    /**
     * Wipes this device's readings on the server, run straight after the device
     * wipe. The device is already erased by the time this runs, so a failure
     * here is precisely the state the preflight exists to prevent — stale rows
     * shadowing every reading the device posts next. Retried a few times before
     * being reported, and reported loudly when it still doesn't land.
     */
    private suspend fun clearCloudData(deviceId: String?): String {
        if (deviceId.isNullOrBlank()) {
            return "Cloud readings were left alone (device ID unknown — open the device and retry)."
        }
        var last = ""
        repeat(CLOUD_CLEAR_ATTEMPTS) { attempt ->
            val outcome = attemptCloudClear(deviceId)
            if (outcome.settled) return outcome.message
            last = outcome.message
            if (attempt < CLOUD_CLEAR_ATTEMPTS - 1) {
                kotlinx.coroutines.delay(1500L * (attempt + 1))
            }
        }
        return "$last Re-run Erase once the server is reachable — until those rows are gone, " +
               "readings this device posts will be dropped."
    }

    /** [settled] marks an outcome no retry can improve on: done, or refused for good. */
    private data class CloudClearOutcome(val message: String, val settled: Boolean)

    private suspend fun attemptCloudClear(deviceId: String): CloudClearOutcome =
        runCatching { cloud.resetDeviceData(deviceId) }.fold(
            onSuccess = { r ->
                when {
                    r.ok -> CloudClearOutcome(
                        "Cleared ${r.rows_deleted} cloud reading(s) for this device.", true)
                    r.error == "login_required" || r.error == "unauthorized" -> CloudClearOutcome(
                        "Cloud readings were NOT cleared — your session expired mid-erase. " +
                        "Sign in on the Cloud tab, then use Erase again.", true)
                    r.error == "not_your_device" -> CloudClearOutcome(
                        "Cloud readings were NOT cleared — this device belongs to another account.", true)
                    r.error == "unknown_device" -> CloudClearOutcome(
                        "No cloud readings to clear (device isn't registered).", true)
                    else -> CloudClearOutcome(
                        "Cloud readings were NOT cleared: ${r.error ?: "unknown error"}.", false)
                }
            },
            onFailure = { CloudClearOutcome(
                "Cloud readings were NOT cleared: ${it.message ?: "network error"}.", false) },
        )

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
                    app.cloudClient, app.deviceSyncer, app.bulkSync, app.deviceStore,
                )
            }
        }
    }
}
