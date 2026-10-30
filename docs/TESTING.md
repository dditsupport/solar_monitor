# Testing

Two layers:

1. **Automated tests** (`tests/`): run on any Linux machine or WSL, no devices
   needed. They cover the logic, including the cases that are hard to create by
   hand (racing clicks, CSRF, seq clashes, partial uploads, calendar edge cases).
2. **Manual checklist** (section 3): the same features on the real server, real
   devices and the real app, which no automated test can stand in for.

Run the automated suites first; if they pass, work through the checklist.

---

## 1. Automated tests

```bash
tests/run_all.sh          # everything
tests/backend/run.sh      # API + dashboard
tests/firmware/run.sh     # firmware logic on the PC
tests/android/run.sh      # app sync logic on the JVM
```

| Suite | Covers | Needs |
|---|---|---|
| `backend/` | 71 API checks: device owners manage their own devices (rename, meter baseline, interval, maintenance, release, delete) and nobody else's, Reset PZEM queued from the web and delivered on the device's Wi-Fi sync only until it confirms, ingest, counter resets, PZEM swap, row timestamps, seq renumbering (flash erase, clash backstop), CSRF, device claiming, admin notes / interval, MySQL time zone. 21 browser checks on the dashboard and Devices page: an owner saves her device and queues Reset PZEM, section order, ranges, Peak, readings table, Meter reading, phone layout (no sideways scroll, charts keep a real plot), slow-response race, session expiry. | `mariadb-server`, `php` with `pdo_mysql`, `python3`. Dashboard part: `node` + `playwright` (skipped without them). |
| `firmware/` | Date maths vs the C library for every day 1970–2105, ISO-8601 parsing, `/log.csv` row parse / format / renumber, BOOT button gestures (factory erase vs Wi-Fi reset), the setup hotspot's close rule, the Wi-Fi disconnect-reason texts, and the real `run_scan()` against a crowded fake scan (BLE scan JSON keeps `s`/`r`/`e`, adds `c`/`a`, strongest first, never over the 512-byte BLE value limit). Runs for all four variants (incl. the Wi-Fi test build), and checks the Wi-Fi test build is still in step with `solar_monitor_ds1307` (only its fake `pzem` / `rtc` / `coin_cell`, `identity.cpp`, `FW_VERSION` and `.ino` may differ). Plus the setup hotspot's page in a phone-sized browser (light / dark) against mocked device endpoints: network list (a name with HTML in it stays text), short / wrong password, connected, hidden network, no sideways scroll. | `g++`, `python3`; page check: `node` + `playwright` (skipped without them) |
| `android/` | 27 tests on the app's real `DeviceSyncer.kt`, `CloudSignIn.kt`, `DashboardRanges.kt` (Cloud tab ranges, labels and numbers, kept in step with the web dashboard) and JSON models: sign-in check before any device is read (live session, renewed with the saved sign-in, none saved, rejected password forgotten, offline), 500-row chunks, partial failure, network error, no ack without acceptance, row timestamps, flash-erase renumbering, clash after accepted chunks, no endless retry, unchanged API JSON keys, and no ack for a Bluetooth dump that lost pieces (re-read once; firmware 1.1.0 checked against `expected_row_count`), a device is read even when it reports `unsynced_count` 0, and an upload is announced so the Cloud tab reloads (one that the server refused is not). The app files it compiles must be warning-free. | JDK 17+, Gradle 9.x (downloads Kotlin from Maven Central on first run) |

The backend suite builds a throwaway MariaDB from `backend/schema.sql` and
serves `backend/public_html` with PHP's built-in server. It never touches the
real database. If the test machine cannot reach the Chart.js CDN, set
`CHARTJS_DIR` to a folder holding `chart.umd.js` and
`chartjs-adapter-date-fns.bundle.min.js`.

**Firmware build check:** open each of the three sketches in Arduino IDE and
press **Verify**. All three must compile.

