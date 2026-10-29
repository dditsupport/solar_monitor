#pragma once

#include <stdint.h>

// The board's BOOT button (GPIO 0) as a user button, read only after boot:
//
//   RESET, then BOOT ~1 s later, held 3 s  -> factory erase (log + NVS), reboot
//   BOOT held 3 s any later time           -> Wi-Fi reset: erase the saved
//                                             router and open the setup hotspot
//                                             (held again: close the hotspot)
//
// GPIO 0 is also the ESP32's download-mode strap, but the ROM samples it only
// at the instant reset is released. Held then, the chip enters the serial
// bootloader and this firmware never runs (press RESET again, nothing is
// lost). Pressed after that, it is an ordinary input.

namespace button {

// [host-test begin]
enum class Gesture : uint8_t { None, FactoryErase, WifiReset };

// What a press is, from when it went down (ms since boot) and how long it has
// been held. Pure, so the host tests cover it.
Gesture classify(uint32_t pressed_at_ms, uint32_t held_ms);
// [host-test end]

// Configure the pin. Call first thing in setup().
void begin();

// The factory-erase window: blocks until ERASE_WINDOW_MS after boot (longer
// only while the button is held), fast-blinking the LED. Held 3 s inside it
// wipes the log and NVS and reboots. `show` (may be null) puts two lines on
// the OLED. Call once storage is mounted.
void boot_window(void (*show)(const char *, const char *));

// Wi-Fi reset gesture; call from loop() every ~50 ms.
void poll();

}  // namespace button
