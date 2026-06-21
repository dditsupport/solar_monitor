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
import com.dangeedums.solar.cloud.IngestBoot
import com.dangeedums.solar.cloud.IngestPayload
import com.dangeedums.solar.cloud.IngestReading
import com.dangeedums.solar.data.CloudSessionStore
import com.juul.kable.ConnectionLostException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

enum class ConnState { Idle, Connecting, Connected, Disconnected, Failed }
enum class SyncStage { Idle, Reading, Forwarding, Acking, Done, Failed }

data class DeviceDetailUi(
    val connState: ConnState = ConnState.Idle,
    val info: DeviceInfoBle? = null,
    val error: String? = null,
    val syncStage: SyncStage = SyncStage.Idle,
    val syncRows: Int = 0,
    val syncMessage: String = "",
)

class DeviceDetailViewModel(
    application: Application,
    private val address: String,
    private val cloud: CloudClient,
    private val session: CloudSessionStore,
) : AndroidViewModel(application) {

    private val peripheral = peripheralForAddress(viewModelScope, address)
    val gatt = SolarGatt(peripheral)

    private val _ui = MutableStateFlow(DeviceDetailUi())
    val ui: StateFlow<DeviceDetailUi> = _ui.asStateFlow()

    init {
        connect()
    }

    fun connect() {
        if (_ui.value.connState == ConnState.Connecting) return
        _ui.value = _ui.value.copy(connState = ConnState.Connecting, error = null)
        viewModelScope.launch {
            try {
                withTimeout(20_000) { gatt.connect() }
                _ui.value = _ui.value.copy(connState = ConnState.Connected)
                refreshInfo()
                // Set wall time from the phone — best-effort, helps the device
                // if its RTC is missing/dead.
                runCatching { gatt.setWallTime(nowIso()) }
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
        viewModelScope.launch {
            runCatching { gatt.readDeviceInfo() }
                .onSuccess { _ui.value = _ui.value.copy(info = it, error = null) }
                .onFailure { _ui.value = _ui.value.copy(error = "read info: ${it.message}") }
        }
    }

    /**
     * BLE-relay sync: pull every buffered row off the device, build the
     * ingest payload, POST it to MilesWeb, then ACK the highest seq back
     * to the device so it truncates /log.csv.
     */
    fun syncNow() {
        viewModelScope.launch {
            try {
                _ui.value = _ui.value.copy(syncStage = SyncStage.Reading, syncRows = 0,
                                            syncMessage = "Subscribing to data stream…")
                val info  = gatt.readDeviceInfo()
                val boots = gatt.readBootHistory()

                // Accumulate stream until "END\n" arrives.
                val acc = StringBuilder()
                withTimeout(60_000) {
                    gatt.observeDataStream().takeWhile { chunk ->
                        acc.append(chunk)
                        !chunk.contains("END\n") && !chunk.endsWith("END")
                    }.collect { /* accumulating */ }
                }

                val rows = parseCsvChunks(acc.toString())
                _ui.value = _ui.value.copy(syncRows = rows.size,
                                            syncStage = SyncStage.Forwarding,
                                            syncMessage = "Forwarding ${rows.size} row(s)…")

                if (rows.isEmpty()) {
                    _ui.value = _ui.value.copy(syncStage = SyncStage.Done,
                                                syncMessage = "Nothing to sync.")
                    return@launch
                }

                val s = session.settings.first()
                val payload = IngestPayload(
                    device_id              = info.deviceId,
                    fw_version             = info.fw,
                    sync_wall_time         = nowIso(),
                    current_boot_id        = info.currentBootId,
                    current_boot_uptime_sec= info.uptimeSec,
                    boot_history           = boots.map { IngestBoot(it.bootId, it.durationSec) },
                    readings               = rows,
                )
                val resp = cloud.ingest(s.deviceToken, payload)

                if (!resp.ok) {
                    _ui.value = _ui.value.copy(syncStage = SyncStage.Failed,
                                                syncMessage = "Server: ${resp.error ?: "unknown"}")
                    return@launch
                }

                _ui.value = _ui.value.copy(syncStage = SyncStage.Acking,
                                            syncMessage = "Acking seq ${resp.acked_up_to_seq}…")
                val acked = if (resp.acked_up_to_seq > 0) resp.acked_up_to_seq
                            else rows.maxOf { it.seq }
                gatt.writeSyncAck(acked)
                refreshInfo()
                _ui.value = _ui.value.copy(syncStage = SyncStage.Done,
                                            syncMessage = "Synced ${rows.size} row(s).")
            } catch (t: ConnectionLostException) {
                _ui.value = _ui.value.copy(syncStage = SyncStage.Failed,
                                            syncMessage = "Connection lost.",
                                            connState = ConnState.Disconnected)
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(syncStage = SyncStage.Failed,
                                            syncMessage = t.message ?: "sync failed")
            }
        }
    }

    private fun parseCsvChunks(text: String): List<IngestReading> {
        val out = ArrayList<IngestReading>()
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed == "END") return@forEach
            val parts = trimmed.split(',')
            if (parts.size < 8) return@forEach
            runCatching {
                out += IngestReading(
                    seq     = parts[0].toLong(),
                    boot_id = parts[1].toInt(),
                    sec     = parts[2].toLong(),
                    V  = parts[3].toDouble(),
                    I  = parts[4].toDouble(),
                    P  = parts[5].toDouble(),
                    Wh = parts[6].toDouble(),
                    PF = parts[7].toDouble(),
                    Hz = parts.getOrNull(8)?.toDoubleOrNull(),
                )
            }
        }
        return out
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
                DeviceDetailViewModel(application, address, app.cloudClient, app.cloudSessionStore)
            }
        }
    }
}
