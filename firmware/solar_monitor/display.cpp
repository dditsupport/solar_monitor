#include "display.h"
#include "config.h"
#include "identity.h"

#include <U8g2lib.h>

namespace display {

// SSD1306 128x64, SPI, full buffer. Using SW SPI because MOSI lives on
// GPIO 21 (non-default for VSPI) after the DS3231 took GPIO 23 for I2C SCL.
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
    s_oled.setFont(u8g2_font_ncenB14_tr);
    int w = s_oled.getStrWidth(fault);
    s_oled.drawStr((128 - w) / 2, 30, fault);
    s_oled.setFont(u8g2_font_6x10_tr);
    s_oled.drawStr(0, 50, identity::device_id().c_str());
    s_oled.sendBuffer();
    return;
  }

  // ---- Top-right: wall-clock time as DD-HH:MM (local TZ) ----
  // Only shown when wall_clock_known; positioned to not collide with the
  // big power number even at the 3.3 kW peak (max ~80 px wide on left).
  if (s.wall_clock_known) {
    time_t now = time_source::wall_time();
    if (now > 0) {
      struct tm lt;
      localtime_r(&now, &lt);
      char tbuf[16];
      snprintf(tbuf, sizeof(tbuf), "%02d-%02d:%02d",
               lt.tm_mday, lt.tm_hour, lt.tm_min);
      s_oled.setFont(u8g2_font_6x10_tr);
      int tw = s_oled.getStrWidth(tbuf);
      s_oled.drawStr(128 - tw, 8, tbuf);
    }
  }

  // ---- Power (big) ----
  // The numeric-only font (logisoso24_tn) can't draw 'W'; render number, then
  // tack the unit on with a smaller proportional font.
  char buf[32];
  s_oled.setFont(u8g2_font_logisoso24_tn);
  snprintf(buf, sizeof(buf), "%.0f", s.latest.power);
  s_oled.drawStr(0, 24, buf);
  int num_w = s_oled.getStrWidth(buf);
  s_oled.setFont(u8g2_font_6x12_tr);
  s_oled.drawStr(num_w + 4, 22, "W");

  // ---- V and I row ----
  s_oled.setFont(u8g2_font_6x10_tr);
  snprintf(buf, sizeof(buf), "%.1fV  %.2fA", s.latest.voltage, s.latest.current);
  s_oled.drawStr(0, 40, buf);

  // ---- Energy row ----
  if (s.wall_clock_known && !s.today_is_partial) {
    snprintf(buf, sizeof(buf), "Today: %.2f kWh", s.today_kwh);
  } else if (s.wall_clock_known) {
    snprintf(buf, sizeof(buf), "Today*: %.2f kWh", s.today_kwh);
  } else {
    snprintf(buf, sizeof(buf), "Session: %.2f kWh", s.session_kwh);
  }
  s_oled.drawStr(0, 52, buf);

  // ---- Status row ----
  draw_wifi_icon(0, 54, s.wifi_status, frame);
  draw_ble_icon(20, 54, s.ble_status);

  // Right side: unsynced count
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
