#include "ble_service.h"
#include "config.h"
#include "identity.h"
#include "shared_state.h"
#include "storage.h"
#include "time_source.h"
#include "wifi_sync.h"

#include <NimBLEDevice.h>
#include <ArduinoJson.h>
#include <esp_task_wdt.h>

#include <string>

static inline std::string to_std(const String &s) {
  return std::string(s.c_str(), s.length());
}

// -----------------------------------------------------------------------------
// BLE GATT layout (see docs/PROVISIONING.md for client-side flow):
//   Service: BLE_SERVICE_UUID
//     Device Info     READ       JSON {device_id, fw, unsynced_count, boot_id, uptime_sec}
//     Set Wall Time   WRITE      ISO 8601 string
//     Boot History    READ       JSON [{boot_id, duration_sec}, ...]
//     Data Stream     NOTIFY     CSV rows separated by \n, terminator "END\n"
//     Sync ACK        WRITE      uint64 seq (decimal string) the app forwarded ok
//     Wi-Fi Config    WRITE      JSON {ssid, password}
//     Wi-Fi Status    READ       JSON {ssid, status, ip}
// -----------------------------------------------------------------------------
//
// SECURITY TODO: no bonding/pairing in v1. Anyone in range can read buffered
// readings and write Wi-Fi credentials. Acceptable for a bench / single-user
// device but should be hardened before any multi-tenant deployment.

namespace ble_service {

static NimBLEServer *s_server = nullptr;
static NimBLECharacteristic *s_char_info = nullptr;
static NimBLECharacteristic *s_char_set_time = nullptr;
static NimBLECharacteristic *s_char_boots = nullptr;
static NimBLECharacteristic *s_char_stream = nullptr;
static NimBLECharacteristic *s_char_ack = nullptr;
static NimBLECharacteristic *s_char_wifi_cfg = nullptr;
static NimBLECharacteristic *s_char_wifi_status = nullptr;
static NimBLECharacteristic *s_char_wifi_scan = nullptr;
static uint32_t s_last_pushed_scan_version = 0;
static WifiStatus s_last_pushed_wifi_status = WIFI_IDLE;

static volatile bool s_client_connected = false;
static volatile bool s_streaming_active = false;
static volatile bool s_stream_requested = false;
static uint16_t s_mtu = 23;  // default until negotiated

static String s_wifi_status_json = "{\"status\":\"idle\"}";

static void set_ble_status(BleStatus st) {
  if (state_lock()) {
    g_state.ble_status = st;
    state_unlock();
  }
}

static String build_device_info_json() {
  StaticJsonDocument<256> doc;
  doc["device_id"] = identity::device_id();
  doc["fw"] = identity::fw_version();
  doc["unsynced_count"] = storage::current_unsynced_count();
  doc["current_boot_id"] = storage::boot_id();
  doc["uptime_sec"] = (uint32_t)(time_source::monotonic_us() / 1000000ULL);
  doc["last_seq"] = storage::last_seq();
  doc["expected_row_count"] = storage::current_unsynced_count();
  String out;
  serializeJson(doc, out);
  return out;
}

static String build_boot_history_json() {
  StaticJsonDocument<1500> doc;
  JsonArray arr = doc.to<JsonArray>();
  storage::BootRecord recs[MAX_BOOT_HISTORY];
  size_t n = storage::get_boot_history(recs, MAX_BOOT_HISTORY);
  for (size_t i = 0; i < n; ++i) {
    JsonObject o = arr.createNestedObject();
    o["boot_id"] = recs[i].boot_id;
    o["duration_sec"] = recs[i].duration_sec;
  }
  String out;
  serializeJson(doc, out);
  return out;
}

// ---- Server / connection callbacks ------------------------------------------

class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer *srv, ble_gap_conn_desc *desc) override {
    s_client_connected = true;
    s_mtu = 23;
    set_ble_status(BLE_CLIENT_CONNECTED);
    Serial.println("[ble] client connected");
  }
  void onDisconnect(NimBLEServer *srv) override {
    s_client_connected = false;
    s_stream_requested = false;
    s_streaming_active = false;
    set_ble_status(BLE_ADVERTISING);
    Serial.println("[ble] client disconnected, restart advertising");
    NimBLEDevice::startAdvertising();
  }
  void onMTUChange(uint16_t mtu, ble_gap_conn_desc *desc) override {
    s_mtu = mtu;
    Serial.printf("[ble] MTU=%u\n", mtu);
  }
};

// ---- Characteristic callbacks -----------------------------------------------

class SetTimeCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic *c) override {
    std::string v = c->getValue();
    if (v.empty()) return;
    time_t epoch = time_source::parse_iso8601(v.c_str());
    if (epoch == 0) {
      Serial.printf("[ble] set_time bad value: %s\n", v.c_str());
      return;
    }
    if (time_source::set_wall_clock(epoch)) {
      if (state_lock()) {
        g_state.wall_clock_known = true;
        state_unlock();
      }
      Serial.printf("[ble] wall clock set to %ld\n", (long)epoch);
    }
  }
};

