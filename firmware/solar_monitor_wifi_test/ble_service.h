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

// True if BLE is in a healthy state: either advertising or a client is
// currently connected. The stuck-BLE watchdog in the connectivity task
// reboots the chip if this stays false for too long.
bool is_alive();

// True while a phone is currently connected over BLE. The periodic radio rest
// consults this so an in-progress provisioning session is never interrupted.
bool is_connected();

// Stop / restart BLE advertising for the periodic radio rest, so the 2.4 GHz
// front end goes genuinely idle instead of continuing to beacon. An already
// connected link is left untouched, and resume_advertising() is a no-op while a
// client is connected — NimBLE restarts advertising itself on that client's
// disconnect (see ServerCallbacks::onDisconnect).
void pause_advertising();
void resume_advertising();

}  // namespace ble_service
