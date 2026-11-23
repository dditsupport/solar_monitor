// Wi-Fi test build: NO RTC. Reports "not available" everywhere, so the wall
// clock comes from NTP, the server's time or the app (BLE Set Wall Time), and
// wifi_sync skips the RTC drift / coin-cell samples. Same API as rtc.h.
#include "rtc.h"
#include "log_serial.h"

namespace rtc {

bool begin() {
  LOG_PRINTLN("[rtc] none (Wi-Fi test build)");
  return false;
}

bool available() { return false; }
time_t read_epoch() { return 0; }
bool write_epoch(time_t) { return false; }
bool lost_power() { return true; }

}  // namespace rtc
