# Firmware comparison — the three repo builds vs. `esp32.supermini_ds1307_ac_energy_meter`

Compares the three firmware trees in `firmware/` against the uploaded
`esp32.supermini_ds1307_ac_energy_meter` build.

**Relay is out of scope.** The uploaded build ships `relay.cpp` / `relay.h` plus
relay hooks scattered through `.ino`, `wifi_sync.cpp`, `ble_service.cpp` and
`config.h`. None of that is described here beyond noting *where* it is entangled,
so the non-relay improvements can be ported without dragging the relay in.

All comparisons were done after normalising line endings — the uploaded tree is
CRLF, the repo is LF. Without that every file looks 100 % changed.

---

## 1. The three repo builds are one codebase with three thin skins

| | `solar_monitor_ds1307` | `solar_monitor_SSD1306_ds1307` | `solar_monitor_SSD1306_ds3231` |
|---|---|---|---|
| Board | ESP32 DevKit V1 | ESP32 DevKit V1 | ESP32 DevKit V1 |
| RTC | DS1307 (CH bit, 100 kHz) | DS1307 (CH bit, 100 kHz) | DS3231 (OSF bit, 400 kHz) |
| OLED | none | SSD1306, soft-SPI 23/22/21/19/18 | SSD1306, soft-SPI 23/22/21/19/18 |
| Sample cadence macro | `SAMPLE_INTERVAL_MS` | `DISPLAY_REFRESH_MS` | `DISPLAY_REFRESH_MS` |
| Extra files | — | `display.cpp/.h` | `display.cpp/.h` |
| `DEVICE_TOKEN` | **`"token"` (placeholder)** | real token | real token |

Everything else is byte-identical apart from comment wording and the
`solar_monitor_*.ino` filename referenced in `shared_state.h`. Concretely:

- **ds1307 vs SSD1306_ds1307** — only `config.h`, `shared_state.h`, `storage.h`
  (one comment), the `.ino`, and the presence of `display.*`. The `.ino` delta is
  `display::begin()` / `display::splash()` / `display::tick()` and the
  `SAMPLE_INTERVAL_MS` → `DISPLAY_REFRESH_MS` rename.
- **SSD1306_ds1307 vs SSD1306_ds3231** — `rtc.cpp/.h` (CH bit vs OSF bit),
  `I2C_FREQ_HZ` 100 kHz → 400 kHz, and DS1307→DS3231 wording in
  `ble_service.cpp` / `wifi_sync.cpp` comments. No logic difference.

### Finding worth fixing regardless of this comparison

`firmware/solar_monitor_ds1307/config.h:20` still has `DEVICE_TOKEN "token"`
while the two OLED builds carry the real token. `wifi_sync.cpp:270` sends it as
`X-Device-Token`, and `backend/public_html/api/_db.php:85` does a
`hash_equals()` against it — so a device flashed with the headless DS1307 build
is rejected by ingest unless the server's `secrets.php` is still on the example
placeholder.

All three also still carry `LOG_INTERVAL_SEC_DEFAULT 60` with the
`// TODO: restore 900 for production` note.

---

## 2. Uploaded build vs. the repo core

Because the three repo builds share one core, the diff against the upload is the
same for all three (the OLED builds add `display.*` on top, which the upload has
no counterpart for). The upload is closest to `solar_monitor_ds1307`.

Files present only in the repo: `coin_cell.cpp`, `coin_cell.h` (+ `display.*` in
the OLED builds). Files only in the upload: `relay.cpp`, `relay.h` (excluded).

Change volume vs. `solar_monitor_ds1307` (normalised, changed lines):

| File | Δ | File | Δ |
|---|---|---|---|
| `ble_service.cpp` | 594 | `storage.cpp` | 67 |
| `wifi_sync.cpp` | 427 | `pzem.cpp` | 56 |
| `config.h` | 254 | `storage.h` | 39 |
| `.ino` | ~180 | `rtc.cpp` | 24 |
| `ble_service.h` | 21 | `rtc.h` / `log_serial.*` / `identity.*` / `shared_state.h` / `pzem.h` | ≤ 12 each |
| `health.cpp`, `health.h`, `wifi_sync.h`, `led.*`, `time_source.*` | **identical** | | |

