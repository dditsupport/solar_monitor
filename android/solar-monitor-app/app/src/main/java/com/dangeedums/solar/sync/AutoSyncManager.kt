package com.dangeedums.solar.sync

import com.dangeedums.solar.ble.BleScanner
import com.dangeedums.solar.ble.SolarGatt
import com.dangeedums.solar.ble.peripheralForAddress
import com.dangeedums.solar.data.Device
import com.dangeedums.solar.data.DeviceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Uploads every saved device's buffered log rows when the app starts.
 *
 * The ESP32 keeps its own store-and-forward buffer and normally drains it over
 * Wi-Fi. Where the device has no usable AP, that buffer only moves when a phone
 * relays it over BLE — which previously meant the user had to open each device
 * and tap "Sync now". This runs that same relay automatically on launch.
 *
 * Devices are processed **strictly one at a time**: a phone's BLE stack handles
 * concurrent GATT connections poorly, and each device's stream/POST/ACK cycle
 * must finish (and disconnect) before the next one starts. One unreachable
 * device costs its connect timeout and then the pass moves on — it never blocks
 * the rest.
 *
 * Runs once per process launch. Nothing here retries in the background: if the
 * pass is skipped (Bluetooth off, no permission) or a device fails, the rows
 * stay safely buffered on the device for the next launch, a manual sync, or the
 * firmware's own Wi-Fi path.
 */
class AutoSyncManager(
    private val scope: CoroutineScope,
    private val deviceStore: DeviceStore,
    private val scanner: BleScanner,
    private val syncer: DeviceSyncer,
) {

    enum class Phase { Idle, Running, Done, Skipped }

    /** Why an app-start pass did not run. */
    enum class SkipReason { None, NoDevices, BluetoothOff, NoPermission }

    sealed interface DeviceState {
        data object Waiting : DeviceState
        data object Connecting : DeviceState
        data object Uploading : DeviceState
        data class Synced(val rows: Int) : DeviceState
        data object NothingPending : DeviceState
        data class Failed(val message: String) : DeviceState
    }

    data class DeviceStatus(
        val address: String,
        val name: String,
        val state: DeviceState = DeviceState.Waiting,
    )

    data class Ui(
        val phase: Phase = Phase.Idle,
        val skipReason: SkipReason = SkipReason.None,
        val statuses: List<DeviceStatus> = emptyList(),
        /** 1-based index of the device currently being processed; 0 when idle. */
        val currentIndex: Int = 0,
        val total: Int = 0,
    ) {
        val rowsUploaded: Int
            get() = statuses.sumOf { (it.state as? DeviceState.Synced)?.rows ?: 0 }
        val failures: Int
            get() = statuses.count { it.state is DeviceState.Failed }
    }

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private var job: Job? = null
    private var startPassDone = false

    /**
     * Run the app-start pass. Safe to call from every Activity.onCreate — it
     * only ever runs once per process, so a rotation or a re-launched activity
     * won't kick off a second sweep.
     */
    fun syncPendingOnStart() {
        if (startPassDone) return
        startPassDone = true
        syncNow()
    }

    /** Run a pass now. No-op while one is already in flight. */
    fun syncNow() {
        if (job?.isActive == true) return
        job = scope.launch { runPass() }
    }

    private suspend fun runPass() {
        val devices = deviceStore.devices.first()
        if (devices.isEmpty()) {
            _ui.value = Ui(phase = Phase.Skipped, skipReason = SkipReason.NoDevices)
            return
        }
        // Both are cheap, and both are hard requirements for a GATT connect.
        if (!scanner.isBluetoothEnabled) {
            _ui.value = Ui(phase = Phase.Skipped, skipReason = SkipReason.BluetoothOff)
            return
        }
        if (!scanner.hasConnectPermission()) {
            _ui.value = Ui(phase = Phase.Skipped, skipReason = SkipReason.NoPermission)
            return
        }

        _ui.value = Ui(
            phase = Phase.Running,
            statuses = devices.map { DeviceStatus(it.address, it.name) },
            total = devices.size,
        )

        devices.forEachIndexed { index, device ->
            _ui.value = _ui.value.copy(currentIndex = index + 1)
            setState(device.address, DeviceState.Connecting)
            val state = syncOneDevice(device)
            setState(device.address, state)
        }

        _ui.value = _ui.value.copy(phase = Phase.Done, currentIndex = 0)
    }

    /** Connect → authenticate → relay → disconnect. Never throws. */
    private suspend fun syncOneDevice(device: Device): DeviceState {
        val gatt = SolarGatt(peripheralForAddress(device.address))
        return try {
            withTimeout(CONNECT_TIMEOUT_MS) { gatt.connect() }
            val authed = withTimeout(AUTH_TIMEOUT_MS) { gatt.authenticate() }
            if (!authed) {
                return DeviceState.Failed("Authentication failed — check the pre-shared key.")
            }
            setState(device.address, DeviceState.Uploading)
            when (val r = syncer.syncConnected(gatt)) {
                is DeviceSyncer.Result.Synced  -> DeviceState.Synced(r.rows)
                is DeviceSyncer.Result.Failed  -> DeviceState.Failed(r.message)
                DeviceSyncer.Result.NothingPending -> DeviceState.NothingPending
            }
        } catch (te: TimeoutCancellationException) {
            // Must be caught BEFORE CancellationException (it is a subclass),
            // otherwise one unreachable device would abort the whole pass
            // instead of just failing its own entry.
            DeviceState.Failed("Timed out — out of range or not responding.")
        } catch (ce: CancellationException) {
            // The pass itself was cancelled — unwind cleanly, don't report a
            // per-device failure. The finally block still disconnects.
            throw ce
        } catch (t: Throwable) {
            DeviceState.Failed(friendlyConnectError(t))
        } finally {
            // Always hand the radio back before moving to the next device.
            // NonCancellable so the disconnect still runs if the pass was
            // cancelled mid-device — otherwise the connection would leak.
            withContext(NonCancellable) { runCatching { gatt.disconnect() } }
        }
    }

    private fun friendlyConnectError(t: Throwable): String {
        val msg = t.message.orEmpty()
        return when {
            msg.contains("permission", ignoreCase = true) ->
                "Bluetooth permission is required."
            msg.isBlank() -> "Couldn't reach the device."
            else -> msg
        }
    }

    private fun setState(address: String, state: DeviceState) {
        _ui.value = _ui.value.copy(
            statuses = _ui.value.statuses.map {
                if (it.address == address) it.copy(state = state) else it
            },
        )
    }

    /**
     * Give up the radio because the user opened a device directly.
     *
     * A phone's BLE stack does not cope with two concurrent GATT connections to
     * the same peripheral, and the pass can hold one for tens of seconds across
     * several devices. The user's explicit action wins: anything not yet
     * uploaded stays buffered on the device for the next pass, a manual sync,
     * or the firmware's own Wi-Fi path.
     */
    fun cancelForUserAction() {
        val j = job ?: return
        if (!j.isActive) return
        j.cancel()
        // The pass coroutine won't reach its own completion line once cancelled,
        // so close out the banner here. In-flight per-device rows keep whatever
        // state they had reached.
        _ui.value = _ui.value.copy(phase = Phase.Done, currentIndex = 0)
    }

    /** Clears a finished pass so the UI banner can be dismissed. */
    fun dismiss() {
        if (_ui.value.phase == Phase.Running) return
        _ui.value = Ui()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val AUTH_TIMEOUT_MS    = 15_000L
    }
}
