#include "pzem.h"
#include "config.h"
#include "time_source.h"

#include <PZEM004Tv30.h>
#include <math.h>
#include "log_serial.h"

namespace pzem {

static PZEM004Tv30 *s_pzem = nullptr;
static uint8_t s_fail_streak = 0;
static uint64_t s_low_v_start_us = 0;
static bool s_low_v_active = false;

void begin() {
  // PZEM-004T-v30 (mandulaj) takes (HardwareSerial&, rxPin, txPin) and runs
  // Serial2.begin() internally — no need for an external begin() call.
  // rxPin = ESP32's RX (GPIO 16, connected to PZEM TX);
  // txPin = ESP32's TX (GPIO 17, connected to PZEM RX).
  s_pzem = new PZEM004Tv30(Serial2, PIN_PZEM_RX, PIN_PZEM_TX);
}

// [host-test begin]
// Whether one reading can be real (see the PZEM plausibility gate in
// config.h). NaN and +/-inf are rejected too: isnan() alone let inf through,
// and an "inf" row in /log.csv can never be parsed or sent.
bool plausible(float v, float i, float p, float e_wh, float pf, float hz) {
  if (!isfinite(v) || !isfinite(i) || !isfinite(p) || !isfinite(e_wh) ||
      !isfinite(pf) || !isfinite(hz)) return false;
  if (v < 0.0f || v > PZEM_V_MAX) return false;
  if (i < 0.0f || i > PZEM_I_MAX_A) return false;
  // P = V x I x PF, so it can never exceed V x I (10 % + 10 W for rounding).
  if (p < 0.0f || p > PZEM_P_MAX_W || p > v * i * 1.1f + 10.0f) return false;
  if (e_wh < 0.0f || e_wh > PZEM_E_MAX_WH) return false;
  if (pf < 0.0f || pf > 1.0f) return false;
  if (hz < 0.0f || hz > PZEM_HZ_MAX) return false;
  // Low voltage is the sensor-fault path (classify()); only check the mains
  // frequency when there is mains to measure.
  if (v >= SENSOR_LOW_V_THRESHOLD && hz < PZEM_HZ_MIN) return false;
  return true;
}

// Whether the energy counter can have risen from last_wh to new_wh in dt_s
// seconds. Drops always can: Reset PZEM, a swapped PZEM, the 9,999.99 kWh
// rollover.
bool rise_possible(double last_wh, double new_wh, double dt_s) {
  if (new_wh <= last_wh) return true;
  return new_wh - last_wh <= (double)PZEM_P_MAX_W * dt_s / 3600.0 + 50.0;
}
// [host-test end]

// Last accepted energy reading, for the jump guard.
static bool     s_have_last = false;
static double   s_last_wh = 0.0;
static uint64_t s_last_us = 0;
// A "jumped" value seen on consecutive reads (re-baseline after a swap).
static double   s_jump_wh = 0.0;
static uint8_t  s_jump_count = 0;
// Rejections, logged at most once a minute so a bad link can't flood serial.
static uint32_t s_rejected = 0;
static uint32_t s_last_reject_log_ms = 0;

static void note_rejected(const char *why, float v, float i, float p, float e_wh, float pf, float hz) {
  s_rejected++;
  uint32_t now = millis();
  if (s_last_reject_log_ms != 0 && now - s_last_reject_log_ms < 60000UL) return;
  s_last_reject_log_ms = now ? now : 1;
  LOG_PRINTF("[pzem] %s rejected (%u so far): V=%.1f I=%.3f P=%.1f E=%.2f kWh PF=%.3f Hz=%.1f"
             " - check the PZEM wiring / supply\n",
             why, (unsigned)s_rejected, v, i, p, e_wh / 1000.0f, pf, hz);
}

bool read(PzemSample &out) {
  if (!s_pzem) return false;
  float v = s_pzem->voltage();
  float i = s_pzem->current();
  float p = s_pzem->power();
  float e = s_pzem->energy();
  float pf = s_pzem->pf();
  float f = s_pzem->frequency();
  if (isnan(v) || isnan(i) || isnan(p) || isnan(e) || isnan(pf) || isnan(f)) {
    return false;   // no answer from the PZEM
  }
  float e_wh = e * 1000.0f;  // library returns kWh
  // An answer no PZEM can give (a garbled frame) is a failed read, not a row.
  if (!plausible(v, i, p, e_wh, pf, f)) {
    note_rejected("implausible reading", v, i, p, e_wh, pf, f);
    return false;
  }
  uint64_t now = time_source::monotonic_us();
  if (s_have_last && !rise_possible(s_last_wh, e_wh, (double)(now - s_last_us) / 1e6)) {
    if (s_jump_count > 0 && fabs(e_wh - s_jump_wh) <= 1000.0) {
      s_jump_count++;
    } else {
      s_jump_wh = e_wh;
      s_jump_count = 1;
    }
    if (s_jump_count < PZEM_JUMP_CONFIRM_READS) {
      note_rejected("energy jump", v, i, p, e_wh, pf, f);
      return false;
    }
    LOG_PRINTF("[pzem] energy %.2f -> %.2f kWh confirmed by %u readings - new counter accepted\n",
               s_last_wh / 1000.0, e_wh / 1000.0, (unsigned)s_jump_count);
  }
  s_jump_count = 0;
  s_have_last = true;
  s_last_wh = e_wh;
  s_last_us = now;
  out.voltage = v;
  out.current = i;
  out.power = p;
  out.energy_wh = e_wh;
  out.pf = pf;
  out.frequency = f;
  return true;
}

PzemStatus classify(bool ok, const PzemSample &sample) {
  if (!ok) {
    if (s_fail_streak < 255) s_fail_streak++;
    if (s_fail_streak >= PZEM_FAIL_THRESHOLD) return PZEM_STALE;
    // Below threshold, hold previous classification by returning PZEM_OK
    // (caller keeps last-known sample on screen).
    return PZEM_OK;
  }
  s_fail_streak = 0;

  // Voltage-based sensor-fault detection (distinguish broken vs night).
  uint64_t now = time_source::monotonic_us();
  if (sample.voltage < SENSOR_LOW_V_THRESHOLD) {
    if (!s_low_v_active) {
      s_low_v_active = true;
      s_low_v_start_us = now;
    }
    uint64_t dur_us = now - s_low_v_start_us;
    if (dur_us >= (uint64_t)SENSOR_FAULT_WINDOW_SEC * 1000000ULL) {
      return PZEM_SENSOR_FAULT;
    }
  } else {
    s_low_v_active = false;
  }
  return PZEM_OK;
}

bool reset_energy() {
  if (!s_pzem) return false;
  return s_pzem->resetEnergy();
}

static volatile bool s_reset_requested = false;
void request_reset() { s_reset_requested = true; }
bool consume_reset_request() {
  if (!s_reset_requested) return false;
  s_reset_requested = false;
  return true;
}

}  // namespace pzem
