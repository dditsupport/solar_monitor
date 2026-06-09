# Pinout — ESP32 DevKit V1 (30-pin)

Orientation: USB connector at the **bottom**, ESP32 module facing you.

## Confirmed hardware

The specific device this project is built around (verified via
`esptool.py chip_id`):

| Property | Value |
|---|---|
| Chip | ESP32-D0WDQ5 |
| Silicon revision | 3 (latest stable) |
| Cores | 2 (Xtensa LX6) |
| CPU clock | 240 MHz |
| Flash | 4 MB @ 80 MHz |
| Free heap at boot | ~337 KB |
| MAC | `8C:94:DF:6D:A9:78` |
| Derived `device_id` | `solar-6da978` |
| Derived BLE advert name | `Solar-6DA978` |

The 4 MB flash matches the firmware's partition scheme (`Default 4MB
with spiffs: 1.2 MB APP / 1.5 MB SPIFFS`). The dual-core configuration
is what `solar_monitor.ino` pins its two FreeRTOS tasks to.

## Definitive pin map

The **Silkscreen** column is the label printed on the DevKit board next
to the pin — that's what you actually look at when soldering. Labels
below match the user's specific 30-pin board (chip ESP32-D0WDQ5 rev 3,
4 MB flash, MAC `8C:94:DF:6D:A9:78`).

| Peripheral pin | ESP32 GPIO | Silkscreen | Side |
|---|---|---|---|
| **PZEM-004T v3.0** | | | |
| TX | GPIO 16 | **RX2** | Right |
| RX | GPIO 17 | **TX2** | Right |
| 5V | 5V rail | **VIN** | Bottom-left |
| GND | GND | **GND** | bottom of either column |
| **SSD1306 OLED (SPI 7-pin)** | | | |
| VCC | 3V3 rail | **3V3** | Bottom-right |
| GND | GND | **GND** | bottom of either column |
| D0 / SCK / CLK | GPIO 18 | **D18** | Right |
| D1 / MOSI / SDA | GPIO 23 | **D23** | Right (top) |
| RES / RST | GPIO 19 | **D19** | Right |
| DC | GPIO 4 | **D4** | Right |
| CS | GPIO 5 | **D5** | Right |
| **DS3231 RTC (I²C)** | | | |
| VCC | 3V3 rail | **3V3** | Bottom-right (share with OLED) |
| GND | GND | **GND** | bottom of either column |
| SDA | GPIO 21 | **D21** | Right |
| SCL | GPIO 22 | **D22** | Right |
| SQW, 32K | — | — | leave disconnected |

All signal pins live on the **right column** of the board, so wiring stays
clean.

## Visual pin reference (board orientation: USB at bottom)

Layout matches the user's specific 30-pin DevKit (clearer photo of this
board is in the project history). Trust the silkscreen label, not the
position number — variants exist.

```
              Left column                   Right column
              ───────────                   ────────────
          1   EN                            D23   ← OLED MOSI / D1
          2   VP (GPIO 36)                  D22   ← DS3231 SCL
          3   VN (GPIO 39)                  TX0   (USB serial)
          4   D34                           RX0   (USB serial)
          5   D35                           D21   ← DS3231 SDA
          6   D32                           D19   ← OLED RST
          7   D33                           D18   ← OLED SCK / D0
          8   D25                           D5    ← OLED CS
          9   D26                           TX2   ← PZEM RX (GPIO 17)
         10   D27                           RX2   ← PZEM TX (GPIO 16)
         11   D14                           D4    ← OLED DC
         12   D12                           D2    (on-board LED — free)
         13   D13                           D15
         14   GND                           GND
         15   VIN  ← HLK-PM01 5V output     3V3   ← OLED & DS3231 VCC
                              ┌─────────┐
                              │  USB-B  │
                              └─────────┘
```

Pins not labeled with `←` are unused by this project and free for future
expansion.

## Power & ground

- The HLK-PM01 (mains → 5 V) feeds the ESP32 `VIN`.
- The PZEM logic side runs from the same 5 V rail (PZEM does NOT accept 3.3 V).
- The OLED VCC and DS3231 VCC both run from **3V3** (top-left pin).
- **Common ground is mandatory**: PZEM 5 V GND, OLED GND, DS3231 GND, and
  ESP32 GND must share a single rail. Without it, UART and I²C traffic is
  unreliable.

## UART / SPI / I²C assignments

- `UART2` (default ESP32 pins, GPIO 16/17) → PZEM.
- `VSPI` (default HW SPI: GPIO 18 SCK, 23 MOSI, 5 CS) → OLED. DC on GPIO 4,
  RST on GPIO 19.
- `Wire` (default ESP32 I²C: GPIO 21 SDA, GPIO 22 SCL) → DS3231 at 400 kHz.
  Most DS3231 breakout boards include on-board 4.7 kΩ pull-ups on SDA/SCL;
  add external pull-ups (4.7 kΩ to 3V3) if your module doesn't.

## Notes & strapping-pin safety

- **GPIO 2** is intentionally **not used** — it's a boot strapping pin (must
  be LOW or floating at power-on) and is wired to the on-board LED on most
  DevKit boards. Leaving it free preserves both the strap behavior and the
  LED for future status indication.
- **GPIO 0** (BOOT button) and **GPIO 12** are also not used (both are
  strapping pins with boot constraints).
- **GPIO 5** (OLED CS) is a strapping pin that must be HIGH at boot — SPI CS
  idles HIGH naturally, so this is safe.
- **GPIO 15** is reserved/unused (strapping pin).

## Free pins for future expansion

If you add a push-button, status LED, second sensor, etc., these are clean
choices that don't conflict with anything above:

- Outputs / general I/O: **GPIO 13, 14, 25, 26, 27, 32, 33**
- Input-only (sensors only, no output drive): **GPIO 34, 35, 36 (VP), 39 (VN)**
- On-board LED for status: **GPIO 2** (now free after RST move)
- BOOT button (already debounced on board): **GPIO 0**
