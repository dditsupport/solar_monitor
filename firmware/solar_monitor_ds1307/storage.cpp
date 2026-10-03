#include "storage.h"
#include "config.h"

#include <LittleFS.h>
#include <Preferences.h>
#include <ArduinoJson.h>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>
#include "log_serial.h"

namespace storage {

static Preferences s_cfg;
static Preferences s_state;
static SemaphoreHandle_t s_log_mutex = nullptr;

static uint32_t s_boot_id = 0;
static uint64_t s_last_seq = 0;
static uint64_t s_seq_hwm = 0;
static bool s_seq_fresh = false;
static uint32_t s_unsynced_count = 0;
static bool s_buffer_full = false;
static uint32_t s_partition_total = 0;

// ---- Helpers ----------------------------------------------------------------

static bool lock_log(TickType_t ticks = pdMS_TO_TICKS(2000)) {
  return xSemaphoreTake(s_log_mutex, ticks) == pdTRUE;
}
static void unlock_log() { xSemaphoreGive(s_log_mutex); }

static bool parse_row(const String &line, RowFields &out) {
  // Exactly ten fields: "seq,boot_id,sec,V,I,P,Wh,PF,Hz,epoch".
  const char *s = line.c_str();
  char *end;

  out.seq = strtoull(s, &end, 10);
  if (end == s || *end != ',') return false;
  s = end + 1;

  out.boot_id = strtoul(s, &end, 10);
  if (end == s || *end != ',') return false;
  s = end + 1;

  out.sec_since_boot = strtoul(s, &end, 10);
  if (end == s || *end != ',') return false;
  s = end + 1;

  float *fields[] = {&out.V, &out.I, &out.P, &out.Wh, &out.PF, &out.Hz};
  for (float *f : fields) {
    *f = strtof(s, &end);
    if (end == s || *end != ',') return false;
    s = end + 1;
  }

  out.epoch = strtoul(s, &end, 10);
  if (end == s) return false;
  return *end == '\0' || *end == '\r' || *end == '\n';
}

// Strip a trailing partial line from /log.csv if it lacks newline or fails to parse.
static void repair_tail() {
  File f = LittleFS.open(LOG_PATH, "r");
  if (!f) return;
  size_t size = f.size();
  if (size == 0) { f.close(); return; }

  // Read up to last 256 bytes.
  size_t scan = size > 256 ? 256 : size;
  f.seek(size - scan, SeekSet);
  String tail;
  while (f.available()) tail += (char)f.read();
  f.close();

  // Find last full line.
  int last_nl = tail.lastIndexOf('\n');
  if (last_nl < 0) {
    // No newline at all in tail; if file <= 256B, the whole thing is garbage.
    if (size <= 256) {
      LittleFS.remove(LOG_PATH);
    } else {
      // Otherwise, leave it; tail was inside a long line. Defensive: truncate to size - scan.
      // (Should not happen in practice — rows are ~50 bytes.)
    }
    return;
  }
  // Validate the last complete line.
  String last_line = tail.substring(tail.lastIndexOf('\n', last_nl - 1) + 1, last_nl);
  RowFields rf;
  if (!parse_row(last_line, rf)) {
    // Truncate to before that bad line.
    size_t cut_at = size - (tail.length() - tail.lastIndexOf('\n', last_nl - 1) - 1);
    File w = LittleFS.open(LOG_PATH, "r+");
    if (w) {
      // ESP32 LittleFS lacks fs::truncate; rewrite without the bad line.
      w.close();
      File r = LittleFS.open(LOG_PATH, "r");
      File t = LittleFS.open(LOG_TMP_PATH, "w");
      if (r && t) {
        size_t copied = 0;
        while (r.available() && copied < cut_at) {
          int b = r.read();
          if (b < 0) break;
          t.write((uint8_t)b);
          copied++;
        }
        t.close();
        r.close();
        LittleFS.remove(LOG_PATH);
        LittleFS.rename(LOG_TMP_PATH, LOG_PATH);
      }
    }
  }
  // Also: ensure file ends with newline. If after repair last char isn't '\n', append one.
  File chk = LittleFS.open(LOG_PATH, "r");
  if (chk) {
    size_t sz = chk.size();
    if (sz > 0) {
      chk.seek(sz - 1, SeekSet);
      int last = chk.read();
      chk.close();
      if (last != '\n') {
        File ap = LittleFS.open(LOG_PATH, "a");
        if (ap) { ap.write((uint8_t)'\n'); ap.close(); }
      }
    } else chk.close();
  }
}

static uint32_t count_rows() {
  File f = LittleFS.open(LOG_PATH, "r");
  if (!f) return 0;
  uint32_t n = 0;
  while (f.available()) {
    int b = f.read();
    if (b == '\n') n++;
  }
  f.close();
  return n;
}

static void scan_prev_boot_duration(uint32_t prev_boot_id, uint32_t &max_sec) {
  max_sec = 0;
  File f = LittleFS.open(LOG_PATH, "r");
  if (!f) return;
  String line;
  while (f.available()) {
    char c = (char)f.read();
    if (c == '\n') {
      RowFields rf;
      if (parse_row(line, rf) && rf.boot_id == prev_boot_id) {
        if (rf.sec_since_boot > max_sec) max_sec = rf.sec_since_boot;
      }
      line = "";
    } else if (c != '\r') {
      line += c;
    }
  }
  f.close();
}

// ---- Public API -------------------------------------------------------------

bool begin() {
  s_log_mutex = xSemaphoreCreateMutex();
  if (!s_log_mutex) return false;

  if (!LittleFS.begin(true)) {
    LOG_PRINTLN("[storage] LittleFS mount failed");
    return false;
  }
  s_partition_total = LittleFS.totalBytes();

  // Recovery step 1: a leftover /log.tmp. truncate_up_to() and repair_tail()
  // both write the new log to /log.tmp, close it, remove /log.csv and then
  // rename. With /log.csv still present the crash came before the remove: the
  // log is intact and the tmp is a partial copy, so drop the tmp. With NO
  // /log.csv the crash came between remove and rename: the tmp is the complete
  // new log and the only copy left — deleting it lost every unsynced row.
  if (LittleFS.exists(LOG_TMP_PATH)) {
    if (LittleFS.exists(LOG_PATH)) {
      LOG_PRINTLN("[storage] cleanup leftover /log.tmp");
      LittleFS.remove(LOG_TMP_PATH);
    } else {
      LOG_PRINTLN("[storage] restoring /log.csv from /log.tmp (interrupted rewrite)");
      LittleFS.rename(LOG_TMP_PATH, LOG_PATH);
    }
  }

  // Recovery step 2: repair tail of /log.csv.
  if (LittleFS.exists(LOG_PATH)) {
    repair_tail();
  }

  // Open NVS namespaces.
  s_cfg.begin("cfg", false);
  s_state.begin("state", false);

  // Pull previous boot identity & seq HWM.
  uint32_t prev_boot_id = s_state.getUInt("boot_id", 0);
  s_seq_hwm = s_state.getULong64("seq_hwm", 0);

  // Restore last_seq from HWM (never reuse seqs).
  s_last_seq = s_seq_hwm;

  // No saved counter at all means this NVS has never logged a row: a new board,
  // a full flash erase or a factory reset. Its seqs restart at 1 and would
  // collide with the readings the server already holds for this device_id, so
  // flag it until the server tells us where to start (apply_seq_base). The
  // flag lives in NVS too: by the next reboot seq_hwm may well exist.
  if (!s_state.isKey("seq_hwm")) s_state.putBool("seq_fresh", true);
  s_seq_fresh = s_state.getBool("seq_fresh", false);

  // If previous boot exists AND it ran long enough to log at least one row,
  // append a boot record. Zero-duration boots (dev reflashes, brief power
  // glitches) carry no readings and would just clutter boot_history with
  // entries the server doesn't need.
  if (prev_boot_id > 0) {
    uint32_t max_sec = 0;
    scan_prev_boot_duration(prev_boot_id, max_sec);
    if (max_sec > 0) {
      BootRecord rec = {prev_boot_id, max_sec};
      push_boot_record(rec);
    }
  }

  // Bump and persist new boot_id.
  s_boot_id = prev_boot_id + 1;
  s_state.putUInt("boot_id", s_boot_id);

  s_unsynced_count = count_rows();
  s_buffer_full = (LittleFS.totalBytes() - LittleFS.usedBytes()) <
                  max((uint32_t)BUFFER_FREE_MIN_BYTES,
                      (uint32_t)(s_partition_total * BUFFER_FREE_MIN_PCT / 100));

  LOG_PRINTF("[storage] boot_id=%u last_seq=%llu unsynced=%u free=%u\n",
                s_boot_id, (unsigned long long)s_last_seq, s_unsynced_count,
                (unsigned)(LittleFS.totalBytes() - LittleFS.usedBytes()));
  return true;
}

uint32_t boot_id() { return s_boot_id; }
uint64_t last_seq() { return s_last_seq; }
uint64_t seq_hwm() { return s_seq_hwm; }
bool seq_fresh() { return s_seq_fresh; }

// Record `seq` as used. Caller holds the log lock.
static void advance_last_seq(uint64_t seq) {
  s_last_seq = seq;
  // Advance HWM only when we cross it.
  if (seq >= s_seq_hwm) {
    s_seq_hwm = seq + SEQ_HWM_STRIDE;
    s_state.putULong64("seq_hwm", s_seq_hwm);
  }
}

// ---- Boot history (circular buffer in NVS) ----------------------------------

void push_boot_record(const BootRecord &rec) {
  // Stored as a JSON array string for simplicity (max 32 entries x ~30 bytes ~ <1 KB).
  String json = s_state.getString("boots", "[]");
  StaticJsonDocument<1500> doc;
  if (deserializeJson(doc, json)) {
    doc.clear();
    doc.to<JsonArray>();
  }
  JsonArray arr = doc.as<JsonArray>();
  JsonObject obj = arr.createNestedObject();
  obj["b"] = rec.boot_id;
  obj["d"] = rec.duration_sec;
  while (arr.size() > MAX_BOOT_HISTORY) arr.remove(0);
  String out;
  serializeJson(doc, out);
  s_state.putString("boots", out);
}

void prune_boot_history_below(uint32_t min_keep_boot_id) {
  String json = s_state.getString("boots", "[]");
  StaticJsonDocument<1500> doc;
  if (deserializeJson(doc, json)) return;
  JsonArray arr = doc.as<JsonArray>();
  bool changed = false;
  for (int i = (int)arr.size() - 1; i >= 0; --i) {
    uint32_t bid = arr[i]["b"] | 0;
    if (bid < min_keep_boot_id) {
      arr.remove(i);
      changed = true;
    }
  }
  if (changed) {
    String out;
    serializeJson(doc, out);
    s_state.putString("boots", out);
  }
}

void clear_boot_history() {
  s_state.putString("boots", "[]");
}

size_t get_boot_history(BootRecord *out, size_t max_out) {
  String json = s_state.getString("boots", "[]");
  StaticJsonDocument<1500> doc;
  if (deserializeJson(doc, json)) return 0;
  JsonArray arr = doc.as<JsonArray>();
  size_t n = 0;
  for (JsonObject o : arr) {
    if (n >= max_out) break;
    out[n].boot_id = o["b"] | 0;
    out[n].duration_sec = o["d"] | 0;
    n++;
  }
  return n;
}

// ---- Wi-Fi creds ------------------------------------------------------------

size_t get_wifi_creds(WifiCred *out, size_t max_out) {
  String json = s_cfg.getString("wifi", "[]");
  StaticJsonDocument<1024> doc;
  if (deserializeJson(doc, json)) return 0;
  JsonArray arr = doc.as<JsonArray>();
  size_t n = 0;
  for (JsonObject o : arr) {
    if (n >= max_out) break;
    out[n].ssid = (const char *)(o["s"] | "");
    out[n].password = (const char *)(o["p"] | "");
    if (out[n].ssid.length()) n++;
  }
  return n;
}

bool add_wifi_cred(const String &ssid, const String &password) {
  if (ssid.isEmpty()) return false;
  // Single-credential model: replace any prior entry with this one.
  StaticJsonDocument<512> doc;
  JsonArray arr = doc.to<JsonArray>();
  JsonObject o = arr.createNestedObject();
  o["s"] = ssid;
  o["p"] = password;
  String out;
  serializeJson(doc, out);
  s_cfg.putString("wifi", out);
  return true;
}

void clear_wifi_creds() {
  s_cfg.putString("wifi", "[]");
}

void set_last_sync_at(uint32_t epoch) {
  s_state.putUInt("sync_at", epoch);
}
uint32_t last_sync_at() {
  return s_state.getUInt("sync_at", 0);
}

uint32_t last_nightly_reboot_day() {
  return s_state.getUInt("nrb_day", 0);
}
void set_last_nightly_reboot_day(uint32_t day) {
  s_state.putUInt("nrb_day", day);
}

// ---- Server-pushed maintenance config --------------------------------------

bool nightly_reboot_enabled() {
  return s_cfg.getBool("nrb_en", NIGHTLY_REBOOT_ENABLE_DEFAULT != 0);
}
uint8_t nightly_reboot_start_hour() {
  return s_cfg.getUChar("nrb_sh", NIGHTLY_REBOOT_START_HOUR_DEFAULT);
}
uint8_t nightly_reboot_end_hour() {
  return s_cfg.getUChar("nrb_eh", NIGHTLY_REBOOT_END_HOUR_DEFAULT);
}
bool set_nightly_reboot(bool enabled, uint8_t start_hour, uint8_t end_hour) {
  // The window must be a non-empty span inside one local day. Rejecting rather
  // than clamping means a malformed push leaves the previous schedule intact.
  if (start_hour > 23 || end_hour > 24 || end_hour <= start_hour) return false;
  if (s_cfg.getBool ("nrb_en", NIGHTLY_REBOOT_ENABLE_DEFAULT != 0) != enabled)
    s_cfg.putBool ("nrb_en", enabled);
  if (s_cfg.getUChar("nrb_sh", NIGHTLY_REBOOT_START_HOUR_DEFAULT) != start_hour)
    s_cfg.putUChar("nrb_sh", start_hour);
  if (s_cfg.getUChar("nrb_eh", NIGHTLY_REBOOT_END_HOUR_DEFAULT) != end_hour)
    s_cfg.putUChar("nrb_eh", end_hour);
  return true;
}

uint32_t radio_rest_interval_sec() {
  return s_cfg.getUInt("rr_int", RADIO_REST_INTERVAL_SEC_DEFAULT);
}
uint32_t radio_rest_duration_sec() {
  return s_cfg.getUInt("rr_dur", RADIO_REST_DURATION_SEC_DEFAULT);
}
bool set_radio_rest(uint32_t interval_sec, uint32_t duration_sec) {
  // 0 = periodic rest disabled; otherwise at least 10 min, so a mistyped value
  // cannot put the radio off-air on a loop. Duration stays well under the
  // stuck-Wi-Fi and stuck-BLE watchdogs.
  if (interval_sec != 0 && (interval_sec < 600 || interval_sec > 86400)) return false;
  if (duration_sec < 5 || duration_sec > 120) return false;
  if (s_cfg.getUInt("rr_int", RADIO_REST_INTERVAL_SEC_DEFAULT) != interval_sec)
    s_cfg.putUInt("rr_int", interval_sec);
  if (s_cfg.getUInt("rr_dur", RADIO_REST_DURATION_SEC_DEFAULT) != duration_sec)
    s_cfg.putUInt("rr_dur", duration_sec);
  return true;
}

String ingest_host() {
  return s_cfg.getString("host", "");
}
bool set_ingest_host(const String &host) {
  if (host.length() > 128) return false;  // sanity cap
  s_cfg.putString("host", host);
  return true;
}

uint32_t log_interval_sec() {
  return s_cfg.getUInt("log_int", LOG_INTERVAL_SEC_DEFAULT);
}
bool set_log_interval_sec(uint32_t sec) {
  if (sec < LOG_INTERVAL_SEC_MIN || sec > LOG_INTERVAL_SEC_MAX) return false;
  // Avoid an NVS write if the value didn't change — limits flash wear on
  // chatty servers that include the field in every response.
  if (s_cfg.getUInt("log_int", 0) == sec) return true;
  s_cfg.putUInt("log_int", sec);
  return true;
}

float today_anchor_wh() {
  return s_state.getFloat("tdy_wh", -1.0f);
}
uint32_t today_anchor_day() {
  return s_state.getUInt("tdy_day", 0);
}
bool today_anchor_clean() {
  return s_state.getBool("tdy_cln", false);
}
void set_today_anchor(float wh, uint32_t day, bool clean) {
  s_state.putFloat("tdy_wh", wh);
  s_state.putUInt("tdy_day", day);
  s_state.putBool("tdy_cln", clean);
}

// ---- Log file ---------------------------------------------------------------

uint32_t free_bytes() {
  return LittleFS.totalBytes() - LittleFS.usedBytes();
}

bool is_buffer_full() {
  uint32_t free_b = free_bytes();
  uint32_t threshold = max((uint32_t)BUFFER_FREE_MIN_BYTES,
                           (uint32_t)(s_partition_total * BUFFER_FREE_MIN_PCT / 100));
  s_buffer_full = (free_b < threshold);
  return s_buffer_full;
}

int format_row(const RowFields &row, char *buf, size_t len) {
  int n = snprintf(buf, len, "%llu,%u,%u,%.2f,%.3f,%.2f,%.2f,%.3f,%.2f,%lu",
                   (unsigned long long)row.seq, row.boot_id, row.sec_since_boot,
                   row.V, row.I, row.P, row.Wh, row.PF, row.Hz,
                   (unsigned long)row.epoch);
  return (n > 0 && n < (int)len) ? n : -1;
}

bool append_next_row(RowFields &row) {
  if (is_buffer_full()) return false;
  if (!lock_log()) return false;
  bool ok = false;
  row.seq = s_last_seq + 1;
  File f = LittleFS.open(LOG_PATH, "a");
  if (f) {
    char line[128];
    int n = format_row(row, line, sizeof(line) - 1);
    if (n > 0) {
      line[n++] = '\n';
      size_t w = f.write((const uint8_t *)line, n);
      f.flush();
      f.close();
      if ((int)w == n) {
        s_unsynced_count++;
        advance_last_seq(row.seq);
        ok = true;
      }
    } else {
      f.close();
    }
  }
  unlock_log();
  return ok;
}

bool apply_seq_base(uint64_t base) {
  if (!lock_log()) return false;
  bool ok = true;
  if (base > 0 && LittleFS.exists(LOG_PATH)) {
    // Same tmp + rename as truncate_up_to(), so boot recovery covers a crash.
    ok = false;
    File r = LittleFS.open(LOG_PATH, "r");
    File w = LittleFS.open(LOG_TMP_PATH, "w");
    if (r && w) {
      String line;
      char out[128];
      while (r.available()) {
        char c = (char)r.read();
        if (c == '\n') {
          RowFields rf;
          if (parse_row(line, rf)) {
            rf.seq += base;
            int n = format_row(rf, out, sizeof(out) - 1);
            if (n > 0) {
              out[n++] = '\n';
              w.write((const uint8_t *)out, n);
            }
          }
          line = "";
        } else if (c != '\r') {
          line += c;
        }
      }
      w.flush();
      w.close();
      r.close();
      LittleFS.remove(LOG_PATH);
      LittleFS.rename(LOG_TMP_PATH, LOG_PATH);
      ok = true;
    } else {
      if (r) r.close();
      if (w) w.close();
    }
  }
  if (ok) {
    s_last_seq += base;
    // Always persist the counter, even for base 0: with no seq_hwm key, the
    // next boot would take this device for fresh again.
    s_seq_hwm = s_last_seq + SEQ_HWM_STRIDE;
    s_state.putULong64("seq_hwm", s_seq_hwm);
    s_seq_fresh = false;
    s_state.putBool("seq_fresh", false);
    LOG_PRINTF("[storage] seq counter now starts above %llu (shifted by %llu)\n",
               (unsigned long long)s_last_seq, (unsigned long long)base);
  }
  unlock_log();
  return ok;
}

uint32_t row_count() {
  return s_unsynced_count;
}

uint32_t current_unsynced_count() {
  return s_unsynced_count;
}

uint64_t snapshot_max_seq() {
  return s_last_seq;
}

uint32_t stream_rows_up_to(uint64_t max_seq, std::function<bool(const RowFields &)> cb) {
  uint32_t emitted = 0;
  File f = LittleFS.open(LOG_PATH, "r");
  if (!f) return 0;
  String line;
  while (f.available()) {
    char c = (char)f.read();
    if (c == '\n') {
      RowFields rf;
      if (parse_row(line, rf) && rf.seq <= max_seq) {
        if (!cb(rf)) {
          f.close();
          return emitted;
        }
        emitted++;
      }
      line = "";
    } else if (c != '\r') {
      line += c;
    }
  }
  f.close();
  return emitted;
}

bool truncate_up_to(uint64_t acked_seq) {
  if (!lock_log()) return false;
  // No log at all means nothing to truncate: success, not an error. Without
  // this the open below created an empty /log.tmp and reported failure, and a
  // caller that retries failures would do that every second.
  if (!LittleFS.exists(LOG_PATH)) {
    s_unsynced_count = 0;
    unlock_log();
    return true;
  }
  bool ok = false;
  File r = LittleFS.open(LOG_PATH, "r");
  File w = LittleFS.open(LOG_TMP_PATH, "w");
  if (r && w) {
    String line;
    uint32_t kept = 0;
    while (r.available()) {
      char c = (char)r.read();
      if (c == '\n') {
        RowFields rf;
        if (parse_row(line, rf) && rf.seq > acked_seq) {
          line += '\n';
          w.write((const uint8_t *)line.c_str(), line.length());
          kept++;
        }
        line = "";
      } else if (c != '\r') {
        line += c;
      }
    }
    w.flush();
    w.close();
    r.close();
    LittleFS.remove(LOG_PATH);
    LittleFS.rename(LOG_TMP_PATH, LOG_PATH);
    s_unsynced_count = kept;
    ok = true;
  } else {
    if (r) r.close();
    if (w) w.close();
  }
  unlock_log();
  return ok;
}

// ---- Serial helpers ---------------------------------------------------------

void dump_log_to_serial() {
  File f = LittleFS.open(LOG_PATH, "r");
  if (!f) {
    LOG_PRINTLN("[storage] no log file");
    return;
  }
  LOG_PRINTLN("---BEGIN LOG---");
  while (f.available()) LOG_WRITE(f.read());
  LOG_PRINTLN("---END LOG---");
  f.close();
}

void dump_boots_to_serial() {
  BootRecord recs[MAX_BOOT_HISTORY];
  size_t n = get_boot_history(recs, MAX_BOOT_HISTORY);
  LOG_PRINTF("[storage] current boot_id=%u, history has %u records\n",
                s_boot_id, (unsigned)n);
  for (size_t i = 0; i < n; ++i) {
    LOG_PRINTF("  boot %u: %u sec\n", recs[i].boot_id, recs[i].duration_sec);
  }
}

void clear_log() {
  if (!lock_log()) return;
  LittleFS.remove(LOG_PATH);
  s_unsynced_count = 0;
  unlock_log();
  LOG_PRINTLN("[storage] log cleared");
}

void erase_all_nvs() {
  s_cfg.clear();
  s_state.clear();
  LOG_PRINTLN("[storage] NVS erased (cfg + state namespaces)");
}

static volatile bool s_factory_reset_requested = false;
void request_factory_reset() { s_factory_reset_requested = true; }
bool consume_factory_reset_request() {
  if (!s_factory_reset_requested) return false;
  s_factory_reset_requested = false;
  return true;
}

}  // namespace storage
