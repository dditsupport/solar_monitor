#pragma once

// ---------- Identity / build ----------
#define FW_VERSION              "1.0.0"

// ---------- Backend ----------
// The ingest endpoint URL is split into two parts:
//   INGEST_HOST_DEFAULT - scheme + host + optional port, e.g. "https://solar.aromen.biz"
//                         Stored in NVS and configurable at runtime via BLE
//                         (Server Config characteristic). This default is only
//                         used if NVS has not been written.
//   INGEST_PATH         - the path component, hardcoded in firmware. The
//                         backend is expected to keep this stable.
// Full URL = NVS host (or INGEST_HOST_DEFAULT) + INGEST_PATH.
//
// To switch backend hostnames at runtime, write {"host":"https://newdomain.com"}
// from the companion app — no reflash needed.
#define INGEST_HOST_DEFAULT     "https://solar.aromen.biz"
#define INGEST_PATH             "/api/ingest.php"
#define DEVICE_TOKEN            "hs2AfGYZqZSFbb_rp-t3zy_I_rXb5TJISpn6Okih4pg"

// ---------- Wi-Fi (optional bench-test fallback) ----------
// If non-empty, the firmware writes these to NVS at boot whenever the saved
// list is empty. Useful when you don't yet have the companion app running and
// just want to bring the device online for testing. Leave both empty to force
// BLE-only provisioning (the production flow).
#define WIFI_SSID               "TP-Link_Second_Floor"
#define WIFI_PASSWORD           "1234567890"

// ---------- Timing ----------
// LOG_INTERVAL_SEC_DEFAULT is the cadence used when the server has not (yet)
// pushed a different value via the ingest.php response. The runtime value
// lives in NVS and is settable from the server: each POST response may
// include {"log_interval_sec": N}, and the firmware will use N until told
// otherwise.
//
// PRODUCTION default: 900 (15 minutes), per spec §3.7. This is also what the
// server pushes to deployed devices, so a freshly flashed unit now matches the
// fleet before its first ingest response instead of logging 15x too fast for
// its first sync.
// Sanity bounds enforced in storage::set_log_interval_sec(): 60..86400.
#define LOG_INTERVAL_SEC_DEFAULT 900
#define LOG_INTERVAL_SEC_MIN     60
#define LOG_INTERVAL_SEC_MAX     86400
#define SAMPLE_INTERVAL_MS       1000     // 1 Hz PZEM sample cadence (no OLED)
#define WIFI_SCAN_INTERVAL_SEC  120       // 2 minutes between Wi-Fi cycles
#define NTP_SYNC_TIMEOUT_MS     5000
#define NTP_RESYNC_INTERVAL_SEC 300       // re-hit the NTP server at most every 5 min
#define WIFI_CONNECT_TIMEOUT_MS 15000
#define HTTP_TIMEOUT_MS         10000    // response read

// TCP connect and TLS handshake caps for the ingest POST.
//
// WHY THESE EXIST: http.setTimeout() bounds only the RESPONSE READ. It does not
// bound the TLS handshake, which WiFiClientSecure leaves at its 120 s default.
// health::begin() installs a 30 s task watchdog with trigger_panic = true, so a
// single stalled handshake on a marginal link (a phone hotspot is the classic
// case) blocks the connectivity task straight past the watchdog and PANIC-REBOOTS
// the chip — the recurring `task_wdt: conn` reset. Worse, it reboots before the
// stuck-Wi-Fi watchdog can ever reach STUCK_WIFI_REBOOT_SEC, because uptime
// returns to zero each time; the device can sit in that loop indefinitely,
// never completing a POST.
//
// WDT BUDGET. The connectivity task feeds the watchdog at the top of its loop,
// inside the Wi-Fi connect wait, inside the NTP wait, and immediately before the
// POST. The POST is then the longest single unfed span, so its three phases must
// stay clear of 30 s:
//     TCP_CONNECT_TIMEOUT_MS   5 s
//   + TLS_HANDSHAKE_TIMEOUT_S  8 s
//   + HTTP_TIMEOUT_MS         10 s
//   = 23 s, leaving ~7 s of margin.
// Raising any of these three means re-checking that sum against the 30 s in
// health.cpp. A handshake that fails fast simply retries next cycle, which costs
// one sync interval; a handshake that runs long costs a reboot.
#define TCP_CONNECT_TIMEOUT_MS  5000
#define TLS_HANDSHAKE_TIMEOUT_S 8

