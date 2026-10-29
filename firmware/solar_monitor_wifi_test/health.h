#pragma once

#include <Arduino.h>

namespace health {

void begin();

// Register the calling task with the Task WDT.
void register_task();

// Feed the WDT for the current task.
void feed();

// True while the boot-loop guard is tripped: the firmware skips Wi-Fi and
// runs BLE-only. Set in begin() after BOOTLOOP_THRESHOLD fast boots.
bool boot_loop_tripped();

// Lift the trip for the rest of this boot. Called by the connectivity task once
// the tripped boot has stayed up BOOTLOOP_RECOVER_SEC.
void clear_boot_loop_trip();

}  // namespace health