---

## 2. Before testing on the real system

Deploy in this order:

1. Upload `backend/public_html` to the server.
2. phpMyAdmin → SQL tab → run `backend/migrations/009_energy_resets_and_cleanup.sql` **once**
   (if not done yet), then `backend/migrations/010_device_commands.sql` **once**
   (needed for Reset PZEM on the Devices page).
3. On each device, check that the unsynced count is **0** (serial `INFO`, or
   Device Info in the app). Then flash firmware **1.2.0**. Rows still buffered
   in the pre-1.1 format are discarded by 1.1.x and later.
4. Install the new app build.

**How to look at the results:**

- **Serial monitor:** 115200 baud. Commands: `INFO` (also prints the PZEM state and last reading), `SYNC`, `LOG`, `DUMP`, `BOOTS`.
  The PZEM is traced once at boot (`[pzem] ok: … V, … A, … W, … kWh`) and then only when its state changes (`[pzem] no response …`, `[pzem] sensor fault …`, `[pzem] back: …`).
- **phpMyAdmin:** the queries below. Replace `solar-xxxxxx` with the device ID.

```sql
-- latest readings
SELECT seq, boot_id, wall_time, time_confidence, voltage, power_w, energy_wh
  FROM solar_readings WHERE device_id = 'solar-xxxxxx' ORDER BY seq DESC LIMIT 30;

-- counter drops recorded (PZEM reset / swap / rollover)
SELECT * FROM energy_resets WHERE device_id = 'solar-xxxxxx';

-- what the server did with each upload ('ok', 'seq_base', ...)
SELECT received_at, rows_in_payload, rows_inserted, status, notes
  FROM ingest_log WHERE device_id = 'solar-xxxxxx' ORDER BY id DESC LIMIT 20;

-- must always be 0
SELECT COUNT(*) FROM solar_readings WHERE voltage <= 0 AND energy_wh <= 0;
```

**Tip:** in the admin page set the device's Interval to `60` while testing,
so a row is logged every minute instead of every 15. Clear the field afterwards
to go back to the default.

---

## 3. Manual checklist

### A. Dashboard

| # | Do | Expect |
|---|---|---|
| A1 | Open the dashboard → Today | X axis runs 6AM–7PM. Cards: Current, Peak, Period total, Meter reading (no Today card). |
| A2 | Look under the chart | `Meter reading: a → b kWh (d kWh)`, where d equals Period total. |
| A3 | Readings table | One row per bar. Each row's End equals the next row's Start, and End − Start = Generated. The Total row equals Period total. The newest End reading equals the Meter reading card. |
| A4 | Click Today, 7 days, 30 days, 12 months | Meter reading stays the same on every range. Period total changes and is never ~21,000. |
| A5 | 7 days / 30 days | Exactly 7 / 30 rows, the oldest at midnight. No small "partial" first bar. 12 months starts on the 1st of a month. |
| A6 | Peak on 7 days | Close to the real highest wattage of the week (compare with Today on a sunny day), not a few hundred W. |
| A7 | Click Custom | From / To appear only now. Pick dates → Apply loads them. Pressing Enter in a date field also applies. From after To → warning, chart unchanged. Clicking 7 days hides the filter again. |
| A8 | "Last sync" | On its own line below the buttons. IST time, and "(x min ago)" is right. |
| A9 | Click 30 days, then Today immediately | The chart ends on Today, not 30 days. |
| A10 | Sign out in another tab, then click a range | You go to the login page; no "login_required" pop-up. |
| A11 | Phone width | Nothing overflows sideways; the cards stack 2×2; the range buttons fit on one row. |
| A12 | Order of the sections | Energy chart, then Power, then Meter readings per bar (same as the app). |
| A13 | Switch the phone / PC to dark mode | Page and charts turn dark (green bars, amber power line) without reloading. |

### A2. App Cloud tab (should match the web dashboard)