### 2.1 Identity and branding

| | Repo | Upload |
|---|---|---|
| `FW_VERSION` | `1.0.0` | `2.0.0` (declared a breaking release) |
| Device ID | `solar-a3f29c` / `Solar-A3F29C` | `meter-a3f29c` / `Meter-A3F29C` |
| `INGEST_HOST_DEFAULT` | `https://solar.aromen.biz` | `https://ac.aromen.biz` |
| Boot banner | `=== Solar Monitor boot ===` | `=== AC Energy Meter boot ===` |

The device-ID prefix change is **breaking for the backend and the app**: existing
rows are keyed on `solar-…`, so a reflashed unit re-registers as a new device.

### 2.2 Target hardware: dual-core DevKit V1 → single-core ESP32-C3 Super Mini

| | Repo (DevKit V1) | Upload (C3 Super Mini) |
|---|---|---|
| PZEM UART | `Serial2`, RX 16 / TX 17 | `Serial1`, RX GPIO20 / TX GPIO21 |
| I2C (RTC) | SDA 4 / SCL 13 | SDA 6 / SCL 5 |
| Status LED | GPIO2, active-**high** | GPIO7, active-**low** |
| Coin-cell ADC | GPIO35 (input-only, ADC1) | GPIO3 (ADC1_CH3) + `COINCELL_DIVIDER_RATIO` |
| Buttons | none | BOOT GPIO9, `FACTORY_RESET_HOLD_MS 5000` |
| Task pinning | sampling→core 0, conn→core 1 | both →core 0 (single core) |
| Console | UART bridge | native USB-CDC (`delay(1000)` to let it enumerate) |

New in the upload: `ROM_LOG_QUIET` in `config.h` gates the
`ets_install_putc1(noop_putc)` call in `log_serial.cpp`. The repo silences ROM
output unconditionally, which also hides the panic reason line
(`CORRUPT HEAP`, `Guru Meditation`, `assert failed`). Set it to 0 to debug.

### 2.3 Radio coexistence — the largest functional block

None of this exists in the repo. All of it is driven by the C3's single shared
radio, but the TX-power and auto-reconnect parts are useful on any board.

- **`WIFI_TX_POWER_QDBM 44`** (11 dBm), reapplied via `WiFi.setTxPower()` after
  *every* `WiFi.begin()` — the driver resets TX power on mode change/reconnect,
  so a one-shot call in `setup()` silently drifts back to max. Runtime-settable
  over BLE (`{"action":"set_tx_power","dbm":13}`), persisted in NVS key `txpwr`,
  bounds 8..84 qdBm. Rationale in-code: the Super Mini's LDO can't hold the
  radio's ~335 mA TX peak, the rail sags, auth frames corrupt, association fails
  with disconnect reason 2 (`AUTH_EXPIRE`).
- **`WIFI_PAUSE_BLE_DURING_SYNC 1`** — `ble_service::pause_advertising()` /
  `resume_advertising()` bracket the whole Wi-Fi cycle in `run_cycle()`.
- **`BLE_CONFIG_WINDOW_SEC 120`** — BLE runs only for the first two minutes after
  boot (Wi-Fi held off), then `ble_service::shutdown()` calls
  `NimBLEDevice::deinit(true)` and Wi-Fi owns the radio for the rest of the run.
  The handoff is deferred while a phone is still connected (`is_connected()`),
  and the stuck-BLE watchdog is suppressed afterwards so the intentional
  shutdown doesn't trigger a reboot. Reboot to get another config window.
- **`WiFi.setAutoReconnect(false)`** (repo: `true`). The IDF's background
  reconnector races the firmware's own `disconnect()`/`begin()`, making
  `esp_wifi_set_config()` fail with *"sta is connecting, cannot set config"* — so
  freshly written credentials never apply.
