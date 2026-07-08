#pragma once

// Battery voltage sense on an ADC1 input-only pin (GPIO35, see config.h).
//
// ADC1 is chosen deliberately: ADC2 is shared with the Wi-Fi radio, so an
// ADC2 read can fail or return garbage whenever the radio is active. ADC1 is
// always readable. GPIO35 is input-only, so it can't drive anything and makes
// a clean, dedicated sense line — the GPIO-capable pins stay free for other
// peripherals.
//
// read_volts() uses analogReadMilliVolts(), which applies the chip's factory
// eFuse calibration automatically on the Arduino framework (no manual
// esp_adc_cal handling needed), then scales by the external divider ratio.

namespace battery {

// Configure the ADC pin attenuation. Call once from setup().
void begin();

// Read the battery voltage in volts, averaged over BATTERY_SAMPLES ADC
// samples and scaled by BATTERY_DIVIDER_RATIO. Returns the voltage at the
// battery terminals (not the raw pin voltage).
float read_volts();

}  // namespace battery
