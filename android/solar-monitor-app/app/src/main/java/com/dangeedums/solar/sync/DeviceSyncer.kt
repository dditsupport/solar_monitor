package com.dangeedums.solar.sync

import com.dangeedums.solar.ble.SolarGatt
import com.dangeedums.solar.cloud.CloudClient
import com.dangeedums.solar.cloud.IngestBoot
import com.dangeedums.solar.cloud.IngestPayload
import com.dangeedums.solar.cloud.IngestReading
import com.dangeedums.solar.data.CloudSessionStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeout
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * The BLE-relay upload for a single device: pull every buffered row off the
 * ESP32 over GATT, POST it to the cloud, then ACK the highest accepted seq
 * back so the firmware truncates /log.csv.
 *
 * Shared by the manual "Sync now" button on the device detail screen and by
 * [AutoSyncManager]'s app-start pass, so both behave identically — including
 * the rule that matters most: **never ACK unless the server accepted the
 * rows.** A failed upload leaves the buffer intact on the device so the next
 * attempt (BLE or the firmware's own Wi-Fi path) can retry it.
 *
 * Callers own the connection: [syncConnected] expects an already connected and
 * authenticated [SolarGatt] and never connects or disconnects on its own.
 */
class DeviceSyncer(
    private val cloud: CloudClient,
    private val session: CloudSessionStore,
) {

    /** Coarse progress for UI. Emitted in order; not every stage always fires. */
    sealed interface Progress {
        data object Reading : Progress
        data class Forwarding(val rows: Int) : Progress
        data class Acking(val seq: Long) : Progress
    }

    sealed interface Result {
        /** Rows were accepted by the server and ACKed back to the device. */
        data class Synced(val rows: Int, val ackedSeq: Long) : Result
        /** The device had no buffered rows — nothing to do. */
        data object NothingPending : Result
        /** Upload did not complete. The device buffer is untouched. */
        data class Failed(val message: String) : Result
    }

    /**
     * @param gatt a connected + authenticated peripheral wrapper.
     * @param trustUnsyncedCount when true, a device reporting `unsynced_count:
     *   0` is skipped without subscribing to the data stream. That saves a
     *   round trip per idle device, which matters when walking several of them
     *   at app start. The manual "Sync now" button passes false so an explicit
     *   user request always reads the stream, even if the counter is stale.
     */
    suspend fun syncConnected(
        gatt: SolarGatt,
        trustUnsyncedCount: Boolean = true,
        onProgress: (Progress) -> Unit = {},
    ): Result {
        onProgress(Progress.Reading)

        val info = gatt.readDeviceInfo()
        if (trustUnsyncedCount && info.unsyncedCount == 0) return Result.NothingPending

        val boots = gatt.readBootHistory()

        // Accumulate notification chunks until the "END\n" terminator arrives.
        val acc = StringBuilder()
        withTimeout(STREAM_TIMEOUT_MS) {
            gatt.observeDataStream().takeWhile { chunk ->
                acc.append(chunk)
                !chunk.contains("END\n") && !chunk.endsWith("END")
            }.collect { /* accumulating */ }
        }

        val rows = parseCsvChunks(acc.toString())
        if (rows.isEmpty()) return Result.NothingPending

        onProgress(Progress.Forwarding(rows.size))

        val s = session.settings.first()
        val payload = IngestPayload(
            device_id               = info.deviceId,
            fw_version              = info.fw,
            sync_wall_time          = nowIso(),
            current_boot_id         = info.currentBootId,
            current_boot_uptime_sec = info.uptimeSec,
            boot_history            = boots.map { IngestBoot(it.bootId, it.durationSec) },
            readings                = rows,
        )
        val resp = cloud.ingest(s.deviceToken, payload)
        if (!resp.ok) return Result.Failed(friendlyIngestError(resp.error))

        val acked = if (resp.acked_up_to_seq > 0) resp.acked_up_to_seq else rows.maxOf { it.seq }
        onProgress(Progress.Acking(acked))
        gatt.writeSyncAck(acked)
        // Give the firmware a moment to act on the ACK (truncate /log.csv and
        // recompute unsynced_count). Reading Device Info immediately would race
        // and still report the pre-ACK count.
        delay(ACK_SETTLE_MS)
        return Result.Synced(rows.size, acked)
    }

    /** Maps an ingest.php error code to something a user can act on. */
    fun friendlyIngestError(error: String?): String = when (error) {
        "unauthorized"               -> "Sign in on the Cloud tab first, then try again."
        "bad_csrf"                   -> "Session expired. Sign out & in on the Cloud tab, then retry."
        "device_owned_by_other_user" -> "This device is bound to a different user. Ask an admin to re-bind it."
        "missing_fields", "invalid_json" -> "Sync payload was rejected by the server ($error)."
        null                         -> "Server rejected the upload."
        else                         -> "Server: $error"
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

    companion object {
        const val STREAM_TIMEOUT_MS = 60_000L
        const val ACK_SETTLE_MS     = 1_200L
    }
}
