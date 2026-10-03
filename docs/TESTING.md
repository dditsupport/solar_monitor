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
| `backend/` | 48 API checks: ingest, counter resets, PZEM swap, row timestamps, seq renumbering (flash erase, clash backstop), CSRF, device claiming, admin notes / interval, MySQL time zone. 12 browser checks on the dashboard: ranges, Peak, readings table, Meter reading, slow-response race, session expiry. | `mariadb-server`, `php` with `pdo_mysql`, `python3`. Dashboard part: `node` + `playwright` (skipped without them). |
| `firmware/` | Date maths vs the C library for every day 1970–2105, ISO-8601 parsing, `/log.csv` row parse / format / renumber. Runs for all three variants. | `g++`, `python3` |
| `android/` | 11 tests on the app's real `DeviceSyncer.kt` and JSON models: 500-row chunks, partial failure, network error, no ack without acceptance, row timestamps, flash-erase renumbering, clash after accepted chunks, no endless retry, unchanged API JSON keys. The app files it compiles must be warning-free. | JDK 17+, Gradle 9.x (downloads Kotlin from Maven Central on first run) |

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
2. phpMyAdmin → SQL tab → run `backend/migrations/009_energy_resets_and_cleanup.sql` **once**.
3. On each device, check that the unsynced count is **0** (serial `INFO`, or
   Device Info in the app). Then flash firmware **1.1.0**. Rows still buffered
   in the old format are discarded by 1.1.0.
4. Install the new app build.

**How to look at the results:**

- **Serial monitor:** 115200 baud. Commands: `INFO`, `SYNC`, `LOG`, `DUMP`, `BOOTS`.
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
| A11 | Phone width | Nothing overflows sideways; the cards stack 2×2. |

### B. Reports page

| # | Do | Expect |
|---|---|---|
| B1 | Open Reports | From and To sit side by side (on a phone too). |
| B2 | Weekly ↔ Monthly, switched quickly | The title and data match the last click. Total = sum of the day chips. |

### C. Admin

| # | Do | Expect |
|---|---|---|
| C1 | Hover Old kWh / Adjust kWh | The help text talks about the **Meter reading** card, not Period total. |
| C2 | Put text in `energy_devices.notes` (phpMyAdmin), then click Save in admin | Notes unchanged afterwards. |
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
| E1 | Interval 60 s. Disconnect the PZEM's TX wire for ~3 minutes, then reconnect | OLED variants show PZEM ERROR meanwhile. No rows for those minutes and **no zero rows** (zero-row query = 0). The dashboard shows a gap, not a spike. |
| E2 | Hotspot OFF. Let 3 rows log. Power the device off 10 min, power on, let 3 more log, hotspot ON | All 6 rows arrive. The 3 before the outage have `wall_time` = when they were really logged (10-min gap visible) and `time_confidence = exact`. |
| E3 | After boot, watch serial | `NTP sync ok` only after Wi-Fi connects. Admin RTC drift shows a real value, not always 0 s right after each reboot. |
| E4 | Unplug/replug power 5 times, each under 60 s | Serial: `BOOT-LOOP TRIPPED (consec=5)`. About 10 min later: `re-enabling Wi-Fi`, then `POST ok`. |
| E5 | Last sync on the dashboard | Matches the device's real last POST (IST). |

### F. Phone relay (app)

| # | Do | Expect |
|---|---|---|
| F1 | Hotspot OFF for a few hours (backlog), then app → device → **Sync** | "Synced N row(s)". No GATT error. The device's unsynced count drops to ~0 within a few seconds. All N rows are in the database. |
| F2 | *(optional, overnight)* Backlog over 500 rows (interval 60 s, ~9 h offline) → Sync | Same as F1. `ingest_log` shows several uploads of ≤ 500 rows each. |

### G. Reading numbers & meter swaps

| # | Do | Expect |
|---|---|---|
| G1 | App → **Reset energy meter to 0** | OLED total restarts near 0. Dashboard: no spike, no zero day. Meter reading keeps rising from where it was. `energy_resets` has one new row. |
| G2 | Physically fit a **new** PZEM (unused) | Same as G1. |
| G3 | App → **Erase device data (factory reset)** | After the reboot, `INFO` shows unsynced = 0. On first sync, serial shows `server holds seqs up to …` then `POST ok`. `ingest_log` shows `seq_base` then `ok`. |
| G4 | **Full flash erase:** Arduino IDE → Tools → *Erase All Flash Before Sketch Upload: Enabled* → upload 1.1.0. Set Wi-Fi again in the app. | Serial: `server holds seqs up to N - renumbering buffered rows`, then `POST ok`. New rows have seq > N. History is intact, with no gap apart from the downtime. |
| G5 | Repeat G4, but with the hotspot OFF. Let 2 rows log, then app → **Sync** | The app re-syncs on its own and reports "Synced". `ingest_log`: `seq_base`, then `ok`. Rows stored with seq > N. |

### Known limits (not bugs to report)

- A **used** PZEM with energy already on its counter is not handled
  automatically. Its existing count can show up as one day's generation.
  Correct it with Adjust kWh.
- A new ESP32 board has a new MAC, so it gets a **new device ID** and appears
  as a new device.