// Heartbeat: even when /log.csv is empty, force a POST at least this often so
// the server can push log_interval_sec / server_time / future config knobs.
// Also fires once on first Wi-Fi cycle after boot, so a fresh device picks
// up server-side cadence within seconds of getting online.
#define CONFIG_HEARTBEAT_SEC    3600

// ---------- Storage ----------
// Rows per POST. Deliberately small. Each row costs ~100 B of request body AND
// ~10 ArduinoJson slots, and with the bundled ArduinoJson 7 (libraries.zip) the
// document is heap-allocated on demand in 1 KB pools rather than reserved up
// front — StaticJsonDocument<N>'s N is a deprecated no-op there, so the "16384"
// in post_batch() reserves nothing.
//
// At 100 rows a single POST therefore churned ~9 KB of pool plus an ~11 KB body
// String through the heap every cycle. Worse, that was self-worsening: once
// POSTs began failing the backlog grew until every retry hit the 100-row cap, so
// each attempt demanded MORE contiguous memory than the one that had just
// failed, and a fragmented heap could never claw its way back. 25 keeps the peak
// near 4 KB and costs only more frequent, individually cheaper POSTs.
#define SYNC_BATCH_SIZE         25        // rows per POST

// Size of the static request-body buffer in wifi_sync.cpp. Must exceed the
// largest body SYNC_BATCH_SIZE can produce (~5 KB worst case at 25 rows with a
// full MAX_BOOT_HISTORY). post_batch() measures the document first and refuses
// to POST rather than truncate into invalid JSON, logging loudly if this is too
// small — so raising SYNC_BATCH_SIZE means raising this too. Lives in .bss:
// costs a fixed 8 KB of DRAM and removes a per-cycle heap allocation that was
// built through hundreds of reallocations.
#define POST_BODY_BUF_BYTES     8192
#define MAX_BOOT_HISTORY        32        // circular buffer entries
#define MAX_WIFI_CREDS          1         // only one network at a time
#define SEQ_HWM_STRIDE          10        // NVS write batching for last_seq
#define BUFFER_FREE_MIN_BYTES   (150 * 1024UL)
#define BUFFER_FREE_MIN_PCT     10        // also keep >= 10% free
#define MAX_SCAN_RESULTS        12        // top-N APs returned over BLE

// ---------- Fault thresholds ----------
#define PZEM_FAIL_THRESHOLD     3         // consecutive Modbus fails -> PZEM ERROR
#define SENSOR_LOW_V_THRESHOLD  50.0f     // V < this for SENSOR_FAULT_WINDOW = SENSOR? fault
#define SENSOR_FAULT_WINDOW_SEC 60

// ---------- Demo mode ----------
// Set to 1 to bypass the real PZEM and feed the rest of the firmware
// synthetic (but plausible) readings. Lets you bench-test the
// LittleFS logging, Wi-Fi sync, and BLE characteristics without having
// the PZEM physically wired. Leave at 0 for production / real measurements.
#define PZEM_DEMO_MODE          0

// ---------- Boot-loop guard ----------
#define BOOTLOOP_WINDOW_SEC     60
#define BOOTLOOP_THRESHOLD      5         // boots inside the window -> BLE-only mode

