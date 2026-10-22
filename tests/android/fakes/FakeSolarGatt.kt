// Fakes for DeviceSyncer tests: the class names and packages match the app's,
// so DeviceSyncer.kt compiles against them unchanged.
package com.dangeedums.solar.ble
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
/**
 * Fake peripheral: streams `csv` like firmware 1.1.1 (one text cut into pieces,
 * so rows and the END,<n> terminator span pieces), records acks, renumbers on
 * seq_base. `losePieceOnDumps` makes the first N dumps drop a piece mid-stream;
 * `legacy` streams like firmware 1.1.0 (whole rows per notification, plain
 * END) and `legacyLoseRow` drops one row notification, as NimBLE coalescing did.
 */
open class SolarGatt(var info: DeviceInfoBle, val boots: List<BootRecord>, var csv: String) {
    val acks = mutableListOf<Long>()
    val acked: Long? get() = acks.lastOrNull()
    var seqBases = mutableListOf<Long>()
    var dumps = 0
    var losePieceOnDumps = 0
    var legacy = false
    var legacyLoseRow = false
    suspend fun readDeviceInfo(): DeviceInfoBle = info
    suspend fun readBootHistory(): List<BootRecord> = boots
    fun observeDataStream(): Flow<String> = flow {
        dumps++
        val lines = csv.lineSequence().filter { it.isNotBlank() }.map { it + "\n" }.toMutableList()
        if (legacy) {
            if (legacyLoseRow && lines.size > 2) lines.removeAt(lines.size / 2)
            (lines + "END\n").forEach { emit(it) }
            return@flow
        }
        val pieces = (lines.joinToString("") + "END,${lines.size}\n").chunked(37).toMutableList()
        if (dumps <= losePieceOnDumps && pieces.size > 2) pieces.removeAt(pieces.size / 2)
        pieces.forEach { emit(it) }
    }
    suspend fun writeSyncAck(seq: Long) {
        acks += seq
        csv = csv.lineSequence().filter { it.isNotBlank() && it.substringBefore(',').toLong() > seq }.joinToString("") { it + "\n" }
    }
    open suspend fun setSeqBase(base: Long): CommandResult {
        seqBases += base
        csv = csv.lineSequence().filter { it.isNotBlank() }.joinToString("") { l ->
            val i = l.indexOf(','); "${l.substring(0, i).toLong() + base}${l.substring(i)}\n" }
        info = info.copy(seqFresh = false, lastSeq = info.lastSeq + base)
        return CommandResult("seq_base", true)
    }
}
