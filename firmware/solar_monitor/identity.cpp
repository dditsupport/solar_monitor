#include "identity.h"
#include "config.h"
#include <WiFi.h>

namespace identity {

static String s_device_id;
static String s_ble_name;

static void compute() {
  if (!s_device_id.isEmpty()) return;
  uint8_t mac[6];
  WiFi.macAddress(mac);
  char lower[16];
  snprintf(lower, sizeof(lower), "solar-%02x%02x%02x", mac[3], mac[4], mac[5]);
  char upper[16];
  snprintf(upper, sizeof(upper), "Solar-%02X%02X%02X", mac[3], mac[4], mac[5]);
  s_device_id = lower;
  s_ble_name = upper;
}

const String &device_id() {
  compute();
  return s_device_id;
}

const String &ble_name() {
  compute();
  return s_ble_name;
}

const char *fw_version() {
  return FW_VERSION;
}

}  // namespace identity
