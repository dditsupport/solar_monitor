#pragma once

#include <Arduino.h>

// Wi-Fi setup hotspot for phones without the Android app (iPhone included).
//
// Opened only by the BOOT button (button.cpp), never automatically. The device
// becomes an access point "SolarMonitor-XXXX" (WPA2, SETUP_AP_PASSWORD), runs
// a captive portal at 192.168.4.1 where the user picks their router and types
// its password, and closes itself once the device is online again.
//
// It is only for configuration: nothing about the hotspot is kept in NVS (its
// name comes from the device_id, its password is compiled in, and on/off lives
// in RAM, so a reboot always closes it). The one thing it writes is the router
// credentials, through storage::add_wifi_cred(), the same as the BLE path.

namespace setup_ap {

// Ask for the hotspot to open / close. Safe from any task: the connectivity
// task, which owns the radio, carries it out in tick().
void request_start();
void request_stop();

// Called by the connectivity task once a second: applies requests and the
// stop rules below.
void tick();

bool active();

// "SolarMonitor-A3F2" (empty while closed).
String ssid();

// True while a phone is on the hotspot and no save is waiting to be tried.
// wifi_sync then skips its own reconnect attempts: on the ESP32 the hotspot
// follows the station's channel, and a connect attempt would drop the phone.
bool hold_connects();

// [host-test begin]
// Whether the hotspot should close now.
//   saved_and_connected  the user saved a network here and the device joined it
//   connected_sec        seconds since it joined (the phone shows "Connected")
//   stations             phones currently on the hotspot
//   idle_sec             seconds with no phone on the hotspot
//   has_creds            a router is saved (false right after a Wi-Fi reset)
bool should_stop(bool saved_and_connected, uint32_t connected_sec,
                 uint32_t stations, uint32_t idle_sec, bool has_creds);
// [host-test end]

}  // namespace setup_ap