- **`try_connect_known()` rewritten.** The repo scans and only calls
  `WiFi.begin()` for an SSID that appeared in the scan — a scan miss or a hidden
  SSID means it never even tries. The upload makes the scan purely informational
  (logs `seen at -NN dBm (ch N)` or `not seen`), then calls `WiFi.begin()`
  unconditionally, once per cycle per stored network. It also logs terminal
  status plus the STA disconnect reason captured from `WiFi.onEvent()`
  (15 = wrong password, 201 = AP not found, 2 = auth expire).

⚠️ `try_connect_known()` in the upload logs the **Wi-Fi password in clear text**
on the serial console (`connecting to "%s" with password "%s"`). That is a
deliberate diagnostic, not something to port to production as-is.

### 2.4 Stability: stack, heap, watchdog

| | Repo | Upload |
|---|---|---|
| `CONN_TASK_STACK` | 12288 | 32768 |
| 16 KB POST `StaticJsonDocument` | on the conn-task stack | file-scope `static` in `.bss` |
| Ingest response doc | `StaticJsonDocument<256>` local | `static StaticJsonDocument<1024>` |
| TLS handshake cap | none (mbedTLS default 120 s) | `TLS_HANDSHAKE_TIMEOUT_S 12` via `setHandshakeTimeout()` |
| TCP connect cap | none | `http.setConnectTimeout(HTTP_TIMEOUT_MS)` |
| Heap guard before POST | none | defer POST if free heap < 45000 or largest block < 40000 |
| WDT feeding | none inside the blocking waits | `esp_task_wdt_reset()` in the connect wait, the NTP wait, before the POST, and in the multi-batch drain loop |
| Heap visibility | none | one `heap free=… largest=…` line per POST |
| LittleFS mount failure | fatal `while(true)` | force `format()` + `begin(false)` and continue |

**Correction — both of these rest on ArduinoJson 6 semantics that do not apply
here.** `firmware/libraries.zip` bundles **ArduinoJson 7.4.3**, where
`StaticJsonDocument<N>` is a deprecated shim over `JsonDocument` and `N` is used
only by `capacity()`. It reserves nothing, on the stack or anywhere else:

- The document was never 16 KB on the connectivity-task stack (it could not have
  been — `CONN_TASK_STACK` is 12288 bytes). Making it a file-scope `static` does
  not move it to `.bss` either; a static `JsonDocument` still heap-allocates its
  pools on demand. The upload's `CONN_TASK_STACK` 12 K → 32 K bump and the
  `.bss` move were aimed at a v6 problem that does not exist against this
  library.
- The 256 → 1024 response-document change is a **no-op**. A v7 `JsonDocument`
  grows to fit; it does not overflow at a fixed capacity, so there is no silent
  `log_interval_sec` drop to fix. (This corrects an earlier claim in this
  document.)

What v7 *does* do is allocate the whole document from the heap in 1 KB pools
(128 slots on a 32-bit target), on demand, per POST — which is a churn problem
rather than a capacity one. See the heap notes below.

### 2.5 PZEM robustness

- `PZEM_READ_ATTEMPTS 3` / `PZEM_READ_RETRY_MS 250` — `pzem::read()` retries a
  missed Modbus transaction instead of failing the sample; the delay exceeds the
  library's ~200 ms value cache so each retry is a genuinely fresh read.
  `reset_energy()` retries the same way.
- Boot-time PZEM probe in `setup()` (5 tries) printing V/I/P/Wh/PF/Hz, or an
  explicit wiring/baud error line.
- PZEM status-transition logging in the sampling task
  (`OK` / `STALE` / `SENSOR_FAULT`), so a fault developing after boot is visible.

### 2.6 Coin cell — module removed, telemetry changed

The repo has a `coin_cell` module sampled on every sampling tick, cached in
`SharedState.coin_cell_v`, and attached to the hourly drift sample as
`coin_cell_v` (float volts).

The upload deletes `coin_cell.cpp/.h` and `SharedState.coin_cell_v` entirely and
replaces them with a static `read_coincell_mv()` inside `wifi_sync.cpp`, read
only at POST time, reported as `coincell_mv` (integer millivolts, `ADC_11db`,
16 samples, `analogReadMilliVolts()` so eFuse calibration applies).

