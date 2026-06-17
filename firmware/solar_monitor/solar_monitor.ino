// Solar AC-Side Power Monitor — ESP32 firmware (v1.0.0)
//
// Sketch entry point. Sets up the brownout detector, mounts storage, then
// spawns two pinned FreeRTOS tasks: SamplingTask on core 0 (PZEM + OLED) and
// ConnectivityTask on core 1 (BLE always, Wi-Fi periodic). All shared mutable
// state lives in g_state, guarded by g_state_mutex.
//
// See docs/PINOUT.md for wiring and docs/PROVISIONING.md for setup steps.

#include "config.h"
#include "shared_state.h"
#include "identity.h"
#include "time_source.h"
#include "pzem.h"
#include "display.h"
#include "storage.h"
#include "health.h"
#include "wifi_sync.h"
#include "ble_service.h"
#include "rtc.h"

#include <esp_task_wdt.h>

// ---- Global shared state ----------------------------------------------------
SharedState g_state;
SemaphoreHandle_t g_state_mutex;

// ---- Forward declarations ---------------------------------------------------
static void sampling_task(void *);
static void connectivity_task(void *);
static void handle_serial_command(const String &cmd);

extern "C" void health_mark_clean_uptime();

// ---- Setup ------------------------------------------------------------------
void setup() {
  // The brownout detector is enabled by default in ESP-IDF 5.x at the chip
  // default threshold (~2.4 V). Override via menuconfig / sdkconfig if needed.

  Serial.begin(115200);
  delay(50);
  Serial.println();
  Serial.println("=== Solar Monitor boot ===");

  g_state_mutex = xSemaphoreCreateMutex();
  if (!g_state_mutex) {
    Serial.println("[fatal] state mutex alloc failed");
    while (true) delay(1000);
  }

  display::begin();
  display::splash("Solar Monitor", "booting...");

  time_source::begin();
  health::begin();
  if (!storage::begin()) {
    display::splash("STORAGE FAIL", "check flash");
    while (true) delay(1000);
  }
  pzem::begin();

  // Seed wall clock from the DS3231 if it's healthy. This lets the OLED show
  // "Today:" immediately at boot instead of "Session:" until NTP or BLE.
  if (rtc::begin()) {
    time_t epoch = rtc::read_epoch();
    if (epoch > 0 && time_source::set_wall_clock(epoch)) {
      Serial.printf("[boot] wall clock seeded from DS3231: %ld\n", (long)epoch);
    }
  }

  // Seed shared state.
  if (state_lock(pdMS_TO_TICKS(1000))) {
    g_state.boot_id = storage::boot_id();
    g_state.last_seq = storage::last_seq();
    g_state.unsynced_count = storage::current_unsynced_count();
    g_state.buffer_full = storage::is_buffer_full();
    g_state.wifi_status = WIFI_IDLE;
    g_state.ble_status = BLE_OFF;
    g_state.wall_clock_known = time_source::wall_clock_known();
    state_unlock();
  }

  Serial.printf("Device ID: %s  fw=%s  boot=%u  unsynced=%u\n",
                identity::device_id().c_str(), identity::fw_version(),
                storage::boot_id(), storage::current_unsynced_count());

  // Hardcoded Wi-Fi fallback for bench testing. If WIFI_SSID is non-empty
  // and the NVS slot has no credentials yet, copy them in. Once provisioned
  // via BLE the saved value wins and this block becomes a no-op.
  if (sizeof(WIFI_SSID) > 1) {
    storage::WifiCred existing[MAX_WIFI_CREDS];
    if (storage::get_wifi_creds(existing, MAX_WIFI_CREDS) == 0) {
      if (storage::add_wifi_cred(WIFI_SSID, WIFI_PASSWORD)) {
        Serial.printf("[boot] seeded Wi-Fi from config.h: %s\n", WIFI_SSID);
      }
    }
  }

  ble_service::begin();
  wifi_sync::begin();

  xTaskCreatePinnedToCore(sampling_task, "sampling",
                          SAMPLING_TASK_STACK, nullptr, SAMPLING_TASK_PRIO,
                          nullptr, 0);
  xTaskCreatePinnedToCore(connectivity_task, "conn",
                          CONN_TASK_STACK, nullptr, CONN_TASK_PRIO,
                          nullptr, 1);
}

