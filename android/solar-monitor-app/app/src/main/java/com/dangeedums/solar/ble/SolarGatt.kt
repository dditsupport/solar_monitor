package com.dangeedums.solar.ble

import com.juul.kable.AndroidPeripheral
import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val SERVICE = BleUuids.SERVICE.toString()

private val DEVICE_INFO_CHAR    = characteristicOf(SERVICE, BleUuids.DEVICE_INFO.toString())
private val SET_WALL_TIME_CHAR  = characteristicOf(SERVICE, BleUuids.SET_WALL_TIME.toString())
private val BOOT_HISTORY_CHAR   = characteristicOf(SERVICE, BleUuids.BOOT_HISTORY.toString())
private val DATA_STREAM_CHAR    = characteristicOf(SERVICE, BleUuids.DATA_STREAM.toString())
private val SYNC_ACK_CHAR       = characteristicOf(SERVICE, BleUuids.SYNC_ACK.toString())
private val WIFI_CONFIG_CHAR    = characteristicOf(SERVICE, BleUuids.WIFI_CONFIG.toString())
private val WIFI_STATUS_CHAR    = characteristicOf(SERVICE, BleUuids.WIFI_STATUS.toString())
private val WIFI_SCAN_CHAR      = characteristicOf(SERVICE, BleUuids.WIFI_SCAN.toString())
private val SERVER_CONFIG_CHAR  = characteristicOf(SERVICE, BleUuids.SERVER_CONFIG.toString())
private val AUTH_CHALLENGE_CHAR = characteristicOf(SERVICE, BleUuids.AUTH_CHALLENGE.toString())
private val AUTH_RESPONSE_CHAR  = characteristicOf(SERVICE, BleUuids.AUTH_RESPONSE.toString())
private val DEVICE_COMMAND_CHAR = characteristicOf(SERVICE, BleUuids.DEVICE_COMMAND.toString())
private val COMMAND_RESULT_CHAR = characteristicOf(SERVICE, BleUuids.COMMAND_RESULT.toString())

/**
 * Higher-level operations on a Solar Monitor peripheral. One instance per
 * connection. Caller is responsible for calling [connect] before any other
 * method and [close] when done.
 */