Net effect: simpler, but the live coin-cell value is no longer in shared state,
so it is no longer available to BLE or a display.

### 2.7 Ingest payload

| Field | Repo | Upload |
|---|---|---|
| `rtc_drift_sec` | ✅ (hourly, gated by `RTC_DRIFT_LOG_INTERVAL_SEC`) | ✅ (captured at each NTP sync) |
| `rtc_drift_at` | ISO 8601 local string | ✗ |
| `rtc_drift_epoch` | ✗ | ✅ uint32 — server dedups on `(device_id, epoch)` |
| `rssi_dbm` | only inside the hourly drift sample | ✗ |
| `wifi_rssi` | ✗ | ✅ on **every** POST |
| `coin_cell_v` | ✅ float volts, hourly | ✗ |
| `coincell_mv` | ✗ | ✅ uint mV, every POST |
| `channel_count` | ✗ | ✅ `1` |
| `relay_*` | ✗ | relay — excluded |

Semantics differ: the repo marks the drift sample pending and clears it on a 200
response; the upload re-sends the last sample on every POST and relies on the
server to dedup by epoch. Also `NTP_RESYNC_INTERVAL_SEC` moved 300 → 3600, so
the upload's "hourly" drift cadence comes from the NTP interval rather than a
separate `RTC_DRIFT_LOG_INTERVAL_SEC` (which the upload drops).

**Backend impact:** `ingest.php` must accept `rtc_drift_epoch`, `wifi_rssi` and
`coincell_mv` alongside (or instead of) `rtc_drift_at`, `rssi_dbm`,
`coin_cell_v`, or that telemetry is silently dropped.

### 2.8 Timing defaults

| Macro | Repo | Upload |
|---|---|---|
| `LOG_INTERVAL_SEC_DEFAULT` | 60 (`TODO: restore 900`) | 300 |
| `NTP_RESYNC_INTERVAL_SEC` | 300 | 3600 |
| `NTP_SERVER_2` | `in.pool.ntp.org` | `time.cloudflare.com` |
| `SAMPLE_INTERVAL_MS` | 1000 | 1000 |
| `WIFI_SCAN_INTERVAL_SEC` | 120 | 120 |
| `STUCK_WIFI_REBOOT_SEC` | 21600 (6 h) | **21600 — unchanged** |
| `STUCK_BLE_REBOOT_SEC` | 43200 (12 h) | **43200 — unchanged** |

Both stuck-radio watchdog thresholds are identical in all four builds. The only
behavioural change around them is that the upload skips the stuck-BLE check once
BLE has been intentionally shut down at the handoff, and it hoists the
`uptime_sec` computation to the top of the connectivity loop.

The upload also drops the repo's `first_cycle` 30-second settle delay — the first
Wi-Fi cycle now runs immediately (`periodic_due = first_cycle ? true : …`).

### 2.9 Storage / factory reset

| | Repo `erase_all_nvs()` | Upload `factory_reset()` |
|---|---|---|
| Scope | `s_cfg.clear()` + `s_state.clear()` (two namespaces) | `nvs_flash_deinit()` + `nvs_flash_erase()` — **whole NVS partition** |
| Buffered log | untouched | `LittleFS.format()` |
| Concurrency | none | takes the log lock and never releases it (reboot follows) |
| Future namespaces | missed | covered |

New NVS key in the upload: `txpwr` (`wifi_tx_power_qdbm()` /
`set_wifi_tx_power_qdbm()`), with a no-op write guard.

### 2.10 BLE service

The upload's BLE rework is the single biggest diff and it is **app-breaking**.

**Auth model**

| | Repo | Upload |
|---|---|---|
| Key macro | `BLE_PRESHARED_KEY` — still `"change-me-…"` | `BLE_PSK` — real 64-hex secret |
| Challenge characteristic | JSON `{"nonce":…,"authenticated":…}`, notifiable | bare 32-hex nonce string |
| Auth state push | `publish_auth_state()` notifies on success/failure | none |
| Auth scope | single global `s_authenticated` bool | bound to the connection handle (`s_auth_handle`), checked per call via `is_authed(info)` |
| Unauth reads | values overwritten with `{"error":"unauthorized"}` | `onRead` callbacks return an **empty** value and rebuild the real value on an authed read |
| Challenge/Response UUIDs | `85a1b1bb…` / `257b8e6b…` | `4eadfb98…` / `0ce9edf3…` |