// ---- Main loop (idle / serial console / WDT feeder) ------------------------
void loop() {
  static String line;
  while (Serial.available()) {
    char c = (char)Serial.read();
    if (c == '\n' || c == '\r') {
      if (line.length()) {
        handle_serial_command(line);
        line = "";
      }
    } else if (line.length() < 64) {
      line += c;
    }
  }
  delay(50);
}

static void handle_serial_command(const String &cmd) {
  String c = cmd;
  c.trim();
  c.toUpperCase();
  if (c == "DUMP") {
    storage::dump_log_to_serial();
  } else if (c == "BOOTS") {
    storage::dump_boots_to_serial();
  } else if (c == "CLEAR") {
    storage::clear_log();
  } else if (c == "WIFI") {
    storage::WifiCred creds[MAX_WIFI_CREDS];
    size_t n = storage::get_wifi_creds(creds, MAX_WIFI_CREDS);
    Serial.printf("%u saved networks\n", (unsigned)n);
    for (size_t i = 0; i < n; ++i) {
      Serial.printf("  %u: %s\n", (unsigned)i, creds[i].ssid.c_str());
    }
  } else if (c == "INFO") {
    Serial.printf("id=%s fw=%s boot=%u last_seq=%llu unsynced=%u free=%u\n",
                  identity::device_id().c_str(), identity::fw_version(),
                  storage::boot_id(), (unsigned long long)storage::last_seq(),
                  storage::current_unsynced_count(),
                  (unsigned)storage::free_bytes());
  } else if (c == "SYNC") {
    Serial.println("[cmd] requesting immediate Wi-Fi sync");
    wifi_sync::request_immediate_sync();
  } else if (c == "LOG") {
    // Append a synthetic row using the latest sample so the next sync
    // has something to send. Useful for end-to-end testing without
    // waiting LOG_INTERVAL_SEC.
    SharedState snap;
    if (state_snapshot(snap)) {
      uint64_t seq = storage::last_seq() + 1;
      storage::RowFields rf;
      rf.seq = seq;
      rf.boot_id = storage::boot_id();
      rf.sec_since_boot = (uint32_t)(time_source::monotonic_us() / 1000000ULL);
      rf.V = snap.latest.voltage;
      rf.I = snap.latest.current;
      rf.P = snap.latest.power;
      rf.Wh = snap.latest.energy_wh;
      rf.PF = snap.latest.pf;
      if (storage::append_row(rf)) {
        storage::set_last_seq(seq);
        wifi_sync::request_immediate_sync();
        Serial.printf("[cmd] synthetic row seq=%llu logged, sync requested\n",
                      (unsigned long long)seq);
      } else {
        Serial.println("[cmd] append_row failed (buffer full?)");
      }
    }
  } else {
    Serial.printf("unknown command: %s (try DUMP, BOOTS, CLEAR, WIFI, INFO, SYNC, LOG)\n",
                  c.c_str());
  }
}

