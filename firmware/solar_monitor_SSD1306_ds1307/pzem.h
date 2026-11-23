#pragma once

#include "shared_state.h"

namespace pzem {

void begin();

// Reads one sample. On success returns true and fills `out`.
// On Modbus failure, returns false; caller should not use `out`.
bool read(PzemSample &out);

// The plausibility gate read() applies (pure; host-tested): a reading no
// PZEM can produce, and an energy rise faster than PZEM_P_MAX_W allows.
bool plausible(float v, float i, float p, float e_wh, float pf, float hz);
bool rise_possible(double last_wh, double new_wh, double dt_s);

// Classify a stream of samples and return the right PzemStatus.
// Pass `ok = true` on a successful read, `ok = false` on a failed read.
// Internally counts consecutive failures and tracks low-voltage duration.
PzemStatus classify(bool ok, const PzemSample &sample);

// Reset the cumulative energy counter on the PZEM (not used in normal operation).
bool reset_energy();

// Request an energy-register reset from another task (e.g. the BLE service).
// The actual Modbus reset is performed by the PZEM-owning sampling task when
// it calls consume_reset_request(), so resetEnergy() never races a concurrent
// read() from a different task.
void request_reset();
bool consume_reset_request();

}  // namespace pzem