The per-connection binding is stricter and the rebuild-on-read pattern removes a
stale-value window the repo's swap-the-value approach has. But the UUID change
plus the dropped `authenticated` flag means the current Android app cannot
authenticate against the uploaded firmware without matching changes.

**Characteristics**

Removed in the upload: `BLE_UUID_DEVICE_COMMAND` / `BLE_UUID_COMMAND_RESULT` and
the whole `DeviceCommandCallbacks` + `send_cmd_result()` machinery
(`{"cmd":"reset_pzem"}`, `{"cmd":"erase_nvs","confirm":true}` with notified
`{"cmd":…,"ok":…,"error":…}` results).

Added in the upload: two dedicated write-only characteristics —
`BLE_UUID_PZEM_RESET` and `BLE_UUID_FACTORY_RESET` — each requiring auth plus an
explicit confirmation payload. Plus a relay characteristic (excluded).

Trade-off: simpler wire format, but the app loses the structured
success/error result channel; a rejected command is now only visible on the
serial console.

**Other BLE changes**

- `build_device_info_json()` grows to 512 B and adds `total_kwh` (live meter
  total, so the app can verify a PZEM reset took) and `channel_count`.
- Wi-Fi Status JSON: **both builds already report the real association**
  (`WiFi.isConnected()`, with SSID and IP), falling back to the transient
  state-machine enum only when not associated. The upload's only changes here
  are that `saved_ssid` is emitted unconditionally rather than when non-empty,
  and a new `tx_power_dbm` field. Note the upload also gates the status/scan
  NOTIFY on `authed_present()`, where the repo notifies unconditionally.
- Wi-Fi Config gains the `set_tx_power` action alongside `scan`.
- New public API: `is_connected()`, `pause_advertising()`, `resume_advertising()`,
  `shutdown()`. `is_alive()` returns `true` after `shutdown()` so the watchdog
  doesn't fire on the intentional handoff.

### 2.11 RTC

Behaviourally the same; the upload adds a boot log line printing the raw DS1307
time and running/stopped state *before* deciding whether to trust it, which is a
useful diagnostic for a dead coin cell. Everything else is comment rewording.

### 2.12 Identical files

`health.cpp`, `health.h`, `wifi_sync.h`, `led.cpp`, `led.h`, `time_source.cpp`,
`time_source.h` are byte-for-byte identical after line-ending normalisation.

---

## 3. What porting the upload back would cost

The uploaded tree is a **hard fork, not a superset**. Adopting it wholesale drops:

1. `coin_cell` module and `SharedState.coin_cell_v` (live coin-cell voltage in
   shared state, hence to BLE and the OLED).
2. `display.cpp/.h` and everything the OLED builds carry — including the recent
   "today/total headline" and fault-banner fixes.
3. The Device Command / Command Result BLE pair and its structured result
   notifications.
4. `RTC_DRIFT_LOG_INTERVAL_SEC` as an independent cadence, `rtc_drift_at`,
   `rssi_dbm`, `coin_cell_v`, and the drift pending/ack semantics.
5. The `authenticated` flag notified on the auth-challenge characteristic.

And it changes device IDs (`solar-` → `meter-`), the backend host, and four BLE
UUIDs, all of which need matching backend and Android changes.

### Relay entanglement (for a clean port)

The relay is not confined to `relay.cpp/.h`. Stripping it means removing:

- `.ino`: `#include "relay.h"`, `relay::begin()`, `relay::tick()` in `loop()`,
  `relay::update_power()` in the sampling task.
- `wifi_sync.cpp`: `#include "relay.h"`, the `relay_on` / `relay_mode` /
  `relay_version` / `relay_count` POST fields, and the `relay::apply()` block
  that consumes `relay_version` / `relay_schedule` /
  `relay_compressor_watts` / `relay_grace_min` from the ingest response.
