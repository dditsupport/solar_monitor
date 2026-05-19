# Pinout — ESP32 DevKit V1

| Function | ESP32 GPIO | Peripheral pin |
|---|---|---|
| PZEM TX → ESP32 RX2 | GPIO 16 | PZEM TX |
| PZEM RX ← ESP32 TX2 | GPIO 17 | PZEM RX |
| OLED MOSI (SDA / D1) | GPIO 23 | OLED D1 |
| OLED SCK (D0) | GPIO 18 | OLED D0 |
| OLED CS | GPIO 5 | OLED CS |
| OLED DC | GPIO 4 | OLED DC |
| OLED RST | GPIO 2 | OLED RES |
| OLED VCC | 3.3 V | OLED VCC |
| OLED GND | GND | OLED GND |

## Power & ground

- The HLK-PM01 (mains → 5 V) feeds the ESP32 `VIN`.
- The PZEM logic side runs from the same 5 V rail.
- **Common ground is mandatory**: PZEM 5 V GND and ESP32 GND must share a
  trace. Without it, UART traffic is unreliable and the PZEM can latch up.

## UART / SPI assignments

- `UART2` (default ESP32 pins) is used for PZEM.
- `VSPI` (default HW SPI pins on most DevKit V1 boards) drives the OLED.

## Notes

- GPIO 2 is shared with the on-board LED on some boards. The OLED RST line
  briefly toggles low at boot; if you see the LED flicker that is expected.
- The PZEM-004T v3.0 has a separate AC-side terminal block. AC wiring is
  out of scope for the firmware (see spec §8) — keep the device on a bench
  with a known load through the CT for Stages 1–7 verification.
