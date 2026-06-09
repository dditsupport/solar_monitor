package com.dangeedums.solar

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.dangeedums.solar.ble.BleScanner
import com.dangeedums.solar.data.DeviceStore

val android.content.Context.savedDevicesDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "saved_devices")

class SolarApp : Application() {
    lateinit var deviceStore: DeviceStore
        private set
    lateinit var bleScanner: BleScanner
        private set

    override fun onCreate() {
        super.onCreate()
        deviceStore = DeviceStore(savedDevicesDataStore)
        bleScanner = BleScanner(this)
    }
}