- `ble_service.cpp`: `#include "relay.h"`, `s_char_relay`, `RelayCallbacks`,
  the relay block in `tick()`, and `s_last_relay_json`.
- `config.h`: `PIN_RELAY`, `RELAY_ACTIVE_HIGH`, `RELAY_COMPRESSOR_WATTS_*`,
  `RELAY_GRACE_MIN_*`, `BLE_UUID_RELAY`.

Note `channel_count` and the 1024-byte response document are sized partly for the
relay payload; the response-size fix is worth keeping either way.

### Suggested port order (highest value first, all relay-free)

1. ~~Response `StaticJsonDocument` 256 → 1024~~ — no-op against ArduinoJson 7,
   see the correction in 2.4. Reduce `SYNC_BATCH_SIZE` and serialise the body
   into a fixed `.bss` buffer instead; that is the change that actually cuts
   per-POST heap churn.
2. ~~Move the 16 KB POST document off the connectivity-task stack~~ — it was
   never there; see 2.4.
3. `setHandshakeTimeout()` + `setConnectTimeout()` + the `esp_task_wdt_reset()`
   calls in the blocking waits (kills the `task_wdt: conn` reboot).
4. `WiFi.setAutoReconnect(false)` and the scan-gating removal in
   `try_connect_known()` — but **not** the clear-text password log line.
5. PZEM read/reset retries and the boot probe.
6. LittleFS format-and-continue instead of the fatal loop.
7. Whole-partition `factory_reset()`.
8. Per-connection auth binding and rebuild-on-read gating (keep the existing
   UUIDs and the `authenticated` flag so the app keeps working), plus gating
   the BLE notifies on an authenticated connection.

Board-specific (C3 only): TX-power capping, BLE/Wi-Fi handoff, pin map, task
pinning, USB-CDC delay.


---

## 4. Addendum — periodic radio rest (added after this comparison)

All three repo builds now take the radio fully off-air on a schedule
(`RADIO_REST_INTERVAL_SEC` / `RADIO_REST_DURATION_SEC` in `config.h`, default
3 h / 45 s): the STA is disassociated, the Wi-Fi PHY powered down, and BLE
advertising stopped, after which both come back and a sync runs immediately.

This exists because `try_connect_known()` reuses any association reporting
`WL_CONNECTED` and `run_cycle()` only tears the link down when that function
fails — so a stale association after an AP restart (routine with a phone
hotspot) could never be recovered except by the 6 h `STUCK_WIFI_REBOOT_SEC`
reboot. The rest caps that window at one interval and costs a reassociation
instead of a reboot, so uptime, `boot_id` and the buffered log all survive.

It is a scheduled backstop, not a fast path: a failure-counted reassociation
(force a reconnect after N consecutive failed POST cycles) would cut the worst
case from hours to minutes and is still worth adding on top.


---

## 5. Heap notes (ArduinoJson 7.4.3, as bundled)

Verified against `firmware/libraries.zip`:

- `ARDUINOJSON_SLOT_ID_SIZE` is 2 on a 32-bit target, so
  `ARDUINOJSON_POOL_CAPACITY` is 128 slots = **1024 bytes per pool**, allocated
  from the heap on demand, with the pool list itself grown by `realloc`.
- `Writer<::String>` buffers `ARDUINOJSON_STRING_BUFFER_SIZE` = **32** bytes and
  calls `String::concat()` per chunk. Its constructor assigns
  `str = (const char*)0`, which **invalidates and frees any buffer already
  reserved** — so calling `body.reserve(...)` before `serializeJson(doc, body)`
  does nothing. Use the
  `serializeJson(source, void* buffer, size_t)` overload with a static buffer
  instead.

Per 100-row POST that works out to roughly 9 KB of pool in ~9 separate 1 KB
allocations, plus an ~11 KB `String` grown through several hundred `concat()`
reallocations, plus mbedTLS's record buffer — which is the one allocation that
must be large and contiguous, and therefore the first to fail as the heap
fragments.
