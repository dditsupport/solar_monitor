# Pinout — ESP32-WROOM-32D DevKit V1 (38-pin)

Orientation: USB connector at the **bottom**, ESP32 module facing you.

## Confirmed hardware

| Property | Value |
|---|---|
| Module | ESP-WROOM-32D (38-pin DevKit V1) |
| Chip | ESP32-D0WDQ5 |
| Silicon revision | 3 (latest stable) |
| Cores | 2 (Xtensa LX6) |
| CPU clock | 240 MHz |
| Flash | 4 MB @ 80 MHz |
| Free heap at boot | ~337 KB |

The MAC address (and therefore the firmware-derived `device_id` and BLE
advertising name `Solar-XXXXXX`) is per-board. Read it from the serial
boot output: the firmware prints `Device ID: solar-xxxxxx` right after
storage init. Alternatively, `esptool.py chip_id` reads it too.

## Firmware variants

Three sketch folders share this board. They differ only in whether an OLED is
fitted and which RTC chip is used — **and the I²C pins are not the same across
them**, so check the column for the build you are flashing.

| Variant | Sketch folder | OLED | RTC | I²C pins | I²C speed |
|---|---|---|---|---|---|
| OLED + DS3231 | `firmware/solar_monitor_SSD1306_ds3231/` | yes | DS3231 | SDA 4, SCL 13 | 400 kHz |
| OLED + DS1307 | `firmware/solar_monitor_SSD1306_ds1307/` | yes | DS1307 | SDA 4, SCL 13 | 100 kHz |
| Headless + DS1307 | `firmware/solar_monitor_ds1307/` | no | DS1307 | SDA 22, SCL 23 | 100 kHz |

The two OLED builds are wired **identically** — swapping between them is a
reflash, not a rewiring. The headless build moves I²C onto GPIO 22/23, which the
OLED builds need for SPI, so it is *not* pin-compatible with them.

## Definitive pin map

The **Silkscreen** column is the label printed on the DevKit board next
to the pin — that's what you actually look at when soldering. Labels
below match the 38-pin ESP-WROOM-32D DevKit V1.

These values are taken from each variant's `config.h`, which is the only
authority — if this table and `config.h` ever disagree, `config.h` wins.

### Common to every variant

| Peripheral pin | ESP32 GPIO | Silkscreen | Side |
|---|---|---|---|
| **PZEM-004T v3.0** | | | |
| TX | GPIO 16 | **16** | Right |
| RX | GPIO 17 | **17** | Right |
| 5V | 5V rail | **VIN** (or **5V**) | Bottom-left |
| GND | GND | **GND** | several positions |
| **RTC coin-cell sense (ADC1)** | | | |
| Coin cell + (VBAT node) | GPIO 35 | **35** | Left |
| **Status LED** | | | |
| Wi-Fi activity | GPIO 2 | **2** | Right (on-board LED) |

### OLED builds — `solar_monitor_SSD1306_ds3231` and `solar_monitor_SSD1306_ds1307`

| Peripheral pin | ESP32 GPIO | Silkscreen | Side |
|---|---|---|---|
| **SSD1306 OLED (SPI 7-pin)** | | | |
| VCC | 3V3 rail | **3V3** | Top-left |
| GND | GND | **GND** | several positions |
| D0 / SCK / CLK | GPIO 23 | **23** | Right (near top) |
| D1 / MOSI / SDA | GPIO 22 | **22** | Right |
| RES / RST | GPIO 21 | **21** | Right |
| DC | GPIO 19 | **19** | Right |
| CS | GPIO 18 | **18** | Right |
| **RTC (I²C)** — DS3231 or DS1307 | | | |
| VCC | 3V3 rail | **3V3** | Top-left (share with OLED) |
| GND | GND | **GND** | several positions |
| SDA | GPIO 4 | **4** | Right |
| SCL | GPIO 13 | **13** | Left |
| SQW, 32K | — | — | leave disconnected |

The OLED is driven by **bit-banged software SPI** (see `display.cpp`), not VSPI,
which is why it can sit on this non-default pin set.

### Headless build — `solar_monitor_ds1307`

No display is fitted, so the five SSD1306 SPI pins are left unwired and I²C
takes the freed GPIO 22/23:

| Peripheral pin | ESP32 GPIO | Silkscreen | Side |
|---|---|---|---|
| **DS1307 RTC (I²C)** | | | |
| VCC | 3V3 rail | **3V3** | Top-left |
| GND | GND | **GND** | several positions |
| SDA | GPIO 22 | **22** | Right |
| SCL | GPIO 23 | **23** | Right (near top) |

### Coin-cell sense

RTC coin-cell sense uses **GPIO 35**, an input-only pin on **ADC1**. ADC1 (not
ADC2) is required because the Wi-Fi radio reserves ADC2. This senses the RTC's
**CR2032 backup coin cell** (~3 V) — *not* the solar/main battery — to give
early warning before the RTC loses time. A CR2032 tops out around 3.0–3.3 V,
inside the ADC span at 11 dB attenuation, so it wires **straight to the pin with
no divider**: run a wire from the coin cell's **+** terminal (equivalently the
RTC module's VBAT node) to GPIO 35; the cell's − side already shares the common
ground. This is the one signal that is the same GPIO on **every** variant.

