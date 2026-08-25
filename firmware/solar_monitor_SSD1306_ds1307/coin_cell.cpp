#include "coin_cell.h"
#include "config.h"

#include <Arduino.h>

namespace coin_cell {

void begin() {
  // 11 dB attenuation gives a usable input span of roughly 0–3.1 V at the pin.
  // A CR2032 stays within that span, so it connects directly with no divider.
  analogSetPinAttenuation(PIN_COIN_CELL_SENSE, ADC_11db);
}

float read_volts() {
  // Average several samples to smooth ADC noise. analogReadMilliVolts()
  // applies the chip's eFuse calibration for us, so no manual esp_adc_cal
  // handling is needed on the Arduino framework.
  uint32_t mv = 0;
  for (int i = 0; i < COIN_CELL_SAMPLES; ++i) {
    mv += analogReadMilliVolts(PIN_COIN_CELL_SENSE);
  }
  return (mv / (float)COIN_CELL_SAMPLES) / 1000.0f;
}

}  // namespace coin_cell
