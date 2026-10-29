// Wi-Fi test build: NO PZEM. Fixed dummy readings in place of the Modbus
// sensor, so the rest of the firmware (logging, Wi-Fi, BLE, server) runs on a
// bare ESP32. Same API as the real pzem.cpp (pzem.h).
#include "pzem.h"
#include "config.h"
#include "time_source.h"
#include "log_serial.h"

namespace pzem {

static const float kVolts = 230.0f;
static const float kAmps  = 1.00f;
static const float kWatts = 230.0f;

// The energy counter runs from boot at the fixed power, so uploaded rows are
// not all identical and Reset PZEM has something to reset. A reboot restarts
// it at 0, which the server records as a counter reset like a real one.
static uint64_t s_zero_us = 0;

void begin() {
  s_zero_us = time_source::monotonic_us();
  LOG_PRINTLN("[pzem] SIMULATED (Wi-Fi test build): fixed 230 V / 1 A / 230 W");
}

bool read(PzemSample &out) {
  double hours = (double)(time_source::monotonic_us() - s_zero_us) / 3.6e9;
  out.voltage = kVolts;
  out.current = kAmps;
  out.power = kWatts;
  out.energy_wh = (float)(kWatts * hours);
  out.pf = 0.99f;
  out.frequency = 50.0f;
  return true;
}

PzemStatus classify(bool, const PzemSample &) {
  return PZEM_OK;
}

bool reset_energy() {
  s_zero_us = time_source::monotonic_us();
  LOG_PRINTLN("[pzem] SIMULATED energy counter reset to 0");
  return true;
}

static volatile bool s_reset_requested = false;
void request_reset() { s_reset_requested = true; }
bool consume_reset_request() {
  if (!s_reset_requested) return false;
  s_reset_requested = false;
  return true;
}

}  // namespace pzem
