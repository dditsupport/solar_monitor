# Pinout — ESP32 DevKit V1 (30-pin)

Orientation: USB connector at the **bottom**, ESP32 module facing you.

## Definitive pin map

The **Silkscreen** column is the label printed on the DevKit board next
to the pin — that's what you actually look at when soldering.

| Peripheral pin | ESP32 GPIO | Silkscreen | Side |
|---|---|---|---|
| **PZEM-004T v3.0** | | | |
| TX | GPIO 16 (RX2) | **D16** | Right |
| RX | GPIO 17 (TX2) | **D17** | Right |
| 5V | 5V rail | **VIN** | Right (varies by board) |
| GND | GND | **GND** | any GND pin |
| **SSD1306 OLED (SPI 7-pin)** | | | |
| VCC | 3V3 rail | **3V3** | Left, top |
| GND | GND | **GND** | any GND pin |
| D0 / SCK / CLK | GPIO 18 | **D18** | Right |
| D1 / MOSI / SDA | GPIO 23 | **D23** | Right |
| RES / RST | GPIO 19 | **D19** | Right |
| DC | GPIO 4 | **D4** | Right |
| CS | GPIO 5 | **D5** | Right |
| **DS3231 RTC (I²C)** | | | |
| VCC | 3V3 rail | **3V3** | Left, top (share with OLED) |
| GND | GND | **GND** | any GND pin |
| SDA | GPIO 21 | **D21** | Right |
| SCL | GPIO 22 | **D22** | Right |
| SQW, 32K | — | — | leave disconnected |

All signal pins live on the **right column** of the board, so wiring stays
clean.

## Visual pin reference (board orientation: USB at bottom)

Position numbers count down from the top of each column. Standard 30-pin
DOIT layout, but if your board variant differs in one or two positions,
**trust the silkscreen label, not the position number**.

```
                  Left column            Right column
                  ───────────            ────────────
              1   3V3                    GND
              2   EN                     D23  ← OLED MOSI / D1
              3   VP (GPIO 36)           D22  ← DS3231 SCL
              4   VN (GPIO 39)           TX0  (USB serial)
              5   D34                    RX0  (USB serial)
              6   D35                    D21  ← DS3231 SDA
              7   D32                    GND
              8   D33                    D19  ← OLED RST
              9   D25                    D18  ← OLED SCK / D0
             10   D26                    D5   ← OLED CS
             11   D27                    D17  ← PZEM RX (ESP32 TX2)
             12   D14                    D16  ← PZEM TX (ESP32 RX2)
             13   D12                    D4   ← OLED DC
             14   GND                    D0   (BOOT button — free)
             15   D13                    D2   (on-board LED — free)
                                         D15
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
