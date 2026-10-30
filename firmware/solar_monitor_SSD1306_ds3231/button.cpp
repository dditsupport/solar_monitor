#include "button.h"
#include "config.h"
#include "storage.h"
#include "setup_ap.h"
#include "log_serial.h"

#include <Arduino.h>

namespace button {

// [host-test begin]
Gesture classify(uint32_t pressed_at_ms, uint32_t held_ms) {
  if (held_ms < BUTTON_HOLD_MS) return Gesture::None;
  // A press that started inside the window after reset is the erase gesture;
  // one that started later is the Wi-Fi reset. Classified by when it went
  // DOWN, so holding through the end of the window still erases.
  return pressed_at_ms < ERASE_WINDOW_MS ? Gesture::FactoryErase : Gesture::WifiReset;
}
// [host-test end]

static inline bool pressed() { return digitalRead(PIN_BOOT_BUTTON) == LOW; }

static inline void led_write(bool on) {
#if LED_ACTIVE_HIGH
  digitalWrite(PIN_STATUS_LED, on ? HIGH : LOW);
#else
  digitalWrite(PIN_STATUS_LED, on ? LOW : HIGH);
#endif
}

void begin() {
  pinMode(PIN_BOOT_BUTTON, INPUT_PULLUP);
}

void boot_window(void (*show)(const char *, const char *)) {
  pinMode(PIN_STATUS_LED, OUTPUT);
  uint32_t now = millis();
  if (now >= ERASE_WINDOW_MS && !pressed()) return;   // slow boot: window already over
  // LED steady ON for the whole window (and while BOOT is held): "you can
  // press BOOT now". It goes out when the window ends or the erase starts.
  led_write(true);
  LOG_PRINTF("[reset] erase window: hold BOOT %u s within %u s of reset to erase the device\n",
             (unsigned)(BUTTON_HOLD_MS / 1000), (unsigned)(ERASE_WINDOW_MS / 1000));
  if (show) show("Hold BOOT 3 s", "to erase device");

  // If the button is already down here it went down after reset (held AT
  // reset, the chip would be in the ROM bootloader, not running this), so it
  // counts from the moment it is first seen.
  uint32_t down_since = 0;
  for (;;) {
    now = millis();
    if (pressed()) {
      if (down_since == 0) {
        down_since = now;
        LOG_PRINTLN("[reset] BOOT held - keep holding to erase, release to cancel");
        if (show) show("Keep holding...", "release = cancel");
      }
      if (classify(down_since, now - down_since) == Gesture::FactoryErase) {
        led_write(false);   // LED goes out: the erase has started
        LOG_PRINTLN("[reset] factory erase by button - wiping log + NVS, rebooting");
        if (show) show("Erasing...", "rebooting");
        // Same pair as the app's "Erase device data" path in loop().
        storage::clear_log();
        storage::erase_all_nvs();
        delay(300);   // let the log line flush
        ESP.restart();
      }
    } else {
      if (down_since != 0) {
        LOG_PRINTLN("[reset] BOOT released early - erase cancelled");
        down_since = 0;
        if (show) show("Erase cancelled", "booting...");
      }
      if (now >= ERASE_WINDOW_MS) break;
    }
    delay(20);
  }
  led_write(false);
  if (show) show("Solar Monitor", "booting...");
}

void poll() {
  static uint32_t down_since = 0;
  static bool fired = false;
  uint32_t now = millis();
  if (!pressed()) {
    down_since = 0;
    fired = false;
    return;
  }
  if (down_since == 0) down_since = now;
  if (fired) return;   // one action per press, however long it is held
  if (classify(down_since, now - down_since) != Gesture::WifiReset) return;
  fired = true;
  if (setup_ap::active()) {
    LOG_PRINTLN("[setup] BOOT held - closing the setup hotspot");
    setup_ap::request_stop();
  } else {
    // Erased right away (NVS only); the hotspot itself is brought up by the
    // connectivity task, which owns the radio.
    storage::clear_wifi_creds();
    LOG_PRINTLN("[setup] BOOT held - Wi-Fi erased, opening the setup hotspot");
    setup_ap::request_start();
  }
}

}  // namespace button
