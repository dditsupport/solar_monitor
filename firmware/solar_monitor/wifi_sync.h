#pragma once

#include <Arduino.h>

namespace wifi_sync {

void begin();

// Called periodically from ConnectivityTask. Returns true if a sync cycle ran
// to completion (regardless of how many rows transferred).
bool run_cycle();

// Pause notifications during HTTPS POST to ease 2.4 GHz coexistence with BLE.
// Set/cleared internally; ble_service.cpp consults this via is_radio_busy().
bool is_radio_busy();

}  // namespace wifi_sync
