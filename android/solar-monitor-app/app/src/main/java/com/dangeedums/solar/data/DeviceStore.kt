package com.dangeedums.solar.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists the user's added device list in a single JSON blob inside
 * Preferences DataStore. The list is small (one or a few devices), so a
 * dedicated database would be overkill. Reads and writes are atomic.
 */
class DeviceStore(private val store: DataStore<Preferences>) {

    private val key = stringPreferencesKey("devices_json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val devices: Flow<List<Device>> = store.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString<List<Device>>(it) }.getOrNull() }
            ?: emptyList()
    }

    suspend fun add(device: Device) {
        store.edit { prefs ->
            val current = prefs[key]
                ?.let { runCatching { json.decodeFromString<List<Device>>(it) }.getOrNull() }
                ?: emptyList()
            // De-dup by MAC address. Re-adding a device the user already
            // renamed (e.g. adding it again from a later scan) keeps their
            // name rather than reverting to the scan-derived one.
            val existing = current.firstOrNull { it.address == device.address }
            val merged = if (existing?.nameIsCustom == true && !device.nameIsCustom) {
                device.copy(name = existing.name, nameIsCustom = true)
            } else {
                device
            }
            val replaced = current.filter { it.address != device.address } + merged
            prefs[key] = json.encodeToString(replaced)
        }
    }

    /**
     * Set a user-chosen name for a saved device. Purely local — works with no
     * cloud login and no BLE connection. A blank name clears the custom flag,
     * so the device falls back to whatever the cloud (or the scan) calls it.
     */
    suspend fun rename(address: String, name: String) {
        val trimmed = name.trim()
        store.edit { prefs ->
            val current = prefs[key]
                ?.let { runCatching { json.decodeFromString<List<Device>>(it) }.getOrNull() }
                ?: emptyList()
            val updated = current.map { d ->
                when {
                    d.address != address -> d
                    trimmed.isEmpty()    -> d.copy(nameIsCustom = false)
                    else                 -> d.copy(name = trimmed, nameIsCustom = true)
                }
            }
            prefs[key] = json.encodeToString(updated)
        }
    }

    suspend fun remove(address: String) {
        store.edit { prefs ->
            val current = prefs[key]
                ?.let { runCatching { json.decodeFromString<List<Device>>(it) }.getOrNull() }
                ?: emptyList()
            prefs[key] = json.encodeToString(current.filter { it.address != address })
        }
    }
}