class StreamCallbacks : public NimBLECharacteristicCallbacks {
  void onSubscribe(NimBLECharacteristic *c, ble_gap_conn_desc *desc, uint16_t subValue) override {
    if (subValue == 0) {
      s_stream_requested = false;
      s_streaming_active = false;
      Serial.println("[ble] stream unsubscribed");
    } else {
      s_stream_requested = true;
      Serial.println("[ble] stream subscribed");
    }
  }
};

class AckCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic *c) override {
    std::string v = c->getValue();
    if (v.empty()) return;
    uint64_t acked = strtoull(v.c_str(), nullptr, 10);
    if (acked == 0) {
      Serial.printf("[ble] ack bad value: %s\n", v.c_str());
      return;
    }
    if (storage::truncate_up_to(acked)) {
      if (state_lock()) {
        g_state.unsynced_count = storage::current_unsynced_count();
        state_unlock();
      }
      storage::set_last_sync_at((uint32_t)time(nullptr));
      Serial.printf("[ble] truncated up to seq=%llu\n", (unsigned long long)acked);
    }
  }
};

class WifiCfgCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic *c) override {
    std::string v = c->getValue();
    StaticJsonDocument<384> doc;
    if (deserializeJson(doc, v)) {
      s_wifi_status_json = "{\"status\":\"error\",\"detail\":\"bad json\"}";
      s_char_wifi_status->setValue(to_std(s_wifi_status_json));
      s_char_wifi_status->notify();
      return;
    }

    // Command shape: {"action":"scan"}
    String action = (const char *)(doc["action"] | "");
    if (action == "scan") {
      wifi_sync::request_scan();
      s_wifi_status_json = "{\"status\":\"scanning\"}";
      s_char_wifi_status->setValue(to_std(s_wifi_status_json));
      s_char_wifi_status->notify();
      // Force the next tick() to push the post-scan "idle" transition by
      // pretending we already broadcast SCANNING from the periodic path.
      s_last_pushed_wifi_status = WIFI_SCANNING;
      Serial.println("[ble] scan requested");
      return;
    }

    // Credential shape: {"ssid":"...","password":"..."}
    String ssid = (const char *)(doc["ssid"] | "");
    String pass = (const char *)(doc["password"] | "");
    if (ssid.isEmpty()) {
      s_wifi_status_json = "{\"status\":\"error\",\"detail\":\"empty ssid\"}";
      s_char_wifi_status->setValue(to_std(s_wifi_status_json));
      s_char_wifi_status->notify();
      return;
    }
    if (storage::add_wifi_cred(ssid, pass)) {
      StaticJsonDocument<160> r;
      r["status"] = "saved";
      r["ssid"] = ssid;
      r["next"] = "connecting";
      String out;
      serializeJson(r, out);
      s_wifi_status_json = out;
      s_char_wifi_status->setValue(to_std(s_wifi_status_json));
      s_char_wifi_status->notify();
      wifi_sync::request_immediate_sync();
      Serial.printf("[ble] wifi cred saved: %s, immediate sync requested\n", ssid.c_str());
    } else {
      s_wifi_status_json = "{\"status\":\"error\",\"detail\":\"save failed\"}";
      s_char_wifi_status->setValue(to_std(s_wifi_status_json));
      s_char_wifi_status->notify();
    }
  }
};

// ---- Data stream pump -------------------------------------------------------

static void pump_stream() {
  if (!s_stream_requested || !s_client_connected) {
    s_streaming_active = false;
    return;
  }
  if (wifi_sync::is_radio_busy()) {
    // Coexistence: pause while Wi-Fi HTTPS POST runs.
    return;
  }
  s_streaming_active = true;

  uint16_t mtu_payload = (s_mtu > 3) ? (s_mtu - 3) : 20;
  if (mtu_payload > 240) mtu_payload = 240;

  // Stream the current snapshot of rows. We use a snapshot by `last_seq` at
  // pump start to avoid sending rows the sampler appends mid-stream.
  uint64_t snap = storage::snapshot_max_seq();
  String chunk;
  chunk.reserve(mtu_payload + 64);

  storage::stream_rows_up_to(snap, [&](const storage::RowFields &r) -> bool {
    char line[96];
    int n = snprintf(line, sizeof(line),
                     "%llu,%u,%u,%.2f,%.3f,%.2f,%.2f,%.3f\n",
                     (unsigned long long)r.seq, r.boot_id, r.sec_since_boot,
                     r.V, r.I, r.P, r.Wh, r.PF);
    if (n <= 0) return true;
    if (chunk.length() + n > mtu_payload) {
      s_char_stream->setValue((uint8_t *)chunk.c_str(), chunk.length());
      s_char_stream->notify();
      chunk = "";
      delay(15);  // small gap to let stack flush
      esp_task_wdt_reset();
      if (!s_client_connected || !s_stream_requested) return false;
    }
    chunk += line;
    return true;
  });

  if (chunk.length() > 0 && s_client_connected && s_stream_requested) {
    s_char_stream->setValue((uint8_t *)chunk.c_str(), chunk.length());
    s_char_stream->notify();
    delay(15);
  }
  // Terminator
  if (s_client_connected && s_stream_requested) {
    const char *eos = "END\n";
    s_char_stream->setValue((uint8_t *)eos, 4);
    s_char_stream->notify();
  }
  // One-shot stream; require client to re-subscribe for a fresh dump.
  s_stream_requested = false;
  s_streaming_active = false;
}

