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

    /** {"host":"https://aromen.biz"} */
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
}

/**
 * Build a Peripheral from a MAC address. Kable 0.35+ owns the internal
 * coroutine scope; lifecycle is driven by explicit connect()/disconnect().
 */
fun peripheralForAddress(address: String): Peripheral = Peripheral(address)
