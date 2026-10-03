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
cut = lambda s, a, b: s[s.index(a):s.index(b)]
open(out, 'w').write("\n".join([
    cut(ts, 'static int64_t days_from_civil', 'bool set_wall_clock'),
    cut(ts, 'time_t parse_iso8601(const char *s) {', 'String iso8601_now()'),
    cut(st, 'static bool parse_row(', '// Strip a trailing partial line'),
    cut(st, 'int format_row(', 'bool append_next_row('),
]))
PY
  cp "$HERE/host_test.cpp" "$WORK/t.cpp"
  g++ -std=gnu++20 -Wall -Wextra -Werror -o "$WORK/t" "$WORK/t.cpp"
  echo "== $v"
  "$WORK/t" || status=1
done
[ $status -eq 0 ] && echo "== firmware: ALL PASSED" || echo "== firmware: FAILURES"
exit $status
