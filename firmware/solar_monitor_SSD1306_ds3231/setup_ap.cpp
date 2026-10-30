#include "setup_ap.h"
#include "setup_ap_page.h"
#include "config.h"
#include "identity.h"
#include "storage.h"
#include "wifi_sync.h"
#include "shared_state.h"
#include "log_serial.h"

#include <WiFi.h>
#include <WebServer.h>
#include <DNSServer.h>

namespace setup_ap {

static_assert(sizeof(SETUP_AP_PASSWORD) - 1 >= 8 && sizeof(SETUP_AP_PASSWORD) - 1 <= 63,
              "SETUP_AP_PASSWORD must be 8-63 characters (WPA2)");

// [host-test begin]
bool should_stop(bool saved_and_connected, uint32_t connected_sec,
                 uint32_t stations, uint32_t idle_sec, bool has_creds) {
  // Done: give the phone a minute to show "Connected" before the network it
  // is on goes away.
  if (saved_and_connected) return connected_sec >= SETUP_AP_DONE_SEC;
  // Someone is on the setup page: never pull it from under them.
  if (stations > 0) return false;
  // Nobody came. Close only if a router is saved: right after a Wi-Fi reset
  // there is none, and closing would leave the device offline for good.
  return has_creds && idle_sec >= SETUP_AP_IDLE_SEC;
}
// [host-test end]

static const IPAddress kIp(192, 168, 4, 1);
static const IPAddress kMask(255, 255, 255, 0);

static volatile bool s_start_req = false;
static volatile bool s_stop_req = false;
static volatile bool s_active = false;
static String s_ssid;

static WebServer *s_http = nullptr;
static DNSServer *s_dns = nullptr;
static volatile TaskHandle_t s_task = nullptr;
static volatile bool s_task_quit = false;

static uint32_t s_last_station_ms = 0;
// A network saved from the page. wifi_sync tries it on its next cycle; it is
// "pending" until wifi_sync's attempt counter moves past the value at save.
static volatile bool s_saved = false;
static volatile bool s_save_pending = false;
static volatile uint32_t s_save_attempt_base = 0;
static String s_saved_ssid;
static uint32_t s_connected_ms = 0;   // when the saved network came up (0 = not yet)

static void set_state_flag(bool on) {
  if (state_lock()) {
    g_state.setup_ap = on;
    state_unlock();
  }
}

static bool joined_saved_network() {
  return s_saved && WiFi.isConnected() && WiFi.SSID() == s_saved_ssid;
}

// ---- HTTP handlers (run on the portal task) --------------------------------

static void no_cache() {
  s_http->sendHeader("Cache-Control", "no-store");
}

static void handle_root() {
  no_cache();
  s_http->send(200, "text/html; charset=utf-8", SETUP_PAGE);
}

// Any other URL: the phone's captive-portal probe (iOS hotspot-detect.html,
// Android generate_204, ...) or a site typed by hand. Sending it to the setup
// page is what makes the phone pop the page up by itself.
static void handle_other() {
  s_http->sendHeader("Location", "http://192.168.4.1/", true);
  no_cache();
  s_http->send(302, "text/plain", "");
}

// When the last scan was asked for (0 = never). Read/written on the portal
// task and in start_now(), which runs before the portal task exists.
static uint32_t s_last_scan_req_ms = 0;

static void handle_scan() {
  // Scan only when the page asks (Scan again) and not more often than
  // SETUP_AP_RESCAN_MS: every scan leaves the hotspot's channel, and a phone
  // that drops off and reconnects reloads the page. Rescanning on every load
  // turned that into a loop that kept knocking the phone off.
  bool queued = false;
  if (s_http->arg("refresh") == "1" &&
      (s_last_scan_req_ms == 0 || millis() - s_last_scan_req_ms >= SETUP_AP_RESCAN_MS)) {
    wifi_sync::request_scan();
    s_last_scan_req_ms = millis();
    queued = true;
  }
  String out = "{\"queued\":";
  out += queued ? "true" : "false";
  out += ",\"v\":";
  out += wifi_sync::scan_results_version();
  out += ",\"networks\":";
  out += wifi_sync::get_scan_results_json();   // [{"s":ssid,"r":rssi,"e":0|1}]
  out += "}";
  no_cache();
  s_http->send(200, "application/json", out);
}

static void handle_save() {
  String ssid = s_http->arg("ssid");
  String pass = s_http->arg("password");
  const char *err = nullptr;
  if (ssid.isEmpty() || ssid.length() > 32) err = "Pick a network (1-32 characters).";
  else if (pass.length() > 63 || (pass.length() > 0 && pass.length() < 8))
    err = "Wi-Fi passwords are 8-63 characters (or empty for an open network).";
  else if (!storage::add_wifi_cred(ssid, pass)) err = "Could not save. Try again.";
  if (err) {
    String out = "{\"ok\":false,\"error\":\"";
    out += err;
    out += "\"}";
    s_http->send(400, "application/json", out);
    return;
  }
  s_saved_ssid = ssid;
  s_connected_ms = 0;
  s_save_attempt_base = wifi_sync::connect_attempts();
  s_saved = true;
  s_save_pending = true;
  wifi_sync::request_immediate_sync();
  LOG_PRINTF("[setup] saved \"%s\" from the setup page - connecting\n", ssid.c_str());
  no_cache();
  s_http->send(200, "application/json", "{\"ok\":true}");
}

// Why the last attempt failed, from the STA disconnect reason.
static const char *failure_reason(uint8_t r) {
  switch (r) {
    case 2: case 15: case 202: case 204: return "wrong_password";  // auth / handshake
    case 201: return "not_found";                                  // NO_AP_FOUND
    default:  return "other";
  }
}

static void handle_status() {
  if (s_save_pending && wifi_sync::connect_attempts() != s_save_attempt_base) s_save_pending = false;
  const char *state = "idle";
  if (s_saved) {
    if (joined_saved_network()) state = "connected";
    else if (s_save_pending) state = "connecting";
    else state = "failed";
  }
  // Hand-built: every value is ours except the SSID, which is escaped.
  String ssid = s_saved_ssid;
  ssid.replace("\\", "\\\\");
  ssid.replace("\"", "\\\"");
  String out = "{\"state\":\"";
  out += state;
  out += "\",\"ssid\":\"";
  out += ssid;
  out += "\",\"device\":\"";
  out += identity::device_id();
  out += "\",\"hotspot\":\"";
  out += s_ssid;
  out += "\"";
  if (strcmp(state, "connected") == 0) {
    out += ",\"ip\":\"";
    out += WiFi.localIP().toString();
    out += "\"";
  }
  if (strcmp(state, "failed") == 0) {
    out += ",\"reason\":\"";
    out += failure_reason(wifi_sync::last_disconnect_reason());
    out += "\"";
  }
  out += "}";
  no_cache();
  s_http->send(200, "application/json", out);
}

static void portal_task(void *) {
  while (!s_task_quit) {
    s_dns->processNextRequest();
    s_http->handleClient();
    vTaskDelay(pdMS_TO_TICKS(10));
  }
  s_task = nullptr;
  vTaskDelete(nullptr);
}

// ---- open / close (connectivity task only) ---------------------------------

static void start_now() {
  if (s_active) return;
  const String &id = identity::device_id();   // "solar-a3f29c"
  String tail = id.length() >= 4 ? id.substring(id.length() - 4) : id;
  tail.toUpperCase();
  s_ssid = String("SolarMonitor-") + tail;

  // Off the old router: its credentials were just erased.
  WiFi.disconnect(false, false);
  WiFi.mode(WIFI_AP_STA);
  WiFi.softAPConfig(kIp, kIp, kMask);
  if (!WiFi.softAP(s_ssid.c_str(), SETUP_AP_PASSWORD)) {
    LOG_PRINTLN("[setup] could not start the hotspot");
    WiFi.mode(WIFI_STA);
    return;
  }

  s_dns = new DNSServer();
  s_dns->setErrorReplyCode(DNSReplyCode::NoError);
  s_dns->start(53, "*", kIp);   // every name -> us, for the captive portal

  s_http = new WebServer(80);
  s_http->on("/", HTTP_GET, handle_root);
  s_http->on("/scan.json", HTTP_GET, handle_scan);
  s_http->on("/save", HTTP_POST, handle_save);
  s_http->on("/status.json", HTTP_GET, handle_status);
  s_http->onNotFound(handle_other);
  s_http->begin();

  s_saved = false;
  s_save_pending = false;
  s_connected_ms = 0;
  s_last_station_ms = millis();
  s_task_quit = false;
  s_active = true;
  TaskHandle_t h = nullptr;
  xTaskCreatePinnedToCore(portal_task, "setup_ap", SETUP_AP_TASK_STACK, nullptr, 1, &h, 1);
  s_task = h;

  set_state_flag(true);
  wifi_sync::request_scan();   // so the page has a list straight away,
  s_last_scan_req_ms = millis(); // scanned before any phone has joined
  LOG_PRINTF("[setup] hotspot on: join \"%s\" (password on the label / in the docs), "
             "then open http://192.168.4.1\n", s_ssid.c_str());
}

static void stop_now(const char *why) {
  if (!s_active) return;
  s_active = false;
  s_task_quit = true;
  for (int i = 0; i < 50 && s_task != nullptr; ++i) vTaskDelay(pdMS_TO_TICKS(10));
  s_http->stop();
  delete s_http;
  s_http = nullptr;
  s_dns->stop();
  delete s_dns;
  s_dns = nullptr;
  WiFi.softAPdisconnect(true);   // AP off; the station side stays
  WiFi.mode(WIFI_STA);
  set_state_flag(false);
  LOG_PRINTF("[setup] hotspot off (%s)\n", why);
  wifi_sync::request_immediate_sync();
}

// ---- public -----------------------------------------------------------------

void request_start() { s_start_req = true; }
void request_stop() { s_stop_req = true; }
bool active() { return s_active; }
String ssid() { return s_active ? s_ssid : String(); }

bool hold_connects() {
  return s_active && !s_save_pending && WiFi.softAPgetStationNum() > 0;
}

void tick() {
  if (s_stop_req) {
    s_stop_req = false;
    s_start_req = false;
    stop_now("button");
  }
  if (s_start_req) {
    s_start_req = false;
    start_now();
  }
  if (!s_active) return;

  uint32_t now = millis();
  uint32_t stations = WiFi.softAPgetStationNum();
  if (stations > 0) s_last_station_ms = now;
  if (s_save_pending && wifi_sync::connect_attempts() != s_save_attempt_base) s_save_pending = false;

  bool joined = joined_saved_network();
  if (joined && s_connected_ms == 0) {
    s_connected_ms = now;
    LOG_PRINTF("[setup] connected to \"%s\" - closing the hotspot in %u s\n",
               s_saved_ssid.c_str(), (unsigned)SETUP_AP_DONE_SEC);
  }
  if (!joined) s_connected_ms = 0;

  storage::WifiCred creds[MAX_WIFI_CREDS];
  bool has_creds = storage::get_wifi_creds(creds, MAX_WIFI_CREDS) > 0;
  if (should_stop(joined, joined ? (now - s_connected_ms) / 1000 : 0, stations,
                  (now - s_last_station_ms) / 1000, has_creds)) {
    stop_now(joined ? "connected" : "idle");
  }
}

}  // namespace setup_ap
