# Solar AC-Side Power Monitor

Single-phase AC-side monitoring for a 3.3 kW rooftop solar installation. The
device measures inverter output with a PZEM-004T v3.0, displays live metrics
on a local OLED, buffers 15-minute readings to internal flash, and mirrors
them to a MilesWeb-hosted PHP/MySQL backend via two store-and-forward paths:

- **Wi-Fi** — when a known hotspot is in range, the ESP32 connects, NTP-syncs,
  POSTs buffered rows, and clears them on server ACK.
- **BLE** — a companion Android app pulls buffered rows over GATT and
  forwards them to the same MilesWeb endpoint over the phone's cellular
  data.

The ESP32 is the source of truth. Cloud and app are catch-up mirrors.

## Repository layout

```
firmware/solar_monitor/   ESP32 firmware (Arduino IDE sketch folder)
backend/                  MilesWeb PHP + MySQL (planned, not yet built)
android/                  Companion app (planned, not yet built)
docs/                     Wiring, provisioning, future hardware notes
tools/                    Bench-test helpers (fake_ingest.py)
```

## Status

This branch delivers **firmware Stages 1–7** per the project spec:

| Stage | Scope | Status |
|---|---|---|
| 1 | OLED bring-up | ✅ |
| 2 | PZEM bring-up | ✅ |
| 3 | PZEM + OLED integration | ✅ |
| 4 | LittleFS + NVS + boot history | ✅ |
| 5 | Backend (MilesWeb) | ⏳ next session |
| 6 | Wi-Fi sync end-to-end | ✅ (testable against `tools/fake_ingest.py`) |
| 7 | BLE GATT service | ✅ (testable with nRF Connect) |
| 8 | Android companion app | ⏳ next session |
| 9 | Backend dashboard | ⏳ next session |
| 10 | AC install | hardware, out of code scope |

## Building the firmware

See [`docs/PROVISIONING.md`](docs/PROVISIONING.md) for the full step-by-step.
Quick path:

1. Arduino IDE 2.x with libraries: U8g2, PZEM-004T-v30 (mandulaj),
   ArduinoJson, NimBLE-Arduino.
2. Board: **ESP32 Dev Module**, partition scheme:
   **Default 4MB with spiffs (1.2MB APP/1.5MB SPIFFS)**.
3. Open `firmware/solar_monitor/solar_monitor.ino` and Upload.

## Bench-testing without a backend

Run the stub:

```bash
python3 tools/fake_ingest.py --port 8080
```

Point `INGEST_URL` in `firmware/solar_monitor/config.h` at
`http://<laptop-ip>:8080/ingest`, reflash, and the device will exercise its
full sync path against the stub.

## Architecture quick reference

- **Two FreeRTOS tasks** pinned to separate cores:
  - Core 0 — sampling: 1 Hz PZEM read + OLED render. Every 15 min appends
    one row to `/log.csv` and advances `last_seq`.
  - Core 1 — connectivity: BLE GATT always advertising. Every 2 min
    attempts the Wi-Fi cycle (scan → connect → NTP → POST → ACK truncate).
- **Single mutex** protects the small shared-state POD; readers snapshot
  a value copy and release before doing any I/O.
- **Monotonic-µs clock** for energy integration (`esp_timer_get_time()`);
  wall-clock is only used for log timestamps and midnight rollover.
- **NVS** uses two namespaces (`cfg` for Wi-Fi credentials, `state` for
  boot id / seq HWM / boot history) and a high-water-mark scheme that
  persists every 10 seqs to limit flash wear at the cost of small
  monotonic gaps across crashes.
- **`/log.csv`** is append-only with crash recovery on boot (deletes any
  leftover `/log.tmp`, validates and trims the last line). Sync uses
  snapshot-and-rewrite: capture max seq from RAM, send rows ≤ that, on
  ACK rewrite the file keeping only rows > that.
- **Buffer full**: when LittleFS free space drops below max(150 KB, 10 %),
  the firmware stops logging and surfaces "BUFFER FULL" on the OLED.

See [`docs/PROVISIONING.md`](docs/PROVISIONING.md) for security TODOs
(no BLE bonding, no cert pinning, no HMAC) deferred to future hardening.
