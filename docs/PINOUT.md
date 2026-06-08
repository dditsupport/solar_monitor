# Pinout — ESP32 DevKit V1 (30-pin)

Orientation: USB connector at the **bottom**, ESP32 module facing you.

## Definitive pin map

| Peripheral pin | ESP32 GPIO | Silkscreen | Side | Position (from top) |
|---|---|---|---|---|
| **PZEM-004T v3.0** | | | | |
| TX | GPIO 16 (RX2) | **D16** | Right | 11 |
| RX | GPIO 17 (TX2) | **D17** | Right | 10 |
| 5V | 5V rail | **VIN** | Bottom-right corner (varies) | — |
| GND | GND | **GND** | Any GND pin | — |
| **SSD1306 OLED (SPI 7-pin)** | | | | |
| VCC | 3V3 rail | **3V3** | Left | 1 (top-left) |
| GND | GND | **GND** | Any GND pin | — |
| D0 / SCK / CLK | GPIO 18 | **D18** | Right | 8 |
| D1 / MOSI / SDA | GPIO 23 | **D23** | Right | 2 |
| RES / RST | GPIO 19 | **D19** | Right | 7 |
| DC | GPIO 4 | **D4** | Right | 13 |
| CS | GPIO 5 | **D5** | Right | 9 |
| **DS3231 RTC (I²C)** | | | | |
| VCC | 3V3 rail | **3V3** | Left | 1 (share with OLED) |
| GND | GND | **GND** | Any GND pin | — |
| SDA | GPIO 21 | **D21** | Right | 6 |
| SCL | GPIO 22 | **D22** | Right | 3 |
| SQW, 32K | — | — | leave disconnected | — |

All signal pins live on the **right column** of the board, so wiring stays
clean.

## Visual pin reference (board orientation: USB at bottom)

```
        Left column                          Right column
          (1) 3V3  ────────────────────────  GND  (1)
          (2) EN                              D23  (2)  ← OLED MOSI/D1
          (3) VP (GPIO 36)                    D22  (3)  ← DS3231 SCL
          (4) VN (GPIO 39)                    TX0  (4)  (USB serial)
          (5) D34                             RX0  (5)  (USB serial)
          (6) D35                             D21  (6)  ← DS3231 SDA
          (7) D32                             GND  (7)  ← OLED RST (D19) - see right (7)
          (8) D33                             D19  (7)  ← OLED RST
          (9) D25                             D18  (8)  ← OLED SCK/D0
         (10) D26                             D5   (9)  ← OLED CS
         (11) D27                             D17 (10)  ← PZEM RX
         (12) D14                             D16 (11)  ← PZEM TX
         (13) D12                             D4  (12)  ← OLED DC
         (14) GND                             D0  (13)  (BOOT button)
         (15) D13                             D2  (14)  (on-board LED, free)
                                              D15 (15)
                              [USB-B]
```

(Position numbers approximate to the user's 30-pin DevKit; some board
variants swap one or two positions. The silkscreen labels are the ground
truth.)

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
