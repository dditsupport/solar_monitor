#include "log_serial.h"

namespace log_serial {

static SemaphoreHandle_t s_mtx = nullptr;

void init() {
  if (s_mtx == nullptr) {
    s_mtx = xSemaphoreCreateMutex();
  }
}

Lock::Lock() : taken_(false) {
  // Allow logging before init() runs (e.g. very early setup). In that case
  // we just skip the mutex — the worst that happens is a few interleaved
  // lines before init() is called.
  if (s_mtx == nullptr) return;
  // Wait up to 50 ms; longer than that and we'd rather log out-of-order
  // than risk a deadlock with a paused task.
  if (xSemaphoreTake(s_mtx, pdMS_TO_TICKS(50)) == pdTRUE) {
    taken_ = true;
  }
}

Lock::~Lock() {
  if (taken_) xSemaphoreGive(s_mtx);
}

}  // namespace log_serial