// ---- SamplingTask (core 0) --------------------------------------------------
static void sampling_task(void *) {
  esp_task_wdt_add(nullptr);

  uint64_t last_us = time_source::monotonic_us();
  uint64_t last_log_us = last_us;
  uint32_t last_day = 0;
  bool wall_clock_seen = false;
  bool clean_uptime_marked = false;
  uint32_t boot_start_us_sec = (uint32_t)(last_us / 1000000ULL);

  for (;;) {
    esp_task_wdt_reset();

    PzemSample sample{};
    bool ok = pzem::read(sample);
    PzemStatus st = pzem::classify(ok, sample);

    uint64_t now_us = time_source::monotonic_us();
    int64_t dt_us = (int64_t)(now_us - last_us);
    if (dt_us < 0) dt_us = 0;
    last_us = now_us;
    float dt_sec = dt_us / 1.0e6f;

    // Integrate energy from instantaneous power (W * h -> kWh).
    float p = ok ? sample.power : 0.0f;
    float d_kwh = (p * dt_sec) / 3600.0f / 1000.0f;

    // Detect midnight rollover.
    bool rolled_over = false;
    if (time_source::wall_clock_known()) {
      uint32_t day = time_source::local_day_number();
      if (!wall_clock_seen) {
        wall_clock_seen = true;
        last_day = day;
      } else if (day != last_day) {
        rolled_over = true;
        last_day = day;
      }
    }

    if (state_lock()) {
      if (ok) g_state.latest = sample;
      g_state.pzem_status = st;
      g_state.session_kwh += d_kwh;
      if (g_state.wall_clock_known) {
        if (rolled_over) {
          g_state.today_kwh = 0.0f;
          g_state.today_is_partial = false;
        }
        g_state.today_kwh += d_kwh;
      }
      if (p > g_state.peak_power_w) g_state.peak_power_w = p;
      g_state.wall_clock_known = time_source::wall_clock_known();
      g_state.uptime_sec = (uint32_t)(now_us / 1000000ULL);
      g_state.unsynced_count = storage::current_unsynced_count();
      g_state.buffer_full = storage::is_buffer_full();
      state_unlock();
    }

    // Render OLED from a snapshot (no I/O under lock).
    display::tick();

    // 15-minute log row.
    if ((uint64_t)(now_us - last_log_us) >= (uint64_t)LOG_INTERVAL_SEC * 1000000ULL) {
      last_log_us = now_us;
      if (ok || st == PZEM_OK) {
        uint64_t seq = storage::last_seq() + 1;
        storage::RowFields rf;
        rf.seq = seq;
        rf.boot_id = storage::boot_id();
        rf.sec_since_boot = (uint32_t)(now_us / 1000000ULL);
        rf.V = sample.voltage;
        rf.I = sample.current;
        rf.P = sample.power;
        rf.Wh = sample.energy_wh;
        rf.PF = sample.pf;
        if (storage::append_row(rf)) {
          storage::set_last_seq(seq);
          if (state_lock()) {
            g_state.last_seq = seq;
            g_state.unsynced_count = storage::current_unsynced_count();
            state_unlock();
          }
          // Push to MilesWeb as soon as the next ConnectivityTask tick runs.
          // If Wi-Fi is reachable, the row ships within seconds; if not,
          // it stays in /log.csv and the periodic 2-min cycle retries.
          wifi_sync::request_immediate_sync();
        }
      }
    }

    // Mark clean uptime after passing the boot-loop window.
    if (!clean_uptime_marked &&
        (uint32_t)(now_us / 1000000ULL) - boot_start_us_sec >= BOOTLOOP_WINDOW_SEC) {
      health_mark_clean_uptime();
      clean_uptime_marked = true;
    }

    vTaskDelay(pdMS_TO_TICKS(DISPLAY_REFRESH_MS));
  }
}

// ---- ConnectivityTask (core 1) ---------------------------------------------
static void connectivity_task(void *) {
  esp_task_wdt_add(nullptr);
  uint64_t last_wifi_us = time_source::monotonic_us();
  // Run a Wi-Fi cycle quickly on first boot too — wait one interval to let
  // the rest of the system settle.
  bool first_cycle = true;

  for (;;) {
    esp_task_wdt_reset();
    ble_service::tick();

    if (!health::boot_loop_tripped()) {
      // On-demand scan requested via BLE Wi-Fi Config write of {"action":"scan"}.
      if (wifi_sync::consume_scan_request()) {
        wifi_sync::run_scan();
        ble_service::tick();  // push the fresh results immediately
      }

      uint64_t now_us = time_source::monotonic_us();
      uint64_t since_us = now_us - last_wifi_us;
      uint64_t interval_us = (uint64_t)WIFI_SCAN_INTERVAL_SEC * 1000000ULL;
      bool periodic_due = first_cycle ? (since_us > 30ULL * 1000000ULL)
                                       : (since_us >= interval_us);
      bool triggered = wifi_sync::consume_immediate_sync_request();
      if (periodic_due || triggered) {
        first_cycle = false;
        last_wifi_us = now_us;
        wifi_sync::run_cycle();
      }
    }
    vTaskDelay(pdMS_TO_TICKS(1000));
  }
}
