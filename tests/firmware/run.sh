#!/usr/bin/env bash
# Firmware pure-logic tests on the host (needs g++ and python3; no ESP32
# toolchain). Extracts the functions straight from each variant's source, so
# it tests exactly what gets flashed.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
status=0
# ArduinoJson for scan_json_test.cpp, from the library bundle in the repo.
command -v unzip >/dev/null && unzip -q "$ROOT/firmware/libraries.zip" 'libraries/ArduinoJson/*' -d "$WORK" 2>/dev/null || true
for v in solar_monitor_SSD1306_ds3231 solar_monitor_SSD1306_ds1307 solar_monitor_ds1307 solar_monitor_wifi_test; do
  F="$ROOT/firmware/$v"
  python3 - "$F" "$WORK/extracted.inc" <<'PY'
import sys
F, out = sys.argv[1], sys.argv[2]
ts = open(F + '/time_source.cpp').read()
st = open(F + '/storage.cpp').read()
import re
cut = lambda s, a, b: s[s.index(a):s.index(b)]
blk = lambda f: cut(open(F + '/' + f).read(), '// [host-test begin]', '// [host-test end]')
cfg = open(F + '/config.h').read()
defs = [m.group(0) for m in re.finditer(
    r'^#define (BUTTON_HOLD_MS|ERASE_WINDOW_MS|SETUP_AP_IDLE_SEC|SETUP_AP_DONE_SEC)\s+\d+', cfg, re.M)]
assert len(defs) == 4, defs
open(out, 'w').write("\n".join([
    cut(ts, 'static int64_t days_from_civil', 'bool set_wall_clock'),
    cut(ts, 'time_t parse_iso8601(const char *s) {', 'String iso8601_now()'),
    cut(st, 'static bool parse_row(', '// Strip a trailing partial line'),
    cut(st, 'int format_row(', 'bool append_next_row('),
    # BOOT button gestures and the setup hotspot's stop rule, with the
    # variant's own config values.
    *defs,
    'namespace button {', blk('button.h'), blk('button.cpp'), '}',
    'namespace setup_ap {', blk('setup_ap.h'), blk('setup_ap.cpp'), '}',
    # STA disconnect reason -> log text (wifi_sync.cpp).
    'namespace wifi_sync {', blk('wifi_sync.cpp'), '}',
]))
# run_scan() itself, for scan_json_test.cpp, with the config it reads.
ws = open(F + '/wifi_sync.cpp').read()
a = ws.index('void run_scan() {')
sdefs = [m.group(0) for m in re.finditer(
    r'^#define (MAX_SCAN_RESULTS|SCAN_JSON_MAX_BYTES)\s+\d+', cfg, re.M)]
assert len(sdefs) == 2, sdefs
open(out.replace('extracted.inc', 'scan_extracted.inc'), 'w').write(
    "\n".join(sdefs + [ws[a:ws.index('#if HEAP_TRACE_CYCLE', a)]]))
PY
  cp "$HERE/host_test.cpp" "$WORK/t.cpp"
  g++ -std=gnu++20 -Wall -Wextra -Werror -o "$WORK/t" "$WORK/t.cpp"
  echo "== $v"
  "$WORK/t" || status=1
  # BLE scan JSON from the real run_scan(); needs ArduinoJson (firmware/libraries.zip).
  if [ -d "$WORK/libraries/ArduinoJson/src" ]; then
    cp "$HERE/scan_json_test.cpp" "$WORK/s.cpp"
    g++ -std=gnu++17 -Wall -Wextra -Werror -Wno-deprecated-declarations \
        -I"$WORK/libraries/ArduinoJson/src" -o "$WORK/s" "$WORK/s.cpp"
    "$WORK/s" || status=1
  fi
done
# The Wi-Fi test build is a copy of solar_monitor_ds1307 with fake pzem / rtc /
# coin_cell and its own .ino, config.h (FW_VERSION) and identity.cpp (test-
# prefix). Everything else must stay identical, or the test build would be
# testing old code.
REAL="$ROOT/firmware/solar_monitor_ds1307"; TEST="$ROOT/firmware/solar_monitor_wifi_test"
for f in "$REAL"/*; do
  b=$(basename "$f")
  case "$b" in
    pzem.cpp|rtc.cpp|coin_cell.cpp|config.h|identity.cpp|solar_monitor_ds1307.ino) continue ;;
  esac
  cmp -s "$f" "$TEST/$b" || { echo "FAIL copy $b from solar_monitor_ds1307 into solar_monitor_wifi_test"; status=1; }
done
# config.h may differ only in FW_VERSION.
if [ "$(diff <(grep -v '^#define FW_VERSION' "$REAL/config.h") <(grep -v '^#define FW_VERSION' "$TEST/config.h"))" != "" ]; then
  echo "FAIL solar_monitor_wifi_test/config.h differs from solar_monitor_ds1307's beyond FW_VERSION"; status=1
fi
echo "== wifi test build: in step with solar_monitor_ds1307"

# The setup hotspot's page in a phone-sized browser (node + playwright; skipped
# without them). One page is shared by every variant; check it is.
PAGE="$ROOT/firmware/solar_monitor_SSD1306_ds3231/setup_ap_page.h"
for v in solar_monitor_SSD1306_ds1307 solar_monitor_ds1307 solar_monitor_wifi_test; do
  cmp -s "$PAGE" "$ROOT/firmware/$v/setup_ap_page.h" || { echo "FAIL $v/setup_ap_page.h differs"; status=1; }
done
if command -v node >/dev/null && node -e "require(process.env.PLAYWRIGHT || 'playwright')" 2>/dev/null; then
  echo "== setup hotspot page"
  node "$HERE/portal_test.js" "$PAGE" || status=1
else
  echo "== setup hotspot page: skipped (needs node + playwright; set PLAYWRIGHT to its module path)"
fi
[ $status -eq 0 ] && echo "== firmware: ALL PASSED" || echo "== firmware: FAILURES"
exit $status
