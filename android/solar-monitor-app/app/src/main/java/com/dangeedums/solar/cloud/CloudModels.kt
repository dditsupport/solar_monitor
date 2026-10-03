package com.dangeedums.solar.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Property names are Kotlin-style; @SerialName holds the JSON key the PHP API
// uses, so the wire format is unchanged.

/* ---------- login.php ---------- */

@Serializable
data class LoginResponse(
    val ok: Boolean,
    val username: String? = null,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    val csrf: String? = null,
    val error: String? = null,
)

/* ---------- csrf.php ---------- */

@Serializable
data class CsrfResponse(val ok: Boolean, val csrf: String? = null, val error: String? = null)

/* ---------- claim_device.php ---------- */

@Serializable
data class ClaimDeviceResponse(
    val ok: Boolean,
    @SerialName("device_id")     val deviceId: String? = null,
    @SerialName("friendly_name") val friendlyName: String? = null,
    val created: Boolean = false,
    @SerialName("owner_user_id") val ownerUserId: Int? = null,
    val error: String? = null,
)

/* ---------- reset_device_data.php ---------- */

@Serializable
data class ResetDeviceDataResponse(
    val ok: Boolean,
    @SerialName("device_id")    val deviceId: String? = null,
    @SerialName("rows_deleted") val rowsDeleted: Int = 0,
    val error: String? = null,
)

/* ---------- device_names.php ---------- */

@Serializable
data class DeviceNamesResponse(
    val ok: Boolean,
    val names: Map<String, String> = emptyMap(),
    val error: String? = null,
)

/* ---------- devices.php ---------- */

@Serializable
data class DevicesResponse(
    val ok: Boolean,
    val devices: List<CloudDevice> = emptyList(),
    val error: String? = null,
)

@Serializable
data class CloudDevice(
    @SerialName("device_id")        val deviceId: String,
    @SerialName("friendly_name")    val friendlyName: String,
    val location: String? = null,
    @SerialName("capacity_kw")      val capacityKw: Double? = null,
    @SerialName("owner_user_id")    val ownerUserId: Int? = null,
    @SerialName("owner_username")   val ownerUsername: String? = null,
    @SerialName("fw_version")       val fwVersion: String? = null,
    @SerialName("last_sync_at")     val lastSyncAt: String? = null,
    @SerialName("last_seq")         val lastSeq: Long? = null,
    @SerialName("last_boot_id")     val lastBootId: Int? = null,
    @SerialName("total_readings")   val totalReadings: Long? = null,
    @SerialName("log_interval_sec") val logIntervalSec: Int? = null,
)

/* ---------- readings.php ---------- */

@Serializable
data class ReadingsResponse(
    val ok: Boolean,
    @SerialName("device_id")      val deviceId: String? = null,
    @SerialName("friendly_name")  val friendlyName: String? = null,
    // capacity_kw is repurposed as the replaced meter's last reading (kWh) at
    // install; adjustment_kwh is a signed manual correction. Both are added to
    // the whole-window meter delta (total_kwh) to form the Period total.
    @SerialName("capacity_kw")    val capacityKw: Double? = null,
    @SerialName("adjustment_kwh") val adjustmentKwh: Double? = null,
    @SerialName("total_kwh")      val totalKwh: Double? = null,
    val from: String? = null,
    val to: String? = null,
    val aggregate: String? = null,
    val points: List<ReadingPoint> = emptyList(),
    val error: String? = null,
)

/**
 * One union model that handles both raw and bucketed points. Fields are
 * mutually exclusive: raw rows carry V/I/P/Wh/PF/Hz; bucketed rows carry
 * kwh/P_avg/P_peak/V_avg/samples.
 */
@Serializable
data class ReadingPoint(
    val t: String,
    @SerialName("t_end")  val tEnd: String? = null,
    // raw
    @SerialName("V")      val voltage: Double? = null,
    @SerialName("I")      val current: Double? = null,
    @SerialName("P")      val power: Double? = null,
    @SerialName("Wh")     val energyWh: Double? = null,
    @SerialName("PF")     val powerFactor: Double? = null,
    @SerialName("Hz")     val frequency: Double? = null,
    val conf: String? = null,
    // bucketed
    val kwh: Double? = null,
    @SerialName("P_avg")  val powerAvg: Double? = null,
    @SerialName("P_peak") val powerPeak: Double? = null,
    @SerialName("V_avg")  val voltageAvg: Double? = null,
    val samples: Int? = null,
    val approx: Boolean? = null,
)

/* ---------- ingest.php (used by BLE relay) ---------- */

@Serializable
data class IngestPayload(
    @SerialName("device_id")               val deviceId: String,
    @SerialName("fw_version")              val fwVersion: String,
    @SerialName("sync_wall_time")          val syncWallTime: String,
    @SerialName("current_boot_id")         val currentBootId: Int,
    @SerialName("current_boot_uptime_sec") val currentBootUptimeSec: Long,
    @SerialName("boot_history")            val bootHistory: List<IngestBoot>,
    val readings: List<IngestReading>,
    // The device's counter is fresh: the server answers with seq_base
    // instead of storing anything (see IngestResponse.seqBase).
    @SerialName("seq_fresh")               val seqFresh: Boolean = false,
    // Heap as reported over BLE at sync time. Sent with the first chunk of a
    // sync only (null on the rest), so one sync records one sample.
    @SerialName("heap_free")               val heapFree: Long? = null,
    @SerialName("heap_largest")            val heapLargest: Long? = null,
    @SerialName("heap_min")                val heapMin: Long? = null,
    // Tags the sample's origin so the server can keep the BLE series separate
    // from the Wi-Fi one -- they are taken in different memory contexts.
    @SerialName("heap_source")             val heapSource: String? = null,
)

@Serializable
data class IngestBoot(
    @SerialName("boot_id")      val bootId: Int,
    @SerialName("duration_sec") val durationSec: Int,
)

@Serializable
data class IngestReading(
    val seq: Long,
    @SerialName("boot_id") val bootId: Int,
    val sec: Long,
    @SerialName("V")       val voltage: Double,
    @SerialName("I")       val current: Double,
    @SerialName("P")       val power: Double,
    @SerialName("Wh")      val energyWh: Double,
    @SerialName("PF")      val powerFactor: Double,
    @SerialName("Hz")      val frequency: Double,
    // Epoch (UTC seconds) the device logged the row at, from its RTC / NTP
    // clock; null when the clock was unknown. Lets the server place rows from
    // an earlier boot exactly instead of reconstructing them.
    val t: Long? = null,
)

@Serializable
data class IngestResponse(
    val ok: Boolean,
    @SerialName("acked_up_to_seq")  val ackedUpToSeq: Long = 0,
    @SerialName("server_time")      val serverTime: String? = null,
    @SerialName("log_interval_sec") val logIntervalSec: Int? = null,
    val error: String? = null,
    // With error "seq_base_required": the highest seq the server holds for the
    // device. Nothing was stored; the device must renumber above it.
    @SerialName("seq_base")         val seqBase: Long? = null,
)
