#include "battery.h"
#include "config.h"

#include <Arduino.h>

namespace battery {

void begin() {
  // 11 dB attenuation gives a usable input span of roughly 0–3.1 V at the pin.
  // Size the external divider so the highest battery voltage you expect lands
  // comfortably inside that span (and never exceeds 3.3 V at the pin).
  analogSetPinAttenuation(PIN_BATTERY_SENSE, ADC_11db);
}

float read_volts() {
  // Average several samples to smooth ADC noise. analogReadMilliVolts()
  // applies the chip's eFuse calibration for us, so no manual esp_adc_cal
  // handling is needed on the Arduino framework.
  uint32_t mv = 0;
  for (int i = 0; i < BATTERY_SAMPLES; ++i) {
    mv += analogReadMilliVolts(PIN_BATTERY_SENSE);
  }
  float pin_v = (mv / (float)BATTERY_SAMPLES) / 1000.0f;
  return pin_v * BATTERY_DIVIDER_RATIO;
}

}  // namespace battery
