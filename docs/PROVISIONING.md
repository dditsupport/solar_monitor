# Provisioning Guide

This document covers first-time flashing, Wi-Fi setup via BLE, and the
bench-test workflow for Stages 6–7.

## 1. Toolchain & libraries

- Arduino IDE 2.x
- Board: **ESP32 Dev Module**
- Partition Scheme: **Default 4MB with spiffs (1.2MB APP/1.5MB SPIFFS)**

Install via Library Manager:

| Library | Purpose |
|---|---|
| `U8g2` (olikraus) | SSD1306 OLED |
| `PZEM-004T-v30` (mandulaj) | PZEM read wrapper |
| `ArduinoJson` (bblanchon) | JSON parsing/serialization |
| `NimBLE-Arduino` (h2zero) | BLE GATT server |

LittleFS, WiFi, HTTPClient, WiFiClientSecure, and Preferences are built in.

## 2. Flash

1. Open `firmware/solar_monitor/solar_monitor.ino` in Arduino IDE.
2. Set board to **ESP32 Dev Module** and partition scheme as above.
3. Verify the `INGEST_URL` and `DEVICE_TOKEN` macros in `config.h`. The
   token committed in this repo is a placeholder generated at project
   bring-up — replace it with the production value before deploying.
4. Upload.

## 3. First boot

OLED shows "Solar Monitor / booting...". After a few seconds you should
see live PZEM readings (zero if no load) and the device ID printed on
the serial monitor at 115200 baud.

Serial commands available at any time:

| Command | Effect |
|---|---|
| `INFO` | Print device id, firmware, last seq, unsynced row count |
| `DUMP` | Print full contents of `/log.csv` |
| `BOOTS` | Print current boot id and the boot history NVS buffer |
| `CLEAR` | Wipe `/log.csv` (does **not** clear NVS state) |
| `WIFI` | List saved Wi-Fi SSIDs |

## 4. Wi-Fi provisioning via BLE (nRF Connect)

The firmware stores **exactly one** Wi-Fi credential; writing a new one
replaces the previous one. The companion app (or nRF Connect) drives
provisioning via three characteristics:

| Operation | Characteristic | UUID | Payload |
|---|---|---|---|
| Trigger a Wi-Fi scan | Wi-Fi Config (write) | `41310027-c18e-4452-a50e-861e77cf2743` | `{"action":"scan"}` |
| Read scan results | Wi-Fi Scan (read or notify) | `d4346c1c-6e36-4a0f-a164-84cd396a4697` | JSON array `[{"s":"SSID","r":-65,"e":1}, ...]` |
| Save credential | Wi-Fi Config (write) | `41310027-c18e-4452-a50e-861e77cf2743` | `{"ssid":"...","password":"..."}` |
| Watch progress | Wi-Fi Status (read or notify) | `28c3fa43-a1b5-4e0e-a51c-a1e979609d28` | JSON `{"status":"...","ssid":"..."}` |

Recommended flow with nRF Connect:

1. Connect, discover services → custom 128-bit service.
2. **Subscribe** to notifications on Wi-Fi Status and Wi-Fi Scan.
3. Write `{"action":"scan"}` to Wi-Fi Config. Wi-Fi Status will push
   `{"status":"scanning"}` and Wi-Fi Scan will push the result JSON
   when the scan finishes (~3–5 s).
4. Pick an SSID from the scan list. Write the credential JSON
   (`{"ssid":"...","password":"..."}`) to Wi-Fi Config. Wi-Fi Status
   will push `{"status":"saved","ssid":"...","next":"connecting"}` and
   then progress through `connecting → connected → syncing → idle`.
5. No 2-minute wait — the firmware kicks off a Wi-Fi cycle the moment
   credentials are saved.

Scan result entries use short keys to keep notifications under the BLE
MTU: `s` = SSID, `r` = RSSI (negative dBm), `e` = 1 if encrypted else 0.
The list is capped at `MAX_SCAN_RESULTS = 12` strongest networks.

`Wi-Fi Status` strings emitted by the firmware:

| `status` | Meaning |
|---|---|
| `idle` | Wi-Fi radio is down |
| `scanning` | scan in progress (BLE-requested or periodic) |
| `connecting` | trying to join the saved SSID |
| `connected` | associated; about to NTP+POST |
| `syncing` | actively uploading buffered rows |
| `saved` | credential just written (will transition to `connecting` next) |
| `error` | with a `detail` field, e.g. `empty ssid`, `bad json`, `save failed` |

## 5. Setting wall-clock via BLE

Write to the **Set Wall Time** characteristic
(`b90e068f-8856-4cba-a043-841081fbd1a1`) with an ISO 8601 string, e.g.
`2026-05-19T14:32:11+05:30` or `2026-05-19T09:02:11Z`. The OLED switches
from "Session" to "Today (partial)" accumulation once accepted. After
the first NTP-driven Wi-Fi sync the firmware will keep the clock
authoritative on its own.

## 6. Bench-testing the sync path (Stage 6)

1. On your laptop:

   ```bash
   python3 tools/fake_ingest.py --port 8080
   ```

   Note the laptop's IP on the same network the ESP32 will join.

2. Edit `INGEST_URL` in `config.h` to:

   ```
   http://<laptop-ip>:8080/ingest
   ```

   (Plain http while bench-testing — the stub doesn't speak TLS.) Reflash.

3. Provision the ESP32 with your laptop's hotspot SSID via BLE (step 4
   above). Within ~2 minutes the device should connect, NTP-sync, and
   POST any buffered rows to the laptop. The stub prints each row and
   acknowledges with the highest seq. Re-run `INFO` over serial — the
   unsynced count should drop to zero.

## 7. BLE data dump (Stage 7)

In nRF Connect:

1. Read **Device Info** to see `unsynced_count`.
2. Subscribe to notifications on **Data Stream**
   (`1199716e-692b-4d47-bd00-72792988364d`). You'll receive one or more
   notifications, each containing one or more CSV rows separated by `\n`,
   ending with the literal `"END\n"` terminator.
3. After your client (or hand-rolled app) has forwarded the rows to your
   server, write the highest acknowledged `seq` as a decimal-string ASCII
   value to **Sync ACK** (`a4b32253-c2e3-42e8-93c3-a008325540b6`). The
   firmware truncates `/log.csv` up to and including that seq.

## Open security TODOs

These are documented now and will be addressed in a later session:

- **No BLE bonding/pairing** in v1 — anyone in range can read buffered
  readings and write Wi-Fi credentials. Acceptable for a single-user
  bench device; not acceptable for any deployment.
- **`setInsecure()` on HTTPS** — the firmware accepts any server certificate
  presented for `INGEST_URL`. The `X-Device-Token` header is the only
  authentication. Cert pinning (PROGMEM root CA) is a TODO in
  `wifi_sync.cpp`.
- **No HMAC payload signing** — a hostile Wi-Fi network can capture the
  token and replay POSTs. The server's `(device_id, seq)` upsert key
  bounds the damage but doesn't prevent it.
