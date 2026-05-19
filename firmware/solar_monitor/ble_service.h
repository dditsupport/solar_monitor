#pragma once

#include <Arduino.h>

namespace ble_service {

void begin();

// Called periodically (e.g. 1 Hz) so the service can refresh dynamic values
// like the Device Info JSON, drive the data-stream pump, and update SharedState
// with current connection status.
void tick();

// True if the radio is currently streaming notifications (informational).
bool is_streaming();

}  // namespace ble_service