// "Stuck" watchdogs. Independent of the task WDT (which catches frozen
// tasks within 30 s) — these catch the subtler failure modes where every
// task keeps running but the radio is silently dead.
//   STUCK_WIFI: time since last successful ingest POST. Only trips after
//               at least one successful POST has ever happened — so a
//               brand-new device with no Wi-Fi credentials won't reboot.
//   STUCK_BLE:  time since BLE was last 'alive' (advertising or connected).
//               Trips only if NimBLE wedged so badly that advertising stops.
// ---------- Stuck-Wi-Fi escalation ----------
// FIELD DATA (device solar-475b78, 6 days): the previous rule — "no successful
// POST for 6 h => the radio is wedged, reboot" — was rebooting healthy units
// roughly twice a day. Six of ten reboots landed 6h00m02s..6h00m11s after the
// last successful POST, to the second. The assumption was simply wrong for a
// device synced from a phone hotspot that is only switched on morning and
// evening: the measured median gap between syncs was 6.6 h and the maximum 33 h,
// so being offline for six hours is NORMAL OPERATION, not a fault.
//
// The real fault signal is being ASSOCIATED and still unable to POST. So the
// counter now only advances while WiFi.isConnected(), and resets whenever the
// link drops or a POST lands. Because that can no longer be tripped by ordinary
// offline time, the thresholds are safe to make aggressive — the old 6 h was
// both too eager (fired when nothing was wrong) and too slow (hours before
// reacting when something was).
//
// Escalation, cheapest first:
//   STUCK_WIFI_REASSOC_SEC — force a radio rest, i.e. a full reassociation with
//     a fresh DHCP lease and DNS servers. A stale association is the usual cause
//     and this costs one sync interval instead of a reboot. 0 disables.
//   STUCK_WIFI_REBOOT_SEC  — if reassociating did not help either, reboot.
// A boot that has never posted at all is exempt from both (a fresh or
// unprovisioned device must not reboot itself); the heap watchdog and the TLS
// handshake cap cover the ways a first POST can fail.
#define STUCK_WIFI_REASSOC_SEC  600       // 10 min associated, nothing posted
#define STUCK_WIFI_REBOOT_SEC   1800      // 30 min associated, nothing posted

// ---------- Nightly scheduled reboot ----------
// Reboot once a day in the small hours for a clean slate: fresh heap — the one
// resource that never heals on its own, since nothing compacts a C heap — plus
// fresh Wi-Fi and BLE stacks and every counter and timer reset. Cheap insurance
// against anything that degrades slowly and isn't otherwise caught.
//
// Nothing is lost. Buffered rows live in LittleFS and ship on the next sync;
// boot_id, seq and the today-energy anchor are all in NVS. The cost is a few
// seconds of downtime and the first log row of the new boot arriving one
// LOG_INTERVAL_SEC_DEFAULT later.
//
// The exact minute inside the window is derived from the device MAC, so a fleet
// spreads itself across the window instead of every unit rebooting on the same
// second and stampeding the ingest endpoint when they all come back.
//
// Fires at most once per local calendar day. The day it last fired is recorded
// in NVS, NOT in RAM — otherwise the reboot it causes would clear the flag and
// it would fire again a second later, in a loop. It also requires a trusted wall
// clock (no clock, no schedule), skips a device that booted less than
// NIGHTLY_REBOOT_MIN_UPTIME_SEC ago (already fresh), and defers while a phone is
// connected over BLE or a sync is in flight, retrying each second until the
// window closes.
//
// Set NIGHTLY_REBOOT_ENABLE to 0 to disable.
#define NIGHTLY_REBOOT_ENABLE         1
#define NIGHTLY_REBOOT_START_HOUR     2     // local time, inclusive
#define NIGHTLY_REBOOT_END_HOUR       5     // local time, exclusive
#define NIGHTLY_REBOOT_MIN_UPTIME_SEC 600   // don't reboot a device that just booted
#define STUCK_BLE_REBOOT_SEC    43200     // 12 h

