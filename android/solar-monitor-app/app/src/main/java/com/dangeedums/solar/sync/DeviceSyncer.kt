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
     * Always reads the device's data stream. The device's `unsynced_count`
     * is not trusted to skip it: a device reporting 0 while rows were still
     * buffered made "Sync now" call it up to date, though its own Sync button
     * found the rows. An idle device costs one short subscription.
     *
     * @param gatt a connected + authenticated peripheral wrapper.
     */
    suspend fun syncConnected(
        gatt: SolarGatt,
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

        val boots = gatt.readBootHistory()

        // Acking tells the device to delete every row up to the acked seq, so it
        // is only safe after a dump that arrived whole. A dump that lost a piece
        // in transit is read once more; if that one is short too, nothing is
        // uploaded or acked and the rows stay on the device.
        var dump = readDump(gatt, info.unsyncedCount)
        if (!dump.isComplete(info.expectedRowCount)) dump = readDump(gatt, info.unsyncedCount)
        if (!dump.isComplete(info.expectedRowCount)) {
            val expected = dump.declaredRows ?: info.expectedRowCount
            return Result.Failed(
                "Bluetooth transfer incomplete (got ${dump.rows.size} of $expected rows). " +
                "Nothing was deleted from the device; tap Sync again."
            )
        }

        val rows = dump.rows.sortedBy { it.seq }
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
                return syncConnected(gatt, onProgress = onProgress, afterSeqBase = true)
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

    /**
     * One dump of the device's buffer: the rows that parsed, how many lines
     * did not (a piece lost in transit merges two rows into one bad line), and
     * the row count from the device's `END,<n>` terminator (null for plain
     * `END`, firmware 1.1.0).
     */
    private class Dump(val rows: List<IngestReading>, val badLines: Int, val declaredRows: Int?) {
        /**
         * Every line parsed and every row the device sent arrived. Firmware
         * 1.1.0 sends no count, so its dump is checked against Device Info's
         * expected_row_count instead (a row logged since then may add one).
         */
        fun isComplete(expectedRowCount: Int): Boolean =
            badLines == 0 &&
                if (declaredRows != null) rows.size == declaredRows else rows.size >= expectedRowCount
    }

    /**
     * Subscribes to the data stream and collects it up to the terminator. The
     * device cuts its text at MTU size, so a row (or the terminator) can span
     * two notifications; the end is detected on the joined text. The timeout
     * scales with the backlog: a flat 60 s could never finish a long-offline
     * device's dump.
     */
    private suspend fun readDump(gatt: SolarGatt, unsyncedCount: Int): Dump {
        val acc = StringBuilder()
        withTimeout(STREAM_TIMEOUT + STREAM_TIME_PER_ROW * unsyncedCount) {
            gatt.observeDataStream().takeWhile { piece ->
                acc.append(piece)
                !END_AT_TAIL.containsMatchIn(acc.substring(maxOf(0, acc.length - 40)))
            }.collect { /* accumulating */ }
        }
        return parseDump(acc.toString())
    }

    private fun parseDump(text: String): Dump {
        val out = ArrayList<IngestReading>()
        var bad = 0
        var declared: Int? = null
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            END_LINE.matchEntire(trimmed)?.let { m ->
                declared = m.groupValues[1].toIntOrNull()
                return@forEach
            }
            val parts = trimmed.split(',')
            if (parts.size != 10) { bad++; return@forEach }
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
            }.onFailure { bad++ }
        }
        return Dump(out, bad, declared)
    }

    private fun nowIso(): String =
        OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    companion object {
        /** The device's terminator line: "END,<rows>" (firmware 1.1.1+) or "END". */
        private val END_LINE = Regex("""END(?:,(\d+))?""")
        private val END_AT_TAIL = Regex("""(?:^|\n)END(?:,\d+)?\n?$""")
        val STREAM_TIMEOUT = 60.seconds
        /** Extra stream allowance per buffered row (≈40 rows/s, well under BLE's pace). */
        val STREAM_TIME_PER_ROW = 25.milliseconds
        const val UPLOAD_CHUNK_ROWS = 500
        // The firmware applies an ACK on its next 1 Hz connectivity tick, then
        // rewrites /log.csv; wait past both before re-reading unsynced_count.
        val ACK_SETTLE = 2_500.milliseconds
    }
}