// ---- Setup ------------------------------------------------------------------

void begin() {
  NimBLEDevice::init(identity::ble_name().c_str());
  NimBLEDevice::setPower(ESP_PWR_LVL_P3);  // moderate TX
  NimBLEDevice::setMTU(247);

  s_server = NimBLEDevice::createServer();
  s_server->setCallbacks(new ServerCallbacks());

  NimBLEService *svc = s_server->createService(BLE_SERVICE_UUID);

  s_char_info = svc->createCharacteristic(
      BLE_UUID_DEVICE_INFO, NIMBLE_PROPERTY::READ);
  s_char_info->setValue(to_std(build_device_info_json()));

  s_char_set_time = svc->createCharacteristic(
      BLE_UUID_SET_WALL_TIME, NIMBLE_PROPERTY::WRITE);
  s_char_set_time->setCallbacks(new SetTimeCallbacks());

  s_char_boots = svc->createCharacteristic(
      BLE_UUID_BOOT_HISTORY, NIMBLE_PROPERTY::READ);
  s_char_boots->setValue(to_std(build_boot_history_json()));

  s_char_stream = svc->createCharacteristic(
      BLE_UUID_DATA_STREAM, NIMBLE_PROPERTY::NOTIFY);
  s_char_stream->setCallbacks(new StreamCallbacks());

  s_char_ack = svc->createCharacteristic(
      BLE_UUID_SYNC_ACK, NIMBLE_PROPERTY::WRITE);
  s_char_ack->setCallbacks(new AckCallbacks());

  s_char_wifi_cfg = svc->createCharacteristic(
      BLE_UUID_WIFI_CONFIG, NIMBLE_PROPERTY::WRITE);
  s_char_wifi_cfg->setCallbacks(new WifiCfgCallbacks());

  s_char_wifi_status = svc->createCharacteristic(
      BLE_UUID_WIFI_STATUS, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
  s_char_wifi_status->setValue(to_std(s_wifi_status_json));

  s_char_wifi_scan = svc->createCharacteristic(
      BLE_UUID_WIFI_SCAN, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
  s_char_wifi_scan->setValue(to_std(wifi_sync::get_scan_results_json()));

  svc->start();

  NimBLEAdvertising *adv = NimBLEDevice::getAdvertising();
  adv->addServiceUUID(BLE_SERVICE_UUID);
  adv->setName(identity::ble_name().c_str());
  adv->setScanResponse(true);
  adv->start();

  set_ble_status(BLE_ADVERTISING);
  Serial.printf("[ble] advertising as %s\n", identity::ble_name().c_str());
}

void tick() {
  // Refresh dynamic READ values.
  if (s_char_info) s_char_info->setValue(to_std(build_device_info_json()));
  if (s_char_boots) s_char_boots->setValue(to_std(build_boot_history_json()));

  // Mirror live Wi-Fi state into the status characteristic and notify on change.
  SharedState snap;
  if (state_snapshot(snap)) {
    if (snap.wifi_status != s_last_pushed_wifi_status) {
      StaticJsonDocument<160> doc;
      const char *st = "idle";
      switch (snap.wifi_status) {
        case WIFI_IDLE: st = "idle"; break;
        case WIFI_SCANNING: st = "scanning"; break;
        case WIFI_CONNECTING: st = "connecting"; break;
        case WIFI_CONNECTED: st = "connected"; break;
        case WIFI_SYNCING: st = "syncing"; break;
      }
      doc["status"] = st;
      String out;
      serializeJson(doc, out);
      s_wifi_status_json = out;
      if (s_char_wifi_status) {
        s_char_wifi_status->setValue(to_std(out));
        s_char_wifi_status->notify();
      }
      s_last_pushed_wifi_status = snap.wifi_status;
    }
  }

  // Push new scan results when the version counter advances.
  uint32_t sv = wifi_sync::scan_results_version();
  if (sv != s_last_pushed_scan_version && s_char_wifi_scan) {
    s_char_wifi_scan->setValue(to_std(wifi_sync::get_scan_results_json()));
    s_char_wifi_scan->notify();
    s_last_pushed_scan_version = sv;
  }

  if (s_stream_requested) {
    pump_stream();
  }
}

bool is_streaming() { return s_streaming_active; }

}  // namespace ble_service
