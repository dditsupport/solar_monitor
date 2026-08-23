package com.dangeedums.solar

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.dangeedums.solar.ble.BleScanner
import com.dangeedums.solar.cloud.CloudClient
import com.dangeedums.solar.cloud.PersistentCookieStorage
import com.dangeedums.solar.data.CloudSessionStore
import com.dangeedums.solar.data.DeviceStore
import com.dangeedums.solar.sync.AutoSyncManager
import com.dangeedums.solar.sync.DeviceSyncer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

val android.content.Context.savedDevicesDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "saved_devices")

val android.content.Context.cloudSessionDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "cloud_session")

class SolarApp : Application() {
    lateinit var deviceStore: DeviceStore
        private set
    lateinit var bleScanner: BleScanner
        private set
    lateinit var cloudClient: CloudClient
        private set
    lateinit var cloudSessionStore: CloudSessionStore
        private set
    lateinit var deviceSyncer: DeviceSyncer
        private set
    lateinit var autoSync: AutoSyncManager
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        deviceStore       = DeviceStore(savedDevicesDataStore)
        bleScanner        = BleScanner(this)
        cloudClient       = CloudClient(PersistentCookieStorage(cloudSessionDataStore))
        cloudSessionStore = CloudSessionStore(cloudSessionDataStore)
        deviceSyncer      = DeviceSyncer(cloudClient, cloudSessionStore)
        // Long-lived so an in-flight BLE relay pass survives the Activity being
        // recreated (rotation) — it is tied to the process, not the UI.
        autoSync          = AutoSyncManager(appScope, deviceStore, bleScanner, deviceSyncer)

        // Pull persisted base URL into the client as early as possible so
        // subsequent calls hit the right host.
        appScope.launch {
            val s = cloudSessionStore.settings.first()
            cloudClient.setBaseUrl(s.baseUrl)
        }
    }
}
