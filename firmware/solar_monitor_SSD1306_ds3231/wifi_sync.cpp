#include "wifi_sync.h"
#include "config.h"
#include "shared_state.h"
#include "storage.h"
#include "identity.h"
#include "time_source.h"
#include "rtc.h"
#include "led.h"

#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>
#include <esp_task_wdt.h>
#include <esp_heap_caps.h>
#include <esp_system.h>
#include "log_serial.h"

// TODO: HMAC payload signing as a future hardening step. For v1, the only auth
// is the X-Device-Token header. Cert pinning is also TODO; we use setInsecure()
// to accept any server cert because the device speaks to a single endpoint
// configured at flash time and the token is the bearer credential.
//
// To enable cert pinning later: replace setInsecure() with setCACert(rootCA)
// and bundle the root cert as a PROGMEM blob in config.h.

namespace wifi_sync {

static volatile bool s_radio_busy = false;
static volatile bool s_immediate_sync_pending = false;
static volatile bool s_scan_pending = false;
static String s_scan_results_json = "[]";
static volatile uint32_t s_scan_version = 0;
// Time of last fully-successful POST round-trip. 0 means "never this boot",
// which forces a heartbeat POST on the first cycle so a fresh device picks
// up server-pushed config (log_interval_sec, server_time) without waiting
// for its first 15-min log row.
static uint64_t s_last_successful_post_us = 0;

// Consecutive Wi-Fi cycles whose POST was deferred by the heap guard below.
// Reset to 0 on any cycle where the heap was healthy. Read by the heap watchdog
// in the connectivity task, which reboots at HEAP_LOW_REBOOT_CYCLES.
static uint32_t s_low_heap_cycles = 0;

// Last STA disconnect reason, captured from the Wi-Fi event so a failed connect
// can say WHY. Common codes: 2 = AUTH_EXPIRE, 15 = 4WAY_HANDSHAKE_TIMEOUT (wrong
// password), 201 = NO_AP_FOUND, 205 = CONNECTION_FAIL. 0 = none captured yet.
static volatile uint8_t s_last_disc_reason = 0;

static void on_wifi_event(WiFiEvent_t event, WiFiEventInfo_t info) {
  if (event == ARDUINO_EVENT_WIFI_STA_DISCONNECTED) {
    s_last_disc_reason = info.wifi_sta_disconnected.reason;
  }
}

static void set_wifi_status(WifiStatus st) {
  if (state_lock()) {
    g_state.wifi_status = st;
    state_unlock();
  }
}

static bool try_connect_known() {
  // Already connected from a previous cycle? Reuse the link — re-scanning
  // and calling WiFi.begin() again every 2 min would otherwise force a
  // disconnect/reconnect and spam the log with the IDF's own
  // early-log noise (the bursts of high-bit bytes that locked_vprintf
  // can't catch because they're written via ets_printf).
  if (WiFi.status() == WL_CONNECTED) {
    set_wifi_status(WIFI_CONNECTED);
    return true;
  }

  storage::WifiCred creds[MAX_WIFI_CREDS];
  size_t n = storage::get_wifi_creds(creds, MAX_WIFI_CREDS);
  if (n == 0) {
    LOG_PRINTLN("[wifi] no saved network — nothing to connect to");
    return false;
  }

  // The scan is DIAGNOSTIC ONLY. It used to gate the connect: `if (found <= 0)
  // return false` and `if (!match) continue`, so WiFi.begin() was never even
  // called unless the SSID turned up in the scan. That silently stranded the
  // device against exactly the AP it is deployed with — a phone hotspot, which
  // beacons weakly, sleeps when no client is attached, and may be hidden. A scan
  // miss meant no connection attempt at all, for as long as the miss persisted,
  // with nothing in the log to say why.
  //
  // Now we always attempt the connect and let the IDF's connection manager find
  // the AP's channel itself (which also covers hidden SSIDs). show_hidden is on
  // and the dwell is 300 ms/channel so the diagnostic line is more truthful, but
  // nothing depends on the result.
  set_wifi_status(WIFI_SCANNING);
  int found = WiFi.scanNetworks(false, true, false, 300);
  for (size_t i = 0; i < n; ++i) {
    bool seen = false;
    for (int j = 0; j < found; ++j) {
      if (WiFi.SSID(j) == creds[i].ssid) {
        LOG_PRINTF("[wifi] \"%s\" seen at %d dBm (ch %d)\n",
                      creds[i].ssid.c_str(), (int)WiFi.RSSI(j), WiFi.channel(j));
        seen = true;
        break;
      }
    }
    if (!seen) {
      LOG_PRINTF("[wifi] \"%s\" not in scan (hidden, asleep or weak) — trying anyway\n",
                    creds[i].ssid.c_str());
    }
  }
  if (found > 0) WiFi.scanDelete();   // release the result buffer

  for (size_t i = 0; i < n; ++i) {
    // Start from a clean, idle STA. A prior failed attempt can leave the driver
    // mid-connect, in which case esp_wifi_set_config() rejects the new
    // credentials outright and the association can never happen.
    WiFi.disconnect(false, false);
    delay(200);

    set_wifi_status(WIFI_CONNECTING);
    LOG_PRINTF("[wifi] connecting to \"%s\" ...\n", creds[i].ssid.c_str());
    s_last_disc_reason = 0;
    WiFi.begin(creds[i].ssid.c_str(), creds[i].password.c_str());
    uint32_t start = millis();
    while (WiFi.status() != WL_CONNECTED &&
           millis() - start < WIFI_CONNECT_TIMEOUT_MS) {
      // This wait alone can run to WIFI_CONNECT_TIMEOUT_MS (15 s), half the
      // 30 s panic watchdog, so feed it here rather than relying on the single
      // reset at the top of the connectivity loop.
      esp_task_wdt_reset();
      delay(200);
    }
    if (WiFi.status() == WL_CONNECTED) {
      set_wifi_status(WIFI_CONNECTED);
      LOG_PRINTF("[wifi] connected to %s, ip=%s, rssi=%d dBm\n",
                    creds[i].ssid.c_str(),
                    WiFi.localIP().toString().c_str(), (int)WiFi.RSSI());
      return true;
    }
    // Say WHY it failed: status is the Arduino wl_status_t (6 = WL_DISCONNECTED),
    // reason is the STA disconnect code captured by on_wifi_event().
    LOG_PRINTF("[wifi] \"%s\" did not connect (status=%d, reason=%d)\n",
                  creds[i].ssid.c_str(), (int)WiFi.status(),
                  (int)s_last_disc_reason);
    WiFi.disconnect(true, true);
  }
  return false;
}

// Last NTP sync (uint64 monotonic-us) and last good epoch, for rate-limiting.
static uint64_t s_last_ntp_us = 0;          // last SUCCESSFUL sync
static bool     s_ntp_ever_ok = false;
// Last ATTEMPT, successful or not. Separate from s_last_ntp_us because that one
// is only written on success: without this, a device that can never reach an NTP
// server re-ran configTzTime() on every Wi-Fi cycle — 30 times an hour — each
// costing a 5 s busy-wait and another restart of the SNTP stack, forever.
static uint64_t s_last_ntp_attempt_us = 0;

// Last successful ingest POST timestamp (monotonic-us) for the stuck-Wi-Fi
// watchdog. Sentinel 0 means "never since boot".
static uint64_t s_last_post_us = 0;

// RTC drift monitoring. Once per RTC_DRIFT_LOG_INTERVAL_SEC we capture the
// signed difference (RTC epoch - NTP epoch) just before any writeback, and
// stash it to be reported to the server on the next ingest POST. Positive =
// the DS3231 is running ahead of true time; negative = behind. Growing
// magnitude flags a failing RTC crystal or dying backup battery.
static uint64_t s_last_drift_us  = 0;
static bool     s_drift_pending  = false;
static long     s_drift_sec      = 0;
static time_t   s_drift_at_epoch = 0;
// Wi-Fi RSSI (dBm) captured with the same hourly sample, reported alongside the
// drift so weak-signal sites can be spotted from the server. 0 = not captured.
static int      s_drift_rssi     = 0;
// RTC backup coin-cell voltage (V) captured with the same hourly sample and
// reported next to the drift and RSSI. 0 = not captured.
static float    s_drift_coin_v   = 0.0f;

static bool ntp_sync_if_due() {
  // Two independent gates.
  //
  // 1. Freshness: if the clock is known and the last SUCCESSFUL sync is recent,
  //    there is nothing to do. Saves a 5 s busy-wait on every Wi-Fi cycle.
  bool wc_known = false;
  if (state_lock()) { wc_known = g_state.wall_clock_known; state_unlock(); }
  uint64_t now_us = time_source::monotonic_us();
  if (s_ntp_ever_ok && wc_known &&
      (now_us - s_last_ntp_us) / 1000000ULL < (uint64_t)NTP_RESYNC_INTERVAL_SEC) {
    return true;
  }

  // 2. Attempt rate limit. Gate 1 alone let a device that could never reach an
  //    NTP server retry on EVERY cycle, because s_last_ntp_us is written only on
  //    success. Rate-limit the attempt itself, not just the success.
  if (s_last_ntp_attempt_us != 0 &&
      (now_us - s_last_ntp_attempt_us) / 1000000ULL < (uint64_t)NTP_RETRY_INTERVAL_SEC) {
    return s_ntp_ever_ok;   // no clock gained now, but one may already be held
  }
  s_last_ntp_attempt_us = now_us;

  configTzTime(TZ_INFO, NTP_SERVER_1, NTP_SERVER_2);
  uint32_t start = millis();
  while (millis() - start < NTP_SYNC_TIMEOUT_MS) {
    esp_task_wdt_reset();   // busy-waits up to NTP_SYNC_TIMEOUT_MS
    time_t now = time(nullptr);
    if (now > 1700000000) {
      time_source::set_wall_clock(now);
      if (state_lock()) {
        g_state.wall_clock_known = true;
        state_unlock();
      }
      // Mirror NTP-corrected time into the DS3231 so it stays accurate
      // across power loss. Skip the write if the RTC is already within
      // the small drift threshold to limit flash/I2C traffic.
      time_t rtc_now = rtc::read_epoch();

      // Sample RTC drift (signed, before any correction) at most hourly.
      if (rtc_now > 0 &&
          (s_last_drift_us == 0 ||
           (now_us - s_last_drift_us) >=
               (uint64_t)RTC_DRIFT_LOG_INTERVAL_SEC * 1000000ULL)) {
        s_drift_sec      = (long)rtc_now - (long)now;  // + = RTC ahead of NTP
        s_drift_at_epoch = now;
        s_drift_rssi     = (int)WiFi.RSSI();  // connected here (NTP just synced)
        // Latest coin-cell reading from the sampling task (ADC1/GPIO35).
        if (state_lock()) { s_drift_coin_v = g_state.coin_cell_v; state_unlock(); }
        s_drift_pending  = true;
        s_last_drift_us  = now_us;
        LOG_PRINTF("[wifi] RTC drift sample: %+ld sec, RSSI %d dBm, coin %.2f V\n",
                   s_drift_sec, s_drift_rssi, s_drift_coin_v);
      }

      long drift = (long)now - (long)rtc_now;
      if (drift < 0) drift = -drift;
      if (rtc_now == 0 || drift > RTC_WRITEBACK_DRIFT_SEC) {
        if (rtc::write_epoch(now)) {
          LOG_PRINTF("[wifi] RTC writeback ok (drift=%ld sec)\n", drift);
        }
      }
      LOG_PRINTF("[wifi] NTP sync ok, epoch=%ld\n", (long)now);
      s_last_ntp_us = now_us;
      s_ntp_ever_ok = true;
      return true;
    }
    delay(100);
  }
  LOG_PRINTLN("[wifi] NTP sync timed out");
  return false;
}

// Request body buffer. Static (.bss), NOT a String on the heap: serializeJson()'s
// Arduino String writer appends in 32-byte chunks via String::concat(), so an
// N-KB body was assembled through hundreds of reallocations, each asking for a
// slightly larger contiguous block and abandoning the previous one. That was the
// single largest source of heap fragmentation in this task, and fragmentation is
// what eventually starves the TLS handshake of the large contiguous block it
// needs — the device stays associated but can no longer POST until it reboots.
//
// Reserving the String up front does NOT help: Writer<::String>'s constructor
// assigns `str = (const char*)0`, which frees whatever was reserved.
//
// Only the connectivity task calls post_batch(), so this single owner needs no
// locking.
static char s_post_body[POST_BODY_BUF_BYTES];

static bool post_batch(uint64_t snapshot_seq, uint64_t &out_acked_seq) {
  // Collect up to SYNC_BATCH_SIZE rows with seq <= snapshot_seq.
  StaticJsonDocument<16384> doc;
  doc["device_id"] = identity::device_id();
  doc["fw_version"] = identity::fw_version();
  doc["sync_wall_time"] = time_source::iso8601_now();
  doc["current_boot_id"] = storage::boot_id();
  doc["current_boot_uptime_sec"] = (uint32_t)(time_source::monotonic_us() / 1000000ULL);

  // Attach a pending RTC drift sample, if any. Cleared after a 200 response.
  if (s_drift_pending) {
    doc["rtc_drift_sec"] = s_drift_sec;
    doc["rssi_dbm"]      = s_drift_rssi;
    doc["coin_cell_v"]   = s_drift_coin_v;
    char isobuf[32];
    struct tm lt;
    localtime_r(&s_drift_at_epoch, &lt);
    strftime(isobuf, sizeof(isobuf), "%Y-%m-%dT%H:%M:%S", &lt);
    doc["rtc_drift_at"] = isobuf;
  }

  // Heap telemetry on EVERY POST, alongside the hourly coin-cell / RTC-drift
  // sample above. `largest` is the biggest contiguous free block — what a TLS
  // handshake actually needs — and `min` the low-water mark since boot. Sent per
  // POST rather than with the hourly sample because the TREND is the point: a
  // steadily falling `free` is a leak, a falling `largest` against a flat `free`
  // is fragmentation, and only a per-POST series can tell them apart server-side.
  doc["heap_free"]    = (uint32_t)esp_get_free_heap_size();
  doc["heap_largest"] = (uint32_t)heap_caps_get_largest_free_block(MALLOC_CAP_8BIT);
  doc["heap_min"]     = (uint32_t)esp_get_minimum_free_heap_size();

  JsonArray hist = doc.createNestedArray("boot_history");
  storage::BootRecord recs[MAX_BOOT_HISTORY];
  size_t hn = storage::get_boot_history(recs, MAX_BOOT_HISTORY);
  for (size_t i = 0; i < hn; ++i) {
    JsonObject o = hist.createNestedObject();
    o["boot_id"] = recs[i].boot_id;
    o["duration_sec"] = recs[i].duration_sec;
  }

  JsonArray readings = doc.createNestedArray("readings");
  uint64_t max_in_batch = 0;
  uint32_t included = 0;
  storage::stream_rows_up_to(snapshot_seq, [&](const storage::RowFields &r) -> bool {
    if (included >= SYNC_BATCH_SIZE) return false;
    JsonObject o = readings.createNestedObject();
    o["seq"] = r.seq;
    o["boot_id"] = r.boot_id;
    o["sec"] = r.sec_since_boot;
    o["V"] = r.V;
    o["I"] = r.I;
    o["P"] = r.P;
    o["Wh"] = r.Wh;
    o["PF"] = r.PF;
    o["Hz"] = r.Hz;
    if (r.seq > max_in_batch) max_in_batch = r.seq;
    included++;
    return true;
  });

  if (included == 0) {
    // Empty buffer. Decide whether to send a config-fetch heartbeat anyway.
    uint64_t now_us = time_source::monotonic_us();
    bool heartbeat_due =
        (s_last_successful_post_us == 0) ||
        ((now_us - s_last_successful_post_us) >=
         (uint64_t)CONFIG_HEARTBEAT_SEC * 1000000ULL);
    if (!heartbeat_due) {
      out_acked_seq = snapshot_seq;
      return true;  // recently POSTed, truly nothing to send
    }
    // Fall through and POST with an empty readings array. Server can use
    // this opportunity to push log_interval_sec / server_time / etc.
    LOG_PRINTLN("[wifi] heartbeat POST (empty readings) to refresh config");
  }

  // Measure before writing: the fixed-buffer serializeJson() overload truncates
  // silently if the document does not fit, which would put malformed JSON on the
  // wire. Refuse the POST instead and say what to change — the rows stay
  // buffered and ship once the body fits.
  size_t body_len = measureJson(doc);
  if (body_len >= sizeof(s_post_body)) {
    LOG_PRINTF("[wifi] body %u B exceeds %u B buffer — lower SYNC_BATCH_SIZE or "
               "raise POST_BODY_BUF_BYTES\n",
               (unsigned)body_len, (unsigned)sizeof(s_post_body));
    return false;
  }
  serializeJson(doc, s_post_body, sizeof(s_post_body));

  // Compose URL: NVS-configured host (BLE-settable) or the compiled default,
  // then the hardcoded path. Strip any trailing slash from the host so we
  // don't double up. Hoisted above the client so the heap guard knows whether
  // this POST will need a TLS handshake at all.
  String host = storage::ingest_host();
  if (host.isEmpty()) host = INGEST_HOST_DEFAULT;
  while (host.endsWith("/")) host.remove(host.length() - 1);
  String url = host + INGEST_PATH;
  bool is_https = url.startsWith("https://");

  // Heap guard — see HEAP_MIN_* in config.h. Only TLS needs a big contiguous
  // block, so a plain-HTTP bench stub is never gated.
  if (is_https) {
    uint32_t free_heap = esp_get_free_heap_size();
    uint32_t largest   = (uint32_t)heap_caps_get_largest_free_block(MALLOC_CAP_8BIT);
    if (free_heap < HEAP_MIN_FREE_BYTES ||
        largest   < HEAP_MIN_LARGEST_BLOCK_BYTES) {
      s_low_heap_cycles++;
#if HEAP_LOW_REBOOT_CYCLES > 0
      // Skip the POST rather than drive a handshake into a starved heap. This is
      // only safe BECAUSE the heap watchdog will reboot us out of it: a C heap
      // never compacts, so deferring is not itself a recovery.
      LOG_PRINTF("[wifi] low heap — deferring POST (free=%u largest=%u, %u in a row)\n",
                    (unsigned)free_heap, (unsigned)largest,
                    (unsigned)s_low_heap_cycles);
      return false;
#else
      // Reboot escalation is off, so deferring here would be a ONE-WAY TRIP —
      // nothing would ever recover the device and it would go quiet for good.
      // These thresholds are also unvalidated against this board. So report and
      // POST anyway: a failed handshake is recoverable, a silent stop is not.
      LOG_PRINTF("[wifi] low heap (free=%u largest=%u, %u in a row) — posting anyway\n",
                    (unsigned)free_heap, (unsigned)largest,
                    (unsigned)s_low_heap_cycles);
#endif
    } else {
      s_low_heap_cycles = 0;
    }
  }

  // The client and HTTPClient live in an explicit scope so their destructors run
  // BEFORE the heap is measured below. Without that, the "free heap" reading was
  // taken while the live TLS session was still holding its buffers — on this
  // board that is ~48 KB, so the logged figure understated real free memory by
  // roughly that much and made a healthy device look nearly out of RAM.
  int    code = -1;
  String resp;
  uint32_t heap_pre = esp_get_free_heap_size();
  uint32_t heap_in  = 0;
  {
    WiFiClientSecure client;
    client.setInsecure();  // TODO: cert pinning
    // Cap the TLS handshake. Without this it sits at WiFiClientSecure's 120 s
    // default, and a stalled handshake blocks this task past the 30 s panic
    // watchdog — see the budget note on TLS_HANDSHAKE_TIMEOUT_S in config.h.
    client.setHandshakeTimeout(TLS_HANDSHAKE_TIMEOUT_S);
    HTTPClient http;
    http.setConnectTimeout(TCP_CONNECT_TIMEOUT_MS);  // bound the TCP connect
    http.setTimeout(HTTP_TIMEOUT_MS);                // bound the response read

    bool ok;
    if (is_https) {
      ok = http.begin(client, url);
    } else {
      ok = http.begin(url);  // plain HTTP for bench stub
    }
    if (!ok) {
      LOG_PRINTLN("[wifi] http.begin failed");
      return false;
    }
    http.addHeader("Content-Type", "application/json");
    http.addHeader("X-Device-Token", DEVICE_TOKEN);

    s_radio_busy = true;
    set_wifi_status(WIFI_SYNCING);
    led::signal_tx();  // flash the status LED to show data going out
    // Feed the watchdog immediately before the blocking call: connect + handshake
    // + response read is the longest unfed span in this task, ~23 s worst case
    // against a 30 s panic watchdog.
    esp_task_wdt_reset();
    code = http.POST((uint8_t *)s_post_body, body_len);
    resp = http.getString();
    http.end();
    s_radio_busy = false;
    heap_in = esp_get_free_heap_size();   // session still alive
  }
  // client + http destructed here.

  // Per-POST heap accounting:
  //   pre  = free before the TLS session is built
  //   in   = free while it is live; pre-in (reported as tls) is what TLS costs
  //   post = free after teardown
  //   dpost= post-pre
  //
  // dpost is NOT the leak. `resp` still holds the response body at this point
  // and post_batch frees it on return, so dpost overstates the loss by roughly
  // the response size — hence resp= alongside it, to be subtracted by eye.
  // Measured: dpost averaged -618 B while the same cycles moved -419 B, the
  // ~200 B difference being exactly this.
  //
  // The AUTHORITATIVE per-cycle figure is net= on the [heap] cycle line, which
  // is taken with every local destroyed at both ends. Use that for the rate;
  // use these for the breakdown of where inside the POST it goes.
  {
    uint32_t heap_post = esp_get_free_heap_size();
    LOG_PRINTF("[wifi] heap pre=%u in=%u post=%u tls=%u dpost=%+d resp=%u largest=%u min=%u\n",
                  (unsigned)heap_pre, (unsigned)heap_in, (unsigned)heap_post,
                  (unsigned)(heap_pre > heap_in ? heap_pre - heap_in : 0),
                  (int)((int32_t)heap_post - (int32_t)heap_pre),
                  (unsigned)resp.length(),
                  (unsigned)heap_caps_get_largest_free_block(MALLOC_CAP_8BIT),
                  (unsigned)esp_get_minimum_free_heap_size());
  }

  if (code != 200) {
    LOG_PRINTF("[wifi] POST failed: code=%d body=%s\n", code, resp.c_str());
    return false;
  }
  StaticJsonDocument<256> rdoc;
  if (deserializeJson(rdoc, resp)) {
    LOG_PRINTF("[wifi] bad response JSON: %s\n", resp.c_str());
    return false;
  }
  if (!(rdoc["ok"] | false)) {
    LOG_PRINTF("[wifi] server rejected: %s\n", resp.c_str());
    return false;
  }
  uint64_t acked = rdoc["acked_up_to_seq"] | 0;
  if (acked == 0) acked = max_in_batch;
  out_acked_seq = acked;
  s_last_successful_post_us = time_source::monotonic_us();

  // Optional: server-pushed logging cadence. Lets ops change the 15-min
  // default to anything between 60 s and 86400 s without reflashing. Values
  // outside that range are silently rejected by storage::set_log_interval_sec().
  uint32_t srv_log_int = rdoc["log_interval_sec"] | 0;
  if (srv_log_int > 0) {
    if (storage::set_log_interval_sec(srv_log_int)) {
      LOG_PRINTF("[wifi] log_interval_sec from server: %u\n", srv_log_int);
    } else {
      LOG_PRINTF("[wifi] log_interval_sec %u out of range, ignored\n", srv_log_int);
    }
  }

  // Optional: server-pushed maintenance config. Each field is independent and an
  // absent field leaves the cached value alone, so the server can push one knob
  // without restating the rest. storage:: validates and ignores nonsense, which
  // is why a bad push cannot strand a device with its radio off or its nightly
  // reboot mis-scheduled.
  if (rdoc.containsKey("nightly_reboot_enable") ||
      rdoc.containsKey("nightly_reboot_start_hour") ||
      rdoc.containsKey("nightly_reboot_end_hour")) {
    bool    en = rdoc["nightly_reboot_enable"]     | storage::nightly_reboot_enabled();
    uint8_t sh = rdoc["nightly_reboot_start_hour"] | storage::nightly_reboot_start_hour();
    uint8_t eh = rdoc["nightly_reboot_end_hour"]   | storage::nightly_reboot_end_hour();
    if (storage::set_nightly_reboot(en, sh, eh)) {
      LOG_PRINTF("[wifi] nightly reboot from server: %s %02u:00-%02u:00\n",
                    en ? "on" : "off", (unsigned)sh, (unsigned)eh);
    } else {
      LOG_PRINTF("[wifi] nightly reboot %02u:00-%02u:00 invalid, ignored\n",
                    (unsigned)sh, (unsigned)eh);
    }
  }
  if (rdoc.containsKey("radio_rest_interval_sec") ||
      rdoc.containsKey("radio_rest_duration_sec")) {
    uint32_t ri = rdoc["radio_rest_interval_sec"] | storage::radio_rest_interval_sec();
    uint32_t rd = rdoc["radio_rest_duration_sec"] | storage::radio_rest_duration_sec();
    if (storage::set_radio_rest(ri, rd)) {
      LOG_PRINTF("[wifi] radio rest from server: every %u s for %u s\n",
                    (unsigned)ri, (unsigned)rd);
    } else {
      LOG_PRINTF("[wifi] radio rest %u/%u s invalid, ignored\n",
                    (unsigned)ri, (unsigned)rd);
    }
  }

  // Server-time fallback: if neither the DS3231 nor NTP gave us a wall
  // clock, seed time_source from the server's response. The server
  // returns server_time as an ISO 8601 string (MilesWeb is in UTC by
  // default; APP_TIMEZONE in secrets.php can change that).
  const char *srv_time = rdoc["server_time"] | (const char *)nullptr;
  if (srv_time && !time_source::wall_clock_known()) {
    time_t srv_epoch = time_source::parse_iso8601(srv_time);
    if (srv_epoch > 0 && time_source::set_wall_clock(srv_epoch)) {
      if (state_lock()) {
        g_state.wall_clock_known = true;
        state_unlock();
      }
      // Persist into the RTC if it's present (even if it had been marked
      // unavailable due to lost-power; this clears the OSF).
      rtc::write_epoch(srv_epoch);
      LOG_PRINTF("[wifi] wall clock seeded from server_time: %s\n", srv_time);
    }
  }

  LOG_PRINTF("[wifi] POST ok, %u rows, acked_up_to=%llu\n",
                included, (unsigned long long)acked);
  s_last_post_us = time_source::monotonic_us();
  s_drift_pending = false;  // server accepted the drift sample (if any)
  return true;
}

uint32_t consecutive_low_heap_cycles() { return s_low_heap_cycles; }

bool is_associated() { return WiFi.isConnected(); }

uint32_t seconds_since_last_successful_post() {
  if (s_last_post_us == 0) return UINT32_MAX;
  return (uint32_t)((time_source::monotonic_us() - s_last_post_us) / 1000000ULL);
}

void begin() {
  WiFi.mode(WIFI_STA);
  // Drive every (re)connect from try_connect_known() rather than letting the IDF
  // auto-reconnect in the background. The background handler fires a fresh
  // esp_wifi_connect() the instant an attempt fails and races our own
  // disconnect()/begin(), which makes esp_wifi_set_config() reject the new
  // credentials ("sta is connecting, cannot set config") — so the SSID and
  // password never apply and the device never associates. With it off, each
  // connect starts from a clean, idle STA. Reachability is unaffected:
  // try_connect_known() early-returns while the link is up, and a cycle runs
  // every WIFI_SCAN_INTERVAL_SEC (plus immediately on a sync request).
  WiFi.setAutoReconnect(false);
  WiFi.persistent(false);
  WiFi.onEvent(on_wifi_event);   // capture STA disconnect reason codes
}

bool is_radio_busy() { return s_radio_busy; }

void radio_off() {
  // WiFi.disconnect(true, true) drops the association and clears the stored
  // config; WIFI_OFF is what actually stops the PHY. disconnect() alone would
  // leave the interface up and the driver free to re-associate immediately,
  // which is not a rest.
  WiFi.disconnect(true, true);
  WiFi.mode(WIFI_OFF);
  s_radio_busy = false;
  set_wifi_status(WIFI_IDLE);
}

void radio_on() {
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(false);   // see begin() — we drive reconnects ourselves
  WiFi.persistent(false);
}

void request_immediate_sync() { s_immediate_sync_pending = true; }
bool consume_immediate_sync_request() {
  if (!s_immediate_sync_pending) return false;
  s_immediate_sync_pending = false;
  return true;
}

void request_scan() { s_scan_pending = true; }
bool consume_scan_request() {
  if (!s_scan_pending) return false;
  s_scan_pending = false;
  return true;
}

String get_scan_results_json() { return s_scan_results_json; }
uint32_t scan_results_version() { return s_scan_version; }

void run_scan() {
  s_radio_busy = true;
  set_wifi_status(WIFI_SCANNING);
  // scanNetworks(async=false, show_hidden=false, passive=false, max_ms_per_chan=300)
  int n = WiFi.scanNetworks(false, false, false, 300);
  StaticJsonDocument<2048> doc;
  JsonArray arr = doc.to<JsonArray>();
  // Sort indices by RSSI (descending) and take top MAX_SCAN_RESULTS.
  int idx[64];
  int total = n;
  if (total < 0) total = 0;
  if (total > 64) total = 64;
  for (int i = 0; i < total; ++i) idx[i] = i;
  for (int i = 1; i < total; ++i) {
    int key = idx[i];
    int j = i - 1;
    while (j >= 0 && WiFi.RSSI(idx[j]) < WiFi.RSSI(key)) {
      idx[j + 1] = idx[j];
      --j;
    }
    idx[j + 1] = key;
  }
  int emit = total < MAX_SCAN_RESULTS ? total : MAX_SCAN_RESULTS;
  for (int i = 0; i < emit; ++i) {
    JsonObject o = arr.createNestedObject();
    o["s"] = WiFi.SSID(idx[i]);
    o["r"] = WiFi.RSSI(idx[i]);
    o["e"] = (WiFi.encryptionType(idx[i]) != WIFI_AUTH_OPEN) ? 1 : 0;
  }
  WiFi.scanDelete();
  String out;
  serializeJson(doc, out);
  s_scan_results_json = out;
  s_scan_version++;
  s_radio_busy = false;
  set_wifi_status(WIFI_IDLE);
  LOG_PRINTF("[wifi] scan complete: %d AP(s), emitted %d\n", n, emit);
}

#if HEAP_TRACE_CYCLE
// Free heap as of the END of the previous completed cycle. The difference
// against h_start below is the "idle" span — everything that happened while the
// connectivity task was NOT in run_cycle(): ble_service::tick() (which runs at
// 1 Hz, so ~120 times between POSTs), the sampling task, and the BLE stack
// itself. Without this column a leak outside run_cycle() would make connect,
// ntp and post all read ~0 and the trace would look innocent while the heap
// kept falling. 0 = no previous cycle yet.
static uint32_t s_heap_prev_cycle_end = 0;
#endif

bool run_cycle() {
#if HEAP_TRACE_CYCLE
  uint32_t h_start = esp_get_free_heap_size();
  int32_t  d_idle  = s_heap_prev_cycle_end
                       ? (int32_t)h_start - (int32_t)s_heap_prev_cycle_end : 0;
#endif
  if (!try_connect_known()) {
    WiFi.disconnect(true, true);
    set_wifi_status(WIFI_IDLE);
    return false;
  }
#if HEAP_TRACE_CYCLE
  uint32_t h_conn = esp_get_free_heap_size();
#endif

  ntp_sync_if_due();  // hourly resync; OK to proceed even if it fails
#if HEAP_TRACE_CYCLE
  uint32_t h_ntp = esp_get_free_heap_size();
#endif

  uint64_t snapshot = storage::snapshot_max_seq();
  // Loop until all rows up to snapshot have been acked or a POST fails.
  while (true) {
    // A backlog now drains in more, smaller POSTs (SYNC_BATCH_SIZE was cut to
    // hold down per-POST heap), so this loop can span several TLS round trips.
    // Feed the task WDT each pass or a large catch-up would trip it.
    esp_task_wdt_reset();
    uint64_t acked = 0;
    if (!post_batch(snapshot, acked)) break;
    if (acked > 0) {
      storage::truncate_up_to(acked);
      // Prune boot_history: any entry older than the oldest remaining row's
      // boot_id is no longer needed (server has it, device won't re-send).
      // If /log.csv is now empty, prune everything older than the current
      // boot so boot_history collapses to just {current_boot}.
      uint32_t min_keep = storage::boot_id();
      storage::stream_rows_up_to(UINT64_MAX, [&](const storage::RowFields &r) -> bool {
        if (r.boot_id < min_keep) min_keep = r.boot_id;
        return true;
      });
      storage::prune_boot_history_below(min_keep);

      if (state_lock()) {
        g_state.unsynced_count = storage::current_unsynced_count();
        state_unlock();
      }
    }
    // If nothing left to send for this snapshot, exit.
    if (storage::current_unsynced_count() == 0) break;
    // If we still have rows with seq <= snapshot (multi-batch case), continue.
    bool more = false;
    storage::stream_rows_up_to(snapshot, [&](const storage::RowFields &) {
      more = true;
      return false;
    });
    if (!more) break;
  }

#if HEAP_TRACE_CYCLE
  // Per-phase heap deltas. Watch which column is consistently negative across
  // many cycles: connect = try_connect_known() (scan + WiFi.begin), ntp =
  // configTzTime() + the SNTP wait, post = the TLS POST(s) and log truncation.
  // Single-cycle values are noisy (+/-400 B); the SIGN OF THE AVERAGE is what
  // identifies the leak.
  {
    uint32_t h_end = esp_get_free_heap_size();
    // idle + connect + ntp + post accounts for ALL heap movement since the last
    // completed cycle, so the four columns sum to the drop between consecutive
    // `free` values. Whichever is consistently negative owns the leak. Note idle
    // covers a longer span whenever a cycle bailed early (no AP), since the
    // early return never reaches this point.
    LOG_PRINTF("[heap] cycle: idle=%+d connect=%+d ntp=%+d post=%+d net=%+d free=%u\n",
                  (int)d_idle,
                  (int)((int32_t)h_conn - (int32_t)h_start),
                  (int)((int32_t)h_ntp  - (int32_t)h_conn),
                  (int)((int32_t)h_end  - (int32_t)h_ntp),
                  (int)((int32_t)h_end  - (int32_t)h_start) + (int)d_idle,
                  (unsigned)h_end);
    s_heap_prev_cycle_end = h_end;
  }
#endif
  storage::set_last_sync_at((uint32_t)time(nullptr));
  // Stay connected between cycles — do NOT disconnect here. The next cycle's
  // try_connect_known() early-returns on WL_CONNECTED, so we skip the
  // reconnect (no log spam, no IDF re-association noise) and the device
  // stays reachable / shows "Connected" in the app.
  set_wifi_status(WIFI_CONNECTED);
  return true;
}

}  // namespace wifi_sync