// ---------- Periodic radio rest ----------
// Every RADIO_REST_INTERVAL_SEC the connectivity task takes the radio fully
// off-air for RADIO_REST_DURATION_SEC: the STA is disassociated and the Wi-Fi
// PHY is powered down (WIFI_OFF), and BLE advertising is stopped. Both then
// come back and a sync runs immediately on the fresh link.
//
// Why: try_connect_known() reuses an existing association whenever
// WiFi.status() reports WL_CONNECTED, and run_cycle() only tears the link down
// when that function fails. So if the STA is left holding a STALE association
// after the AP restarts — routine with a phone hotspot: screen off, band
// switch, DHCP renewal, a carrier blip — every POST fails at DNS/TCP while the
// driver still reports "connected", and nothing in the firmware ever forces a
// reassociation. Before this, the only escape was the STUCK_WIFI_REBOOT_SEC
// reboot 6 h later. The rest caps that window at one interval and costs a
// reassociation rather than a reboot, so uptime, boot_id and the buffered log
// all survive. It also gives the PA and the shared 2.4 GHz front end a
// periodic idle window.
//
// No data is lost across a rest: the sampling task keeps writing rows to
// LittleFS throughout and they ship on the cycle that follows. The rest is
// deferred while a phone is connected over BLE, so a provisioning session is
// never cut off mid-way.
//
// Set RADIO_REST_INTERVAL_SEC to 0 to disable the rest entirely.
// 0 = no blind periodic rest. Retired deliberately: it was written as THE fix
// for a stale association, before the stuck-Wi-Fi escalation gained an on-demand
// reassociation (STUCK_WIFI_REASSOC_SEC). That path does the same job strictly
// better — it fires only when the device is demonstrably associated-but-not-
// posting, instead of taking the radio off-air eight times a day on the chance
// something is wrong. Setting this to 0 disables ONLY the timer; the rest
// mechanism stays compiled and on-demand reassociation still works. Give it a
// non-zero interval only if field logs show stale associations that the
// on-demand path is not catching.
#define RADIO_REST_INTERVAL_SEC 0         // periodic rest disabled; on-demand still active
#define RADIO_REST_DURATION_SEC 45        // seconds fully off-air (<= 60)

// ---------- Heap guard + heap watchdog (TLS POST) ----------
// A TLS handshake needs one large CONTIGUOUS allocation for mbedTLS's record
// buffers (~16 KB with the IDF defaults), which makes it the first thing on this
// device to fail as the heap fragments — long before TOTAL free memory looks
// alarming. That is the failure mode where the STA stays associated, the app
// still shows "Connected", and every POST fails until the device is rebooted.
//
// So post_batch() checks BOTH numbers before opening the connection and defers
// the POST when either is short. Rows stay buffered; attempting a handshake into
// a starved heap risks a hard fault or heap corruption rather than a clean
// failure.
//
// Deferring does not RECOVER anything — a C heap never compacts, so a fragmented
// one does not heal on its own. Each deferral is therefore counted, and the heap
// watchdog in the connectivity task reboots once the count reaches
// HEAP_LOW_REBOOT_CYCLES. A reboot is the only real cure. At
// WIFI_SCAN_INTERVAL_SEC = 120 s, 5 cycles is ~10 minutes of sustained low heap,
// which also means the watchdog cannot fire in the first ten minutes after boot;
// health::boot_loop_tripped() is the backstop if it ever did start a loop.
// Set HEAP_LOW_REBOOT_CYCLES to 0 to defer only and never reboot.
//
// TUNE THESE from the "[wifi] heap free=… largest=… min=…" line logged after
// every POST — but note the defaults are deliberately biased LOW. Set them too
// HIGH and a healthy board defers every POST and reboots itself every ~10
// minutes, which is far worse than the fragmentation this is meant to catch. A
// healthy ESP32 running Wi-Fi + BLE + LittleFS typically shows a largest free
// block of 60-110 KB, so 20000 sits well clear of normal while still being only
// ~20% above the ~16.5 KB contiguous block mbedTLS actually needs. Raise them
// only once the logged numbers from YOUR board justify it.
// MEASURED on a real unit (solar-2e5694, SSD1306+DS3231, 2026-09-11), not
// guessed: a healthy board just after boot reports free ~23.5 KB, largest
// contiguous ~18.4 KB, and a minimum-free low-water mark of ~14.6-16.6 KB —
// i.e. a TLS POST costs roughly 7 KB at peak, not the ~16 KB assumed from
// ESP-IDF defaults. The previous values here (30000/20000) sat ABOVE a healthy
// board's normal state and would have tripped on every single POST.
//
// These are set below anything observed while still leaving margin over the
// ~7 KB a handshake actually needs. They remain PROVISIONAL until a few days of
// the heap log confirm the steady-state range on your fleet — do not raise them
// toward the observed idle figures, or a healthy device starts deferring.
#define HEAP_MIN_FREE_BYTES          12000
#define HEAP_MIN_LARGEST_BLOCK_BYTES  8000
// 0 = measure and report only; do not act. The heap-fragmentation theory these
// thresholds encode has NO supporting evidence from this fleet — the field data
// that was supposed to show it instead showed the stuck-Wi-Fi watchdog rebooting
// healthy units — and the numbers are guesses at what a healthy board of this
// build reports, not measurements of one. Acting on unvalidated thresholds risks
// a device that reboots every ~10 minutes, or (see post_batch) one that defers
// every POST forever. Both are worse than the problem.
//
// So: leave at 0, watch the "[wifi] heap free=… largest=… min=…" line for a few
// days, and only then set this non-zero — with thresholds taken from what YOUR
// boards actually report. Non-zero also re-enables deferring the POST when the
// heap is low, which is safe only when a reboot can recover from it.
#define HEAP_LOW_REBOOT_CYCLES       0

