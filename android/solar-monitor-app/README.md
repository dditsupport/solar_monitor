# Solar Monitor — Android companion app

Kotlin + Jetpack Compose. Built with Gradle Kotlin DSL.

## What this commit delivers

The MVP slice of stage 8 — **discovery and persistence**:

- Launches into a **My devices** screen showing the user's saved devices
  (empty on first run).
- A search FAB opens **Nearby**, which scans BLE for advertisements
  carrying the firmware's custom service UUID
  `5f12b3bc-8ef3-4b48-a971-f70a38f519ec`.
- Discovered devices show up live with their advertising name
  (`Solar-XXXXXX`) and MAC address. Tap **Add** to save.
- Saved devices persist via Preferences DataStore (one JSON blob under
  the `saved_devices` store, keyed by MAC for de-dup). Tap the trash
  icon to remove.

Future commits in this directory will add: tap-to-connect, BLE Wi-Fi
provisioning UI, on-device data dump → MilesWeb forwarding, and charts
backed by `readings.php`.

## Uploading buffered rows — Sync now

**My devices** has a **Sync now** button that drains any log rows still
buffered on the saved devices over BLE, so the user doesn't have to open
each device individually. Nothing syncs on its own — the pass runs only
when the button is tapped.

On tap:

1. **Bluetooth is checked first.** If it's off, or the
   `BLUETOOTH_CONNECT` permission is missing, the pass is skipped and the
   screen says why instead of failing device by device.
2. Otherwise each saved device is handled **one at a time** — connect,
   authenticate, pull rows, POST to the backend, ACK back so the firmware
   truncates `/log.csv`, disconnect — before the next device starts. A
   phone's BLE stack does not cope with parallel GATT connections, and an
   unreachable device only costs its connect timeout before the pass moves
   on.
3. Progress and per-device results are shown on the list; the button is
   disabled while a pass runs so repeat taps can't stack.

Opening a device detail screen cancels a running pass — that screen needs
the radio, and the user's action wins. Nothing is ever ACKed unless the
server accepted the rows, so a failure anywhere leaves the buffer intact
on the device for the next tap, the device's own **Sync now**, or the
firmware's Wi-Fi path.

The relay itself lives in `sync/DeviceSyncer.kt` and is shared verbatim
with the per-device **Sync now** button, so both paths behave identically.
`sync/BulkSyncManager.kt` only orchestrates the walk across devices.

## Build

1. Install **Android Studio Ladybug** (2024.2.1) or newer.
2. Open the directory `android/solar-monitor-app/` (not the project
   root). Android Studio will sync Gradle and generate the wrapper.
3. Run on a physical device — emulators don't have BLE radios. Minimum
   Android version is 8.0 (API 26).
4. On Android 12+ the system will prompt for **Nearby devices**
   (Bluetooth) permission the first time you tap Scan. On Android 11
   and below it prompts for **Location** instead — the OS requires it
   for BLE scan even though we set `neverForLocation`.

## Project layout

```
solar-monitor-app/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml      ← version catalog
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/values/{strings,themes}.xml
        └── java/com/dangeedums/solar/
            ├── SolarApp.kt        ← Application, holds singletons
            ├── MainActivity.kt    ← Compose host + nav graph
            ├── ble/BleScanner.kt  ← BluetoothLeScanner wrapper as Flow
            ├── data/
            │   ├── Device.kt      ← @Serializable model
            │   └── DeviceStore.kt ← Preferences DataStore persistence
            ├── sync/
            │   ├── DeviceSyncer.kt    ← one device: pull → POST → ACK
            │   └── BulkSyncManager.kt ← Sync now: walk all saved devices
            └── ui/
                ├── MainViewModel.kt
                ├── ScanScreen.kt
                ├── SavedDevicesScreen.kt
                └── theme/Theme.kt
```

## Library choices

- **No third-party BLE library** for the scan path. `BluetoothLeScanner`
  with a `ScanFilter` on the firmware's service UUID is plenty for
  discovery. A higher-level library (Nordic, RxAndroidBle) will likely
  earn its keep once we add the GATT data-stream forwarder.
- **DataStore Preferences** instead of Room for the saved-device list.
  A single JSON blob is simpler than a database table for a list that
  rarely exceeds 5 entries.
- **Compose** for UI, **Navigation Compose** for the two-screen flow,
  **Material 3** for theming. No XML layouts.

## Permissions rationale

`AndroidManifest.xml` declares scan permissions split by API level. The
`neverForLocation` flag on `BLUETOOTH_SCAN` (Android 12+) lets us skip
the location grant on modern devices because we identify our target by
name prefix and service UUID, not by physical location. Older devices
(API 30 and below) still need `ACCESS_FINE_LOCATION` because the
platform enforces it for BLE scan.
