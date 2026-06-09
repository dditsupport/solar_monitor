package com.dangeedums.solar.data

import kotlinx.serialization.Serializable

/**
 * A Solar Monitor device the user has paired/added through BLE discovery.
 *
 * `id` is the firmware-derived identifier (`solar-<last6mac>`), but we discover
 * it via the BLE advertising name (`Solar-<LAST6MAC>`). For freshly-discovered
 * devices the canonical id might not yet be known (would require connecting and
 * reading Device Info), so `id` is nullable on the scan side and filled in
 * after the first connect. `address` is the MAC the OS reports — useful for
 * reconnecting without re-scanning on Android 12+ where we cannot resolve a
 * name back to a device without paired status.
 */
@Serializable
data class Device(
    val name: String,
    val address: String,
    val id: String? = null,
    val addedAtEpochMs: Long = System.currentTimeMillis(),
)
