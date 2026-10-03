#!/usr/bin/env bash
# Every automated suite. See docs/TESTING.md for what each covers and needs.
HERE=$(cd "$(dirname "$0")" && pwd)
status=0
"$HERE/backend/run.sh"  || status=1
"$HERE/firmware/run.sh" || status=1
"$HERE/android/run.sh"  || status=1
echo
[ $status -eq 0 ] && echo "ALL SUITES PASSED" || echo "SOME SUITES FAILED"
exit $status