| # | Do | Expect |
|---|---|---|
| A2.1 | App → Cloud, Today, next to the web dashboard on Today | Same Current, Peak, **Period total** (e.g. 15.26, not ~21,000) and **Meter reading**. Chart X axis 6AM–6PM. "Meter reading: a → b kWh (d kWh)" under it equals the web's. |
| A2.2 | Scroll to "Meter readings per bar" | Same rows as the web table: Hour, Generated (green), Start, End, newest first; Total = Period total. |
| A2.3 | Tap 7 days, 30 days, 12 months | 7 / 30 day bars, 12 month bars; Meter reading card unchanged; the whole range fits on screen (no sideways scroll). |
| A2.4 | Tap Custom | From / To appear only now; pick dates, Apply loads them. From after To → message, chart unchanged. Tapping 7 days hides the filter. |
| A2.5 | Look at "Last sync" | Same time as the web; "(x min ago)" advances while the screen is open. |
| A2.6 | Devices → **Sync now** (or a device's own Sync), then open the Cloud tab | New rows and the new Last sync show without restarting the app. If the Cloud tab is already open, it reloads by itself a moment after the sync. |
| A2.7 | Pull the Cloud tab down | Spinner, then fresh data (picks up rows a device sent over Wi-Fi). Leaving the app and coming back after 20 s also reloads. |

### B. Reports page

| # | Do | Expect |
|---|---|---|
| B1 | Open Reports | From and To sit side by side (on a phone too). |
| B2 | Weekly ↔ Monthly, switched quickly | The title and data match the last click. Total = sum of the day chips. |

### C. Devices page (owners and admins)

| # | Do | Expect |
|---|---|---|
| C0a | Sign in as a normal user who owns a device → **Devices** in the header | "My devices" lists only that user's devices; no Owner picker; "Release from my account" shown. |
| C0b | Change Name / Location / Old meter kWh / Adjust kWh → Save | "Saved." under the card; the dashboard shows the new name and Meter reading. |
| C0c | Set Log every / maintenance → Set | "Saved. Takes effect on the device's next sync." The device's serial shows the new value after its next sync. |
| C0d | Release from my account | The card disappears; the device is unowned and can be claimed again from the app. |
| C0e | Sign in as admin → Devices (or the old `/admin/devices.php` link) | "All devices" with an Owner picker on each card. |
| C0f | Reset PZEM to 0 → OK, on a device that syncs over Wi-Fi (firmware 1.2.0) | "Reset pending — applies on the device's next Wi-Fi sync". On that sync the serial shows `[cmd] reset_pzem #N from server`, the PZEM counter restarts at 0, and on reload the note is gone (`device_commands.done_at` set). The dashboard's Meter reading carries on without a jump. |
| C0g | Delete device → OK | Gone from the list; all its cloud records deleted. The device keeps running; after its next sync it appears again (admin view, unowned) and is claimed again from the app or by an admin. |
| C1 | Hover Old meter kWh / Adjust kWh | The help text talks about the **Meter reading** card, not Period total. |
| C2 | Put text in `energy_devices.notes` (phpMyAdmin), then click Save on the Devices page | Notes unchanged afterwards. |
| C3 | Clear the Interval field → Set | `device_meta.log_interval_sec` = 0. The device's serial shows `log_interval_sec from server: 900` on its next sync. |
| C4 | Interval `300` → Set | The device switches to 5-minute rows. |

### D. Accounts & security

| # | Do | Expect |
|---|---|---|
| D1 | User B, in the app: Register with cloud on a device owned by user A | "This device is registered to another account…". The owner is unchanged. |
| D2 | Admin moves the device in the admin page | Works as before. |
| D3 | Signed in to the dashboard as a **non-admin**, DevTools console: `fetch('/api/claim_device.php',{method:'POST',body:new URLSearchParams({device_id:'test'})}).then(r=>r.status)` | `403` |

### E. Device data (firmware)

| # | Do | Expect |
|---|---|---|
| E1 | Interval 60 s. Disconnect the PZEM's TX wire for ~3 minutes, then reconnect | Serial: `[pzem] no response - check RX/TX wiring…` within ~3 s, then `[pzem] back: …` on reconnect. OLED variants show PZEM ERROR meanwhile. No rows for those minutes and **no zero rows** (zero-row query = 0). The dashboard shows a gap, not a spike. |
| E2 | Hotspot OFF. Let 3 rows log. Power the device off 10 min, power on, let 3 more log, hotspot ON | All 6 rows arrive. The 3 before the outage have `wall_time` = when they were really logged (10-min gap visible) and `time_confidence = exact`. |
| E3 | After boot, watch serial | `NTP sync ok` only after Wi-Fi connects. Admin RTC drift shows a real value, not always 0 s right after each reboot. |
| E4 | Unplug/replug power 5 times, each under 60 s | Serial: `BOOT-LOOP TRIPPED (consec=5)`. About 10 min later: `re-enabling Wi-Fi`, then `POST ok`. |
| E5 | Last sync on the dashboard | Matches the device's real last POST (IST). |
| E6 | Hotspot ON, default 900 s interval, serial monitor open for 45 min | A `POST ok` about every 15 min and **no** `[health] stuck-wifi` / `radio rest` lines between them. |
| E7 | Jio AirFiber / Airtel Xstream router (set it to ch 13 if it allows), device given its Wi-Fi | Serial: one `[wifi] "<ssid>" AA:BB:.. ch 13 … dBm auth=…` line per 2.4 GHz radio, then `connecting to "<ssid>" (pinned … ch 13)` and `connected`. |
| E8 | After E7, change the router's channel and reboot it | One `did not connect (… reason=… <text>)`, `pinned attempt failed - retrying unpinned`, then `connected` in the same cycle. |
| E9 | Hidden phone hotspot | `not in scan (hidden, asleep or weak) — trying anyway`, then an unpinned `connecting to …` and `connected` (unchanged behaviour). |
| E10 | Android app → Wi-Fi scan in a busy area (10+ networks) | The list is shown (strongest networks; the weakest may be left out), never empty. |

### E2. Buttons and the Wi-Fi setup hotspot (firmware 1.2.0, no app)

| # | Do | Expect |
|---|---|---|
| H1 | Hold BOOT 3 s while the device runs | Serial: `[setup] BOOT held - Wi-Fi erased, opening the setup hotspot`, then `[setup] hotspot on: join "SolarMonitor-XXXX"`. LED double-blinks; OLED shows the name, `Pass: Solar123`, `Open 192.168.4.1`. |
| H2 | iPhone: join `SolarMonitor-XXXX` with `Solar123` | The setup page pops up by itself (or open http://192.168.4.1). Your router is in the list. |
| H2b | Android: join it, open the page from the *Sign in to network* notification, leave it open 2 min | The phone stays on the setup network. Serial shows **one** `scan complete` when the hotspot opened, and another only after tapping *Scan again* (never a stream of them). |
| H3 | Pick the router, type a **wrong** password, Save | "Connecting…", then "Wrong password". The hotspot stays up; try again works. |
| H4 | Right password, Save | "Connected to … (IP)". About a minute later the setup network disappears (`[setup] hotspot off (connected)`, then `[setup] setup done - restarting for a clean heap`), the phone goes back to its Wi-Fi, the device reboots, connects to the new network and its first `POST ok` reaches the dashboard — no `low heap — deferring POST` lines. |
| H5 | Reboot after H4 | Still on the new router (the compiled default Wi-Fi does **not** come back). |
| H6 | Hold BOOT 3 s, join, then hold BOOT 3 s again | `[setup] hotspot off (button)`. No router saved now, so the device stays offline until H1 is repeated. |
| H7 | Turn the router off for 20 min; separately, erase Wi-Fi from the app | The setup hotspot does **not** appear in either case. |
| H8 | Press and release RESET, wait ~1 s, hold BOOT 3 s, then release | LED steady on during the 5 s window and while held; after 3 s it blinks once (`erase armed`). Release BOOT: LED goes out, serial `[reset] factory erase by button`; reboot with fresh counters. The next sync renumbers on the server (`seq_base`) and no cloud data is lost. |
| H9 | Same as H8 but release BOOT after 1 s | `erase cancelled`, normal boot, nothing erased. |
| H9b | Same as H8 but keep holding BOOT after the blink for 10 s | Nothing is erased while BOOT is held; the erase runs only when it is released. |
| H10 | Hold BOOT **while** releasing RESET | No firmware output (ROM download mode). Press RESET: normal boot, nothing erased. |
| H12 | Power-cycle 6 times quickly (serial: `BOOT-LOOP TRIPPED … BLE-only mode`), then hold BOOT 3 s | `[health] boot-loop trip cleared: setup started with BOOT`, then `scan complete`; the page lists networks; save → connected → hotspot off → restart. |
| H11 | Android app: Wi-Fi setup, Sync now, Reset PZEM, Erase device data | All work exactly as before. |

### F. Phone relay (app)

| # | Do | Expect |
|---|---|---|
| F1 | Hotspot OFF for a few hours (backlog), then app → device → **Sync** | "Synced N row(s)". No GATT error. The device's unsynced count drops to ~0 within a few seconds. All N rows are in the database. |
| F1b | Same as F1 with a backlog of 50+ rows, serial monitor open | One tap syncs **all** rows (the message shows the full count, Device Info unsynced drops to ~0). Serial: `[ble] stream: sent N row(s), MTU …` with N = the backlog. |
| F1c | Sign in on the Cloud tab with **Remember me** ticked. Leave the app unused for more than 30 min (the old session would have expired), then tap **Sync now** | Syncs normally; no "Sign in on the Cloud tab" message. |
| F1d | Cloud tab → **Sign out**, then **Sync now** | Banner: "Sign in on the Cloud tab … No device was touched". No device line shows "Connecting…". |
| F2 | *(optional, overnight)* Backlog over 500 rows (interval 60 s, ~9 h offline) → Sync | Same as F1. `ingest_log` shows several uploads of ≤ 500 rows each. |

### G. Reading numbers & meter swaps

| # | Do | Expect |
|---|---|---|
| G1 | App → **Reset energy meter to 0** | OLED total restarts near 0. Dashboard: no spike, no zero day. Meter reading keeps rising from where it was. `energy_resets` has one new row. |
| G2 | Physically fit a **new** PZEM (unused) | Same as G1. |
| G3 | App → **Erase device data (factory reset)** | After the reboot, `INFO` shows unsynced = 0. On first sync, serial shows `server holds seqs up to …` then `POST ok`. `ingest_log` shows `seq_base` then `ok`. |
| G4 | **Full flash erase:** Arduino IDE → Tools → *Erase All Flash Before Sketch Upload: Enabled* → upload 1.1.3. Set Wi-Fi again in the app. | Serial: `server holds seqs up to N - renumbering buffered rows`, then `POST ok`. New rows have seq > N. History is intact, with no gap apart from the downtime. |
| G5 | Repeat G4, but with the hotspot OFF. Let 2 rows log, then app → **Sync** | The app re-syncs on its own and reports "Synced". `ingest_log`: `seq_base`, then `ok`. Rows stored with seq > N. |

### Known limits (not bugs to report)

- A **used** PZEM with energy already on its counter is not handled
  automatically. Its existing count can show up as one day's generation.
  Correct it with Adjust kWh.
- A new ESP32 board has a new MAC, so it gets a **new device ID** and appears
  as a new device.
