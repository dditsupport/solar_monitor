// Fakes for DeviceSyncer tests: the class names and packages match the app's,
// so DeviceSyncer.kt compiles against them unchanged.
package com.dangeedums.solar.ble
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
/** Fake peripheral: streams `csv` in BLE-sized chunks, records acks, renumbers on seq_base. */
open class SolarGatt(var info: DeviceInfoBle, val boots: List<BootRecord>, var csv: String) {
    val acks = mutableListOf<Long>()
    val acked: Long? get() = acks.lastOrNull()
    var seqBases = mutableListOf<Long>()
    suspend fun readDeviceInfo(): DeviceInfoBle = info
    suspend fun readBootHistory(): List<BootRecord> = boots
    fun observeDataStream(): Flow<String> = flow { (csv + "END\n").chunked(240).forEach { emit(it) } }
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
