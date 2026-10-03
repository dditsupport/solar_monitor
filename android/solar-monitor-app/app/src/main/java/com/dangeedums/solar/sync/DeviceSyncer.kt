package com.dangeedums.solar.sync

import com.dangeedums.solar.ble.SolarGatt
import com.dangeedums.solar.cloud.CloudClient
import com.dangeedums.solar.cloud.IngestBoot
import com.dangeedums.solar.cloud.IngestPayload
import com.dangeedums.solar.cloud.IngestReading
import com.dangeedums.solar.data.CloudSessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeout
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The BLE-relay upload for a single device: pull every buffered row off the
 * ESP32 over GATT, POST it to the cloud, then ACK the highest accepted seq
 * back so the firmware truncates /log.csv.
 *
 * Shared by the per-device "Sync now" button on the detail screen and by
 * [BulkSyncManager]'s all-devices pass, so both behave identically — including
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
     *   in one pass. The per-device "Sync now" button passes false so a request
     *   aimed at one device always reads the stream, even if the counter is stale.
     */
    suspend fun syncConnected(
        gatt: SolarGatt,
        trustUnsyncedCount: Boolean = true,
        // Internal: set on the one automatic retry after the device renumbered.
        // Kept before onProgress so callers can still pass that as a trailing lambda.
        afterSeqBase: Boolean = false,
        onProgress: (Progress) -> Unit = {},
    ): Result {
        onProgress(Progress.Reading)

        val info = gatt.readDeviceInfo()
        // The phone's clock at the moment the device reported its uptime. The
        // server places current-boot rows at sync_wall_time - uptime + sec, so
        // the two must be read together: stamping "now" at upload time instead
        // shifted every row later by however long the stream and upload took —
        // minutes, for a big backlog.
        val syncWall = nowIso()
        if (trustUnsyncedCount && info.unsyncedCount == 0) return Result.NothingPending

        val boots = gatt.readBootHistory()

        // Accumulate notification chunks until the "END\n" terminator arrives.
        // The timeout scales with the backlog: the device streams every buffered
        // row in one go, and a flat 60 s could never finish a long-offline
        // device's backlog, so its sync failed every time.
        val acc = StringBuilder()
        withTimeout(STREAM_TIMEOUT + STREAM_TIME_PER_ROW * info.unsyncedCount) {
            gatt.observeDataStream().takeWhile { chunk ->
                acc.append(chunk)
                !chunk.contains("END\n") && !chunk.endsWith("END")
            }.collect { /* accumulating */ }
        }

        val rows = parseCsvChunks(acc.toString()).sortedBy { it.seq }
        if (rows.isEmpty()) return Result.NothingPending

        onProgress(Progress.Forwarding(rows.size))

        // Upload in chunks: one POST carrying a whole long-offline backlog
        // (tens of thousands of rows) risked the server's request-size and
        // execution-time limits, and then nothing got through at all. Each
        // chunk is acked by the server independently; if one fails, the device
        // is still told about everything accepted before it.
        val s = session.settings.first()
        var acked = 0L
        var sent = 0
        var failure: String? = null
        for (chunk in rows.chunked(UPLOAD_CHUNK_ROWS)) {
            val payload = IngestPayload(
                deviceId             = info.deviceId,
                fwVersion            = info.fw,
                syncWallTime         = syncWall,
                currentBootId        = info.currentBootId,
                currentBootUptimeSec = info.uptimeSec,
                bootHistory          = boots.map { IngestBoot(it.bootId, it.durationSec) },
                readings             = chunk,
                seqFresh             = info.seqFresh,
                // Whatever the device reported when we read Device Info for this
                // sync. This is the only heap sample that exists for a device that
                // cannot reach the server on its own. Sent once, not per chunk.
                heapFree             = if (sent == 0) info.heapFree else null,
                heapLargest          = if (sent == 0) info.heapLargest else null,
                heapMin              = if (sent == 0) info.heapMin else null,
                heapSource           = if (sent == 0) "ble" else null,
            )
            val resp = try {
                cloud.ingest(s.deviceToken, payload)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                failure = e.message ?: "upload failed"
                break
            }
            if (!resp.ok && resp.error == "seq_base_required" && resp.seqBase != null) {
                // The server stored nothing from this chunk: the device's
                // reading counter restarted (flash erase, new board, factory
                // reset) or a number clashed with a different stored reading.
                // Ack what earlier chunks got accepted (old numbering — the
                // firmware applies the ack before the renumber), have the
                // device renumber above the server's maximum, then re-read and
                // send everything again, once.
                if (acked > 0) gatt.writeSyncAck(acked)
                if (afterSeqBase) return Result.Failed("Device and server still disagree on reading numbers.")
                gatt.setSeqBase(resp.seqBase)
                delay(ACK_SETTLE)
                return syncConnected(gatt, trustUnsyncedCount = false, onProgress = onProgress, afterSeqBase = true)
            }
            if (!resp.ok) { failure = friendlyIngestError(resp.error); break }
            acked = maxOf(acked, if (resp.ackedUpToSeq > 0) resp.ackedUpToSeq else chunk.maxOf { it.seq })
            sent += chunk.size
            onProgress(Progress.Forwarding(rows.size - sent))
        }
        // Never ACK more than the server accepted; with nothing accepted, the
        // device buffer stays untouched for the next attempt.
        if (acked == 0L) return Result.Failed(failure ?: "Server rejected the upload.")

        onProgress(Progress.Acking(acked))
        gatt.writeSyncAck(acked)
        // Give the firmware a moment to act on the ACK (truncate /log.csv and
        // recompute unsynced_count). Reading Device Info immediately would race
        // and still report the pre-ACK count.
        delay(ACK_SETTLE)
        if (failure != null) {
            return Result.Failed("Uploaded $sent of ${rows.size} rows, then: $failure")
        }
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
            if (parts.size != 10) return@forEach
            runCatching {
                out += IngestReading(
                    seq         = parts[0].toLong(),
                    bootId      = parts[1].toInt(),
                    sec         = parts[2].toLong(),
                    voltage     = parts[3].toDouble(),
                    current     = parts[4].toDouble(),
                    power       = parts[5].toDouble(),
                    energyWh    = parts[6].toDouble(),
                    powerFactor = parts[7].toDouble(),
                    frequency   = parts[8].toDouble(),
                    // Logged-at epoch; 0 when the device's clock was unknown.
                    t           = parts[9].toLong().takeIf { it > 0 },
                )
            }
        }
        return out
    }

    private fun nowIso(): String =
        OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    companion object {
        val STREAM_TIMEOUT = 60.seconds
        /** Extra stream allowance per buffered row (≈40 rows/s, well under BLE's pace). */
        val STREAM_TIME_PER_ROW = 25.milliseconds
        const val UPLOAD_CHUNK_ROWS = 500
        // The firmware applies an ACK on its next 1 Hz connectivity tick, then
        // rewrites /log.csv; wait past both before re-reading unsynced_count.
        val ACK_SETTLE = 2_500.milliseconds
    }
}