// ---------- Pin map (ESP32 DevKit V1) ----------
#define PIN_PZEM_RX             16        // ESP32 RX2 <- PZEM TX
#define PIN_PZEM_TX             17        // ESP32 TX2 -> PZEM RX
#define PZEM_BAUD               9600

// This variant has no OLED display; the SSD1306 SPI pins are left unwired.

#define PIN_I2C_SDA             4         // DS1307 SDA
#define PIN_I2C_SCL             13        // DS1307 SCL
#define I2C_FREQ_HZ             100000    // DS1307 is standard-mode only (100 kHz)

// ---------- RTC coin-cell sense (ADC1) ----------
// Senses the DS1307 backup coin cell (CR2032, ~3 V) — NOT the solar/main
// battery — for early warning before the RTC loses time. GPIO35 is an
// input-only pin on ADC1. ADC1 is unaffected by the Wi-Fi radio (which reserves
// ADC2), so this is a clean, always-readable sense line, and being input-only
// it can't drive anything — the GPIO-capable pins stay free for other uses. A
// CR2032 never exceeds ~3.3 V, so it wires straight to the pin with no divider.
// Tap the coin cell's + terminal (the module's VBAT node). The SAME pin is used
// on every firmware variant so wiring is identical no matter which build is
// flashed.
#define PIN_COIN_CELL_SENSE     35
#define COIN_CELL_SAMPLES       16        // ADC samples averaged per reading
#define RTC_WRITEBACK_DRIFT_SEC 2         // skip RTC writeback if NTP within this
#define RTC_DRIFT_LOG_INTERVAL_SEC 3600   // measure + report RTC-vs-NTP drift hourly

// ---------- Status LED ----------
// Wi-Fi activity indicator. GPIO 2 is the on-board LED on most ESP32 dev
// boards. Set ACTIVE_HIGH to 0 if your board's LED is wired active-low.
#define PIN_STATUS_LED          2
#define LED_ACTIVE_HIGH         1
#define LED_BLINK_SEARCH_MS     150       // toggle period while Wi-Fi disconnected
#define LED_BLINK_TX_MS         60        // toggle period during a data POST
#define LED_TX_PULSE_MS         800       // how long the TX flicker lasts per POST

