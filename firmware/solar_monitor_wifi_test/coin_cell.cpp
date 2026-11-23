// Wi-Fi test build: no RTC, so no coin cell to measure. 0 V = "not read".
#include "coin_cell.h"

namespace coin_cell {

void begin() {}
float read_volts() { return 0.0f; }

}  // namespace coin_cell
