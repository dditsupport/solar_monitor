// Runs the firmware's real run_scan() (pulled from wifi_sync.cpp by run.sh)
// against a fake WiFi with a crowded scan, to check the BLE Wi-Fi Scan JSON:
// old keys kept, "c"/"a" added, strongest first, and never over
// SCAN_JSON_MAX_BYTES (NimBLE stores nothing past 512 bytes).
#include <ArduinoJson.h>
#include <cstdio>
#include <cstring>
#include <string>
#define String std::string
#define LOG_PRINTF(...) printf(__VA_ARGS__)
enum wifi_auth_mode_t { WIFI_AUTH_OPEN = 0, WIFI_AUTH_WPA2_PSK = 3, WIFI_AUTH_WPA2_WPA3_PSK = 7 };
enum WifiStatus { WIFI_IDLE, WIFI_SCANNING };
struct Ap { std::string ssid; int rssi; int ch; wifi_auth_mode_t auth; };
static Ap g_aps[40]; static int g_n = 0;
struct FakeWiFi {
  int scanNetworks(bool, bool, bool, int) { return g_n; }
  std::string SSID(int i) { return g_aps[i].ssid; }
  int RSSI(int i) { return g_aps[i].rssi; }
  int channel(int i) { return g_aps[i].ch; }
  wifi_auth_mode_t encryptionType(int i) { return g_aps[i].auth; }
  void scanDelete() {}
} WiFi;
static volatile bool s_radio_busy = false;
static std::string s_scan_results_json = "[]";
static volatile unsigned s_scan_version = 0;
static void ensure_sta() {}
static void set_wifi_status(WifiStatus) {}
#include "scan_extracted.inc"
int fails = 0;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL " __VA_ARGS__); printf("\n"); } } while (0)
int main() {
  // 30 APs with 32-character names (the longest an SSID can be), shuffled RSSI.
  g_n = 30;
  for (int i = 0; i < g_n; ++i) {
    char name[48]; snprintf(name, sizeof name, "JioFiber-%02d-xxxxxxxxxxxxxxxxxxxx", i);
    std::string ssid(name); ssid.resize(32);
    g_aps[i] = { ssid, -40 - ((i * 7) % 50), 1 + i % 13, i % 3 ? WIFI_AUTH_WPA2_WPA3_PSK : WIFI_AUTH_OPEN };
  }
  run_scan();
  const std::string &js = s_scan_results_json;
  CHECK(js.size() <= SCAN_JSON_MAX_BYTES && js.size() <= 512, "JSON %zu bytes > budget", js.size());
  JsonDocument doc;
  CHECK(!deserializeJson(doc, js), "JSON does not parse: %s", js.c_str());
  JsonArray arr = doc.as<JsonArray>();
  CHECK(arr.size() >= 4, "too few entries kept: %u", (unsigned)arr.size());
  int prev = 0; bool first = true, keys = true, sorted = true;
  for (JsonObject o : arr) {
    keys = keys && o["s"].is<const char *>() && o["r"].is<int>() && o["e"].is<int>() && o["c"].is<int>() && o["a"].is<int>();
    int r = o["r"]; if (!first && r > prev) sorted = false; prev = r; first = false;
  }
  CHECK(keys, "every entry has s, r, e (old app) and c, a (new)");
  CHECK(sorted, "strongest first");
  CHECK(arr[0]["r"].as<int>() == -40, "the strongest AP is kept");
  // A quiet street: everything fits, nothing dropped, fields right.
  g_n = 2;
  g_aps[0] = { "JioFiber-4G7k", -58, 13, WIFI_AUTH_WPA2_WPA3_PSK };
  g_aps[1] = { "MyPhone", -49, 6, WIFI_AUTH_WPA2_PSK };
  run_scan();
  CHECK(s_scan_results_json ==
        "[{\"s\":\"MyPhone\",\"r\":-49,\"e\":1,\"c\":6,\"a\":3},{\"s\":\"JioFiber-4G7k\",\"r\":-58,\"e\":1,\"c\":13,\"a\":7}]",
        "two-AP JSON: %s", s_scan_results_json.c_str());
  printf("scan json: %d failure(s)\n", fails);
  return fails != 0;
}
