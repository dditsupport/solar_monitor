#pragma once

// RTC backup coin-cell (CR2032) voltage sense on an ADC1 input-only pin
// (GPIO35, see config.h). This is the ~3 V cell that keeps the DS3231/DS1307
// running across mains loss — NOT the solar/main battery.
//
// ADC1 is chosen deliberately: ADC2 is shared with the Wi-Fi radio, so an
// ADC2 read can fail or return garbage whenever the radio is active. ADC1 is
// always readable. GPIO35 is input-only, so it can't drive anything and makes
// a clean, dedicated sense line — the GPIO-capable pins stay free for other
// peripherals.
//
// A CR2032 tops out around 3.0–3.3 V, comfortably inside the ADC input span at
// 11 dB attenuation, so it wires straight to the pin with NO divider. Tap the
// coin cell's + terminal (equivalently the RTC module's VBAT node): when mains
// power is present the RTC runs from VCC and the VBAT node sits at the cell's
// own voltage, so the reading tracks cell health and gives early warning before
// the DS3231's lost-power flag ever trips.
//
// read_volts() uses analogReadMilliVolts(), which applies the chip's factory
// eFuse calibration automatically on the Arduino framework (no manual
// esp_adc_cal handling needed).

namespace coin_cell {

// Configure the ADC pin attenuation. Call once from setup().
void begin();

// Read the coin-cell voltage in volts, averaged over COIN_CELL_SAMPLES ADC
// samples. Wired directly (no divider), so this is the voltage at the cell.
float read_volts();

}  // namespace coin_cell