class SolarGatt(
    private val peripheral: Peripheral,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    val state: Flow<State> = peripheral.state

    suspend fun connect() {
        peripheral.connect()
        // Request a larger MTU so the data-stream chunks fit. Negotiated value
        // ends up being min(requested, server-supported). 247 matches what the
        // firmware sets via NimBLEDevice::setMTU.
        (peripheral as? AndroidPeripheral)?.requestMtu(247)
    }

    suspend fun disconnect() = peripheral.disconnect()

    /** Current Auth Challenge: the firmware's per-connection nonce + auth flag. */
    suspend fun readAuthChallenge(): AuthChallenge {
        val text = peripheral.read(AUTH_CHALLENGE_CHAR).decodeToString()
        return json.decodeFromString(AuthChallenge.serializer(), text)
    }

    /**
     * Prove possession of the pre-shared key so the firmware opens the
     * sensitive characteristics. Reads the current nonce, writes
     * HMAC_SHA256(key, nonce), then confirms the firmware flipped
     * `authenticated` to true. Returns true on success.
     *
     * Must be called after [connect] and before any other operation — until it
     * succeeds every other read returns {"error":"unauthorized"} and every
     * write is silently dropped by the firmware.
     */
    suspend fun authenticate(key: String = BleAuth.PRESHARED_KEY): Boolean {
        val challenge = readAuthChallenge()
        if (challenge.authenticated) return true
        if (challenge.nonce.isBlank()) return false

        val response = BleAuth.response(challenge.nonce, key)
        peripheral.write(AUTH_RESPONSE_CHAR, response.toByteArray(), WriteType.WithResponse)

        // The firmware sets authenticated=true (or rotates the nonce on
        // failure) synchronously in its write callback; re-read to confirm.
        // Retry a couple of times to absorb any propagation delay.
        repeat(5) {
            val c = runCatching { readAuthChallenge() }.getOrNull()
            if (c?.authenticated == true) return true
            kotlinx.coroutines.delay(150)
        }
        return false
    }

    suspend fun readDeviceInfo(): DeviceInfoBle {
        val bytes = peripheral.read(DEVICE_INFO_CHAR)
        return json.decodeFromString(DeviceInfoBle.serializer(), bytes.decodeToString())
    }

    suspend fun readBootHistory(): List<BootRecord> {
        val bytes = peripheral.read(BOOT_HISTORY_CHAR)
        val text = bytes.decodeToString()
        if (text.isBlank() || text == "[]") return emptyList()
        return json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(BootRecord.serializer()), text)
    }

    /** ISO 8601 string, e.g. "2026-06-20T17:24:32+05:30". */
    suspend fun setWallTime(iso8601: String) {
        peripheral.write(SET_WALL_TIME_CHAR, iso8601.toByteArray(), WriteType.WithResponse)
    }

    /** {"ssid":"...","password":"..."} or {"action":"scan"} */
    suspend fun writeWifiConfig(json: String) {
        peripheral.write(WIFI_CONFIG_CHAR, json.toByteArray(), WriteType.WithResponse)
    }

    /** {"host":"https://solar.aromen.biz"} */
    suspend fun writeServerConfig(json: String) {
        peripheral.write(SERVER_CONFIG_CHAR, json.toByteArray(), WriteType.WithResponse)
    }

    /** Highest seq the server has acknowledged. ESP32 truncates /log.csv up to it. */
    suspend fun writeSyncAck(seq: Long) {
        peripheral.write(SYNC_ACK_CHAR, seq.toString().toByteArray(), WriteType.WithResponse)
    }

    /** Last-known Wi-Fi status. Returns null if char is empty / unparseable. */
    suspend fun readWifiStatus(): WifiStatus? = runCatching {
        val text = peripheral.read(WIFI_STATUS_CHAR).decodeToString()
        if (text.isBlank()) null
        else json.decodeFromString(WifiStatus.serializer(), text)
    }.getOrNull()

    /** Live Wi-Fi status pushes from the device. */
    fun observeWifiStatus(): Flow<WifiStatus> = peripheral.observe(WIFI_STATUS_CHAR).map {
        json.decodeFromString(WifiStatus.serializer(), it.decodeToString())
    }

    /** Live Wi-Fi scan results — emits whenever the firmware completes a scan. */
    fun observeWifiScan(): Flow<List<WifiScanResult>> = peripheral.observe(WIFI_SCAN_CHAR).map { bytes ->
        val text = bytes.decodeToString()
        if (text.isBlank() || text == "[]") emptyList()
        else json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(WifiScanResult.serializer()),
            text,
        )
    }

    suspend fun readWifiScan(): List<WifiScanResult> {
        val text = peripheral.read(WIFI_SCAN_CHAR).decodeToString()
        return if (text.isBlank() || text == "[]") emptyList()
        else json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(WifiScanResult.serializer()),
            text,
        )
    }

    /**
     * Subscribes to the Data Stream characteristic and emits each chunk as
     * a string. Terminator chunk "END\n" is included so the caller can
     * detect end-of-stream and stop accumulating.
     */
    fun observeDataStream(): Flow<String> =
        peripheral.observe(DATA_STREAM_CHAR).map { it.decodeToString() }

    /**
     * Requests zeroing the PZEM's cumulative energy counter. `ok:true` means
     * the firmware queued the request — the actual Modbus reset happens on
     * its next sample (within ~1s), not before this call returns. Today/
     * session totals on the device self-heal once the counter rolls back.
     */
    suspend fun resetPzemEnergy(): CommandResult = sendDeviceCommand("""{"cmd":"reset_pzem"}""")

    /**
     * Requests a factory reset: wipes Wi-Fi credentials, ingest host/log-
     * interval overrides, and boot/sync history from the device's NVS, then
     * it reboots. `ok:true` means the firmware queued the request — the
     * actual wipe + reboot happen moments later in its main loop, not before
     * this call returns. Device identity (derived from its MAC) is
     * unaffected. The BLE link drops once the device actually reboots.
     */
    suspend fun eraseNvs(): CommandResult =
        sendDeviceCommand("""{"cmd":"erase_nvs","confirm":true}""")

    // Firmware executes the write's onWrite() callback — including setting
    // the Command Result value — synchronously before it sends back the ATT
    // write response, so by the time this WithResponse write suspend returns,
    // a plain read already sees the fresh result (same read-after-write
    // pattern used for Wi-Fi config elsewhere in this class).
    private suspend fun sendDeviceCommand(cmdJson: String): CommandResult {
        peripheral.write(DEVICE_COMMAND_CHAR, cmdJson.toByteArray(), WriteType.WithResponse)
        val text = peripheral.read(COMMAND_RESULT_CHAR).decodeToString()
        return json.decodeFromString(CommandResult.serializer(), text)
    }
}

/**
 * Build a Peripheral from a MAC address. Kable 0.35+ owns the internal
 * coroutine scope; lifecycle is driven by explicit connect()/disconnect().
 */
fun peripheralForAddress(address: String): Peripheral = Peripheral(address)
