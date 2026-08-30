#include "display.h"
#include "config.h"
#include "identity.h"
#include "time_source.h"

#include <U8g2lib.h>
#include <time.h>

namespace display {

// SSD1306 128x64, SPI, full buffer. Using SW SPI because MOSI lives on
// GPIO 22 (non-default for VSPI), so hardware SPI can't drive this pin set.
// SW SPI bit-bangs cleanly at >1 MHz on a 240 MHz ESP32, which is plenty
// for refreshing this 1 KB framebuffer at 1 Hz.
static U8G2_SSD1306_128X64_NONAME_F_4W_SW_SPI s_oled(
    U8G2_R0, PIN_OLED_SCK, PIN_OLED_MOSI, PIN_OLED_CS, PIN_OLED_DC, PIN_OLED_RST);

void begin() {
  s_oled.begin();
  s_oled.setFontMode(1);
}

void splash(const char *line1, const char *line2) {
  s_oled.clearBuffer();
  s_oled.setFont(u8g2_font_ncenB10_tr);
  s_oled.drawStr(0, 14, line1 ? line1 : "");
  s_oled.setFont(u8g2_font_6x10_tr);
  if (line2) s_oled.drawStr(0, 30, line2);
  s_oled.sendBuffer();
}

static void draw_wifi_icon(int x, int y, WifiStatus st, uint32_t blink_phase) {
  // 12 x 10 area.
  bool solid = (st == WIFI_CONNECTED);
  bool blink = (st == WIFI_SYNCING) && (blink_phase & 1);
  if (st == WIFI_IDLE) return;
  s_oled.drawCircle(x + 6, y + 8, 2);
  for (int r = 4; r <= 8; r += 2) {
    if (solid || (st == WIFI_CONNECTING) || (blink)) {
      s_oled.drawCircle(x + 6, y + 8, r);
    }
  }
  if (solid && !blink) s_oled.drawDisc(x + 6, y + 8, 1);
}

static void draw_ble_icon(int x, int y, BleStatus st) {
  if (st == BLE_OFF) return;
  // Stylized B: two triangles meeting on a vertical axis.
  bool solid = (st == BLE_CLIENT_CONNECTED);
  for (int dy = -5; dy <= 5; ++dy) {
    int w = 4 - abs(dy) / 2;
    if (solid) s_oled.drawHLine(x + 6 - w, y + 5 + dy, w * 2);
    else if (dy == -5 || dy == 5 || abs(dy) == 0) s_oled.drawHLine(x + 6 - w, y + 5 + dy, w * 2);
  }
  s_oled.drawVLine(x + 6, y, 11);
}

// Font ladders, widest first, for draw_fitted().
static const uint8_t *const kFaultFonts[] = {
    u8g2_font_ncenB14_tr, u8g2_font_ncenB12_tr,
    u8g2_font_ncenB10_tr, u8g2_font_6x10_tr};
static const uint8_t *const kEnergyFonts[] = {
    u8g2_font_helvB14_tr, u8g2_font_helvB12_tr, u8g2_font_6x10_tr};

// Draw [text] centred on baseline [y], stepping down [fonts] until it fits
// the 128 px width.
//
// Centring an over-wide string is not a harmless overflow: (128 - w) / 2 goes
// negative and u8g2 happily draws from there, so the leading glyphs fall off
// the left edge -- which is how "PZEM ERROR" rendered as "ZEM ERROR" on a real
// panel. If even the last font overflows, fall back to x=0 so the string is
// clipped only at the end, where a reader can still tell what it says.
static void draw_fitted(const char *text, int y,
                        const uint8_t *const *fonts, size_t n) {
  int w = 0;
  for (size_t i = 0; i < n; ++i) {
    s_oled.setFont(fonts[i]);
    w = s_oled.getStrWidth(text);
    if (w <= 128) break;
  }
  s_oled.drawStr(w <= 128 ? (128 - w) / 2 : 0, y, text);
}

void render(const SharedState &s) {
  static uint32_t frame = 0;
  frame++;

  s_oled.clearBuffer();

  // ---- Fault overlays take priority ----
  const char *fault = nullptr;
  if (s.buffer_full) fault = "BUFFER FULL";
  else if (s.pzem_status == PZEM_STALE) fault = "PZEM ERROR";
  else if (s.pzem_status == PZEM_SENSOR_FAULT) fault = "SENSOR?";

  if (fault) {
    draw_fitted(fault, 30, kFaultFonts, sizeof(kFaultFonts) / sizeof(kFaultFonts[0]));
    s_oled.setFont(u8g2_font_6x10_tr);
    s_oled.drawStr(0, 50, identity::device_id().c_str());
    s_oled.sendBuffer();
    return;
  }

  // Layout (USB at bottom, top-down):
  //   y=12   "1847 W"          (helvB12) | "30-08 | 17:24" right (5x7)
  //   y=24   "230.1V  8.03A"   (6x10)
  //   y=46   "0.05/0.22 kWh"   (helvB14, centred) - today/total, the headline
  //          figure, given the vertical space of what used to be two 6x10 rows
  //   y=63   WiFi icon, BLE icon, "Today" (6x10), "Q:0" right

  char buf[32];

  // ---- Top row: Power (left) + DD-MM | HH:MM (right) ----
  s_oled.setFont(u8g2_font_helvB12_tr);
  snprintf(buf, sizeof(buf), "%.0f W", s.latest.power);
  s_oled.drawStr(0, 12, buf);

  if (s.wall_clock_known) {
    time_t now = time_source::wall_time();
    if (now > 0) {
      struct tm lt;
      localtime_r(&now, &lt);
      char tbuf[20];
      // DD-MM | HH:MM. tm_mon is 0-based. 5x7 rather than 6x10 so the wider
      // stamp still leaves the power reading room to grow past "9999 W".
      snprintf(tbuf, sizeof(tbuf), "%02d-%02d | %02d:%02d",
               lt.tm_mday, lt.tm_mon + 1, lt.tm_hour, lt.tm_min);
      s_oled.setFont(u8g2_font_5x7_tr);
      int tw = s_oled.getStrWidth(tbuf);
      s_oled.drawStr(128 - tw, 9, tbuf);
    }
  }

  // ---- V and I row ----
  s_oled.setFont(u8g2_font_6x10_tr);
  snprintf(buf, sizeof(buf), "%.1fV  %.2fA", s.latest.voltage, s.latest.current);
  s_oled.drawStr(0, 24, buf);

  // ---- Energy row: today (or session) / lifetime total ----
  // One large-font line across the band the two 6x10 rows used to occupy, so
  // the figure that matters reads from across the room. The label moves to the
  // status row: it is the same every frame, the numbers are not.
  snprintf(buf, sizeof(buf), "%.2f/%.2f kWh",
           s.wall_clock_known ? s.today_kwh : s.session_kwh, s.total_kwh);
  // Steps the font down rather than overflow 128 px: a five-figure lifetime
  // total ("12345.67/98765.43 kWh") reaches that at the largest size.
  draw_fitted(buf, 46, kEnergyFonts, sizeof(kEnergyFonts) / sizeof(kEnergyFonts[0]));

  // ---- Status row (bottom) ----
  draw_wifi_icon(0, 54, s.wifi_status, frame);
  draw_ble_icon(20, 54, s.ble_status);
  // Names the first figure above; the second is the lifetime total either way.
  // '*' marks a day total whose midnight anchor was not captured cleanly, so
  // the reading understates -- the same caveat the old "Today*:" row carried.
  s_oled.setFont(u8g2_font_6x10_tr);
  s_oled.drawStr(38, 63, !s.wall_clock_known ? "Session"
                         : (s.today_is_partial ? "Today*" : "Today"));
  snprintf(buf, sizeof(buf), "Q:%lu", (unsigned long)s.unsynced_count);
  int sw = s_oled.getStrWidth(buf);
  s_oled.drawStr(128 - sw, 63, buf);

  s_oled.sendBuffer();
}

bool tick() {
  SharedState snap;
  if (!state_snapshot(snap)) return false;
  render(snap);
  return true;
}

}  // namespace display