// ---------- Time ----------
#define TZ_INFO                 "IST-5:30"   // POSIX TZ, used by setenv()
#define NTP_SERVER_1            "time.google.com"   // anycast, PoP in Mumbai/Chennai
#define NTP_SERVER_2            "in.pool.ntp.org"   // Indian pool

// ---------- BLE UUIDs (generated once, do not change) ----------
#define BLE_SERVICE_UUID        "5f12b3bc-8ef3-4b48-a971-f70a38f519ec"
#define BLE_UUID_DEVICE_INFO    "56c4fe7d-1c7d-4042-9547-6170ec5c243c"
#define BLE_UUID_SET_WALL_TIME  "b90e068f-8856-4cba-a043-841081fbd1a1"
#define BLE_UUID_BOOT_HISTORY   "d155756b-566e-4aa3-9fe5-c898f78fda8b"
#define BLE_UUID_DATA_STREAM    "1199716e-692b-4d47-bd00-72792988364d"
#define BLE_UUID_SYNC_ACK       "a4b32253-c2e3-42e8-93c3-a008325540b6"
#define BLE_UUID_WIFI_CONFIG    "41310027-c18e-4452-a50e-861e77cf2743"
#define BLE_UUID_WIFI_STATUS    "28c3fa43-a1b5-4e0e-a51c-a1e979609d28"
#define BLE_UUID_WIFI_SCAN      "d4346c1c-6e36-4a0f-a164-84cd396a4697"
#define BLE_UUID_SERVER_CONFIG  "9478f8ff-cb2f-4447-8a2f-49791de6bc09"
#define BLE_UUID_AUTH_CHALLENGE "85a1b1bb-7b81-43c8-9775-b5417e39e10d"
#define BLE_UUID_AUTH_RESPONSE  "257b8e6b-5ae7-44e8-a327-d6712a2f87aa"
#define BLE_UUID_DEVICE_COMMAND "8ef37e91-b324-47bf-bfa6-961b435fde4a"
#define BLE_UUID_COMMAND_RESULT "9ee9aca6-e6b8-4744-a3c6-677b60775bf5"

// ---------- BLE authentication (challenge-response) ----------
// We can no longer leave BLE open: anyone in range could otherwise read the
// buffered energy log and push Wi-Fi credentials. Instead the firmware and the
// companion app share a secret pre-shared key that is NEVER sent over the air.
//
// Flow (see ble_service.cpp):
//   1. On every connection the firmware generates a fresh random NONCE and
//      publishes it on the Auth Challenge characteristic.
//   2. The app computes HMAC_SHA256(key = BLE_PRESHARED_KEY, msg = NONCE) and
//      writes the hex digest to the Auth Response characteristic.
//   3. The firmware recomputes the same HMAC and constant-time compares. On a
//      match the connection is authenticated; until then every sensitive
//      characteristic (data stream, Wi-Fi/server config, sync-ack, set-time,
//      device info, boot history, Wi-Fi status/scan) is closed and returns
//      {"error":"unauthorized"}.
//
// The key never leaves either side, so a passive sniffer only ever sees a
// random nonce and a digest — neither of which reveals the secret, and the
// nonce rotates on every connection and every failed attempt to stop replay.
//
// PRODUCTION: change BLE_PRESHARED_KEY below and set the identical string in
// the Android app (BleAuth.PRESHARED_KEY in ble/BleAuth.kt). Any UTF-8 string
// works; its raw bytes are used as the HMAC key.
#define BLE_PRESHARED_KEY       "change-me-solar-monitor-preshared-key-v1"
#define BLE_AUTH_NONCE_LEN      16       // random challenge length in bytes

// ---------- Files ----------
#define LOG_PATH                "/log.csv"
#define LOG_TMP_PATH            "/log.tmp"

// ---------- Task config ----------
#define SAMPLING_TASK_STACK     6144
#define CONN_TASK_STACK         12288
#define SAMPLING_TASK_PRIO      3
#define CONN_TASK_PRIO          2
