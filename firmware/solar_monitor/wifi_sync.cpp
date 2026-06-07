#include "wifi_sync.h"
#include "config.h"
#include "shared_state.h"
#include "storage.h"
#include "identity.h"
#include "time_source.h"
#include "rtc.h"

#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>

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

static void set_wifi_status(WifiStatus st) {
  if (state_lock()) {
    g_state.wifi_status = st;
    state_unlock();
  }
}

static bool try_connect_known() {
  storage::WifiCred creds[MAX_WIFI_CREDS];
  size_t n = storage::get_wifi_creds(creds, MAX_WIFI_CREDS);
  if (n == 0) return false;

  set_wifi_status(WIFI_SCANNING);
  int found = WiFi.scanNetworks(false, false, false, 200);
  if (found <= 0) return false;

  for (size_t i = 0; i < n; ++i) {
    bool match = false;
    for (int j = 0; j < found; ++j) {
      if (WiFi.SSID(j) == creds[i].ssid) {
        match = true;
        break;
      }
    }
    if (!match) continue;

    set_wifi_status(WIFI_CONNECTING);
    WiFi.begin(creds[i].ssid.c_str(), creds[i].password.c_str());
    uint32_t start = millis();
    while (WiFi.status() != WL_CONNECTED &&
           millis() - start < WIFI_CONNECT_TIMEOUT_MS) {
      delay(200);
    }
    if (WiFi.status() == WL_CONNECTED) {
      set_wifi_status(WIFI_CONNECTED);
      Serial.printf("[wifi] connected to %s, ip=%s\n",
                    creds[i].ssid.c_str(), WiFi.localIP().toString().c_str());
      return true;
    }
    WiFi.disconnect(true, true);
  }
  return false;
}

static bool ntp_sync() {
  configTzTime(TZ_INFO, NTP_SERVER_1, NTP_SERVER_2);
  uint32_t start = millis();
  while (millis() - start < NTP_SYNC_TIMEOUT_MS) {
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
      long drift = (long)now - (long)rtc_now;
      if (drift < 0) drift = -drift;
      if (rtc_now == 0 || drift > RTC_WRITEBACK_DRIFT_SEC) {
        if (rtc::write_epoch(now)) {
          Serial.printf("[wifi] RTC writeback ok (drift=%ld sec)\n", drift);
        }
      }
      Serial.printf("[wifi] NTP sync ok, epoch=%ld\n", (long)now);
      return true;
    }
    delay(100);
  }
  Serial.println("[wifi] NTP sync timed out");
  return false;
}

static bool post_batch(uint64_t snapshot_seq, uint64_t &out_acked_seq) {
  // Collect up to SYNC_BATCH_SIZE rows with seq <= snapshot_seq.
  StaticJsonDocument<16384> doc;
  doc["device_id"] = identity::device_id();
  doc["fw_version"] = identity::fw_version();
  doc["sync_wall_time"] = time_source::iso8601_now();
  doc["current_boot_id"] = storage::boot_id();
  doc["current_boot_uptime_sec"] = (uint32_t)(time_source::monotonic_us() / 1000000ULL);

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
    if (r.seq > max_in_batch) max_in_batch = r.seq;
    included++;
    return true;
  });

  if (included == 0) {
    out_acked_seq = snapshot_seq;
    return true;  // nothing to send
  }

  String body;
  serializeJson(doc, body);

  WiFiClientSecure client;
  client.setInsecure();  // TODO: cert pinning
  HTTPClient http;
  http.setTimeout(HTTP_TIMEOUT_MS);

  String url = String(INGEST_URL);
  bool ok;
  if (url.startsWith("https://")) {
    ok = http.begin(client, url);
  } else {
    ok = http.begin(url);  // plain HTTP for bench stub
  }
  if (!ok) {
    Serial.println("[wifi] http.begin failed");
    return false;
  }
  http.addHeader("Content-Type", "application/json");
  http.addHeader("X-Device-Token", DEVICE_TOKEN);

  s_radio_busy = true;
  set_wifi_status(WIFI_SYNCING);
  int code = http.POST((uint8_t *)body.c_str(), body.length());
  String resp = http.getString();
  http.end();
  s_radio_busy = false;

  if (code != 200) {
    Serial.printf("[wifi] POST failed: code=%d body=%s\n", code, resp.c_str());
    return false;
  }
  StaticJsonDocument<256> rdoc;
  if (deserializeJson(rdoc, resp)) {
    Serial.printf("[wifi] bad response JSON: %s\n", resp.c_str());
    return false;
  }
  if (!(rdoc["ok"] | false)) {
    Serial.printf("[wifi] server rejected: %s\n", resp.c_str());
    return false;
  }
  uint64_t acked = rdoc["acked_up_to_seq"] | 0;
  if (acked == 0) acked = max_in_batch;
  out_acked_seq = acked;
  Serial.printf("[wifi] POST ok, %u rows, acked_up_to=%llu\n",
                included, (unsigned long long)acked);
  return true;
}

void begin() {
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(false);
}

bool is_radio_busy() { return s_radio_busy; }

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
  Serial.printf("[wifi] scan complete: %d AP(s), emitted %d\n", n, emit);
}

bool run_cycle() {
  if (!try_connect_known()) {
    WiFi.disconnect(true, true);
    set_wifi_status(WIFI_IDLE);
    return false;
  }

  ntp_sync();  // OK to proceed even if NTP failed; rows still upload

  uint64_t snapshot = storage::snapshot_max_seq();
  // Loop until all rows up to snapshot have been acked or a POST fails.
  while (true) {
    uint64_t acked = 0;
    if (!post_batch(snapshot, acked)) break;
    if (acked > 0) {
      storage::truncate_up_to(acked);
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

  storage::set_last_sync_at((uint32_t)time(nullptr));
  WiFi.disconnect(true, true);
  set_wifi_status(WIFI_IDLE);
  return true;
}

}  // namespace wifi_sync
