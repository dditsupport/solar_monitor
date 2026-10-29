#!/usr/bin/env bash
# Firmware pure-logic tests on the host (needs g++ and python3; no ESP32
# toolchain). Extracts the functions straight from each variant's source, so
# it tests exactly what gets flashed.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
status=0
for v in solar_monitor_SSD1306_ds3231 solar_monitor_SSD1306_ds1307 solar_monitor_ds1307; do
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
]))
PY
  cp "$HERE/host_test.cpp" "$WORK/t.cpp"
  g++ -std=gnu++20 -Wall -Wextra -Werror -o "$WORK/t" "$WORK/t.cpp"
  echo "== $v"
  "$WORK/t" || status=1
done
# The setup hotspot's page in a phone-sized browser (node + playwright; skipped
# without them). One page is shared by every variant; check it is.
PAGE="$ROOT/firmware/solar_monitor_SSD1306_ds3231/setup_ap_page.h"
for v in solar_monitor_SSD1306_ds1307 solar_monitor_ds1307; do
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