> Note: some DS3231 breakout boards (the common "ZS-042") include a trickle
> charge circuit that slowly overcharges a non-rechargeable CR2032. If yours has
> it, remove the charging diode/resistor as usual — unrelated to this sense tap.

Most signal pins live on the **right column** of the board, so wiring stays
clean; the coin-cell tap is the one exception on the left column.

## Visual pin reference (board orientation: USB at bottom)

Layout matches the 38-pin ESP-WROOM-32D DevKit V1. Trust the silkscreen
label, not the position number — variants exist.

Arrows below show the **OLED builds**; where the headless build differs it is
marked `[headless]`.

```
              Left column                   Right column
              ───────────                   ────────────
          1   3V3   ← OLED & RTC VCC        GND
          2   EN                            23    ← OLED SCK / D0   [headless: RTC SCL]
          3   VP   (GPIO 36)                22    ← OLED MOSI / D1  [headless: RTC SDA]
          4   VN   (GPIO 39)                TX    (USB serial, GPIO 1)
          5   34                            RX    (USB serial, GPIO 3)
          6   35    ← coin-cell sense       21    ← OLED RST
          7   32                            GND
          8   33                            19    ← OLED DC
          9   25                            18    ← OLED CS
         10   26                            5     (free — strapping pin)
         11   27                            17    ← PZEM RX
         12   14                            16    ← PZEM TX
         13   12                            4     ← RTC SDA
         14   GND                           0     (BOOT button — free)
         15   13    ← RTC SCL               2     ← status LED (on-board)
         16   D2    (SD flash, unusable)    15    (free — strapping pin)
         17   D3    (SD flash, unusable)    D1    (SD flash, unusable)
         18   CMD   (SD flash, unusable)    D0    (SD flash, unusable)
         19   5V / VIN  ← HLK-PM01 input    CLK   (SD flash, unusable)
                              ┌─────────┐
                              │  USB-B  │
                              └─────────┘
```

Pins not labeled with `←` are unused by this project and free for future
expansion. The six `SD flash` pins along the bottom are connected to the
on-module SPI flash and cannot be used for general I/O.

## Power & ground

- The HLK-PM01 (mains → 5 V) feeds the ESP32 `VIN`.
- The PZEM logic side runs from the same 5 V rail (PZEM does NOT accept 3.3 V).
- The OLED VCC and RTC VCC both run from **3V3** (top-left pin).
- **Common ground is mandatory**: PZEM 5 V GND, OLED GND, RTC GND, and
  ESP32 GND must share a single rail. Without it, UART and I²C traffic is
  unreliable.

## UART / SPI / I²C assignments

- `UART2` (default ESP32 pins, GPIO 16/17) → PZEM.
- **Software (bit-banged) SPI** → OLED, on GPIO 23 SCK, 22 MOSI, 18 CS, 19 DC,
  21 RST. This is *not* VSPI: MOSI sits on GPIO 22, which hardware SPI can't
  drive, so `display.cpp` bit-bangs it. Any output-capable GPIO would work.
- `Wire` → RTC. **GPIO 4 SDA / GPIO 13 SCL on the OLED builds**, GPIO 22 SDA /
  GPIO 23 SCL on the headless build. Speed is 400 kHz for the DS3231 and
  100 kHz for the DS1307 (standard mode only — do not run a DS1307 at 400 kHz).
  Most RTC breakout boards include on-board 4.7 kΩ pull-ups on SDA/SCL; add
  external pull-ups (4.7 kΩ to 3V3) if your module doesn't.

## Notes & strapping-pin safety

- **GPIO 2** drives the on-board status LED (`PIN_STATUS_LED`). It is also a
  strapping pin that must be LOW or floating at power-on; the LED circuit does
  not hold it HIGH, so this is safe.
- **GPIO 5** is a strapping pin that must be HIGH at boot. It is **free** in the
  current pin map — OLED CS was moved off it to GPIO 18.
- **GPIO 0** (BOOT button) and **GPIO 12** are not used — both are strapping
  pins with boot constraints.
- **GPIO 34, 35, 36, 39** are input-only: they can sense but never drive.
  GPIO 35 carries the coin-cell tap.

## Free pins for future expansion

If you add a push-button, status LED, second sensor, etc., these are clean
choices that don't conflict with anything above:

- Outputs / general I/O: **GPIO 14, 25, 26, 27, 32, 33**
- Input-only (sensors only, no output drive): **GPIO 34, 36 (VP), 39 (VN)**
  (**GPIO 35** is taken by the RTC coin-cell sense line)
- **GPIO 5**, free since OLED CS moved to GPIO 18 — but it is a strapping pin
  that must be HIGH at boot, so only drive it with something that idles HIGH
- **GPIO 15**, free since RTC SCL moved to GPIO 13 on the OLED builds — but it
  is a strapping pin that idles HIGH via I²C-style pull-ups if you use it for
  another bus, so only drive it with something that idles HIGH
- BOOT button (already debounced on board): **GPIO 0**

**GPIO 2 is not free** — it drives the on-board status LED (`PIN_STATUS_LED`)
on every variant. On the headless build, GPIO 4, 13, and 15 are also free,
since only the OLED builds put I²C there.
