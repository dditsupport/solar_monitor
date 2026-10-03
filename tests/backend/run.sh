#!/usr/bin/env bash
# Backend + dashboard tests against a throwaway MariaDB and PHP's built-in
# server. Nothing touches the real database.
#
# Needs: mariadb-server (mariadbd, mariadb-install-db), php with pdo_mysql,
# python3. The dashboard suite also needs node + playwright (set PLAYWRIGHT to
# its module path if it is not resolvable) and is skipped without them.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
WORK=$(mktemp -d)
SOCK=/tmp/sm-test-$$.sock          # Unix socket paths are capped at 108 chars
DB_PORT=${DB_PORT:-3317}
WEB_PORT=${WEB_PORT:-8097}
export SM_BASE="http://127.0.0.1:$WEB_PORT" SM_SOCK="$SOCK" SM_DB=solar_test SM_TOKEN=testtoken
DB_PID= ; PHP_PID=
cleanup() { [ -n "$PHP_PID" ] && kill "$PHP_PID" 2>/dev/null; [ -n "$DB_PID" ] && kill "$DB_PID" 2>/dev/null; sleep 1; rm -rf "$WORK" "$SOCK"; }
trap cleanup EXIT

echo "== starting MariaDB"
mariadb-install-db --user="$(id -un)" --datadir="$WORK/db" >/dev/null 2>&1
mariadbd --user="$(id -un)" --datadir="$WORK/db" --socket="$SOCK" --pid-file="$WORK/db.pid" \
         --port="$DB_PORT" --bind-address=127.0.0.1 >"$WORK/db.log" 2>&1 &
DB_PID=$!
for _ in $(seq 1 30); do [ -S "$SOCK" ] && break; sleep 1; done
M=(mariadb -uroot --socket="$SOCK")
"${M[@]}" -e "CREATE DATABASE solar_test; CREATE USER app@'127.0.0.1' IDENTIFIED BY 'pw';
              GRANT ALL ON solar_test.* TO app@'127.0.0.1';"
"${M[@]}" solar_test < "$ROOT/backend/schema.sql"
hash() { php -r 'echo password_hash($argv[1], PASSWORD_DEFAULT);' "$1"; }
"${M[@]}" solar_test -e "INSERT INTO users (username, password_hash, is_admin) VALUES
  ('admin', '$(hash adminpass)', 1), ('alice', '$(hash alicepass)', 0), ('bob', '$(hash bobpass)', 0);"

echo "== starting PHP"
cat > "$WORK/secrets.php" <<PHP
<?php
declare(strict_types=1);
define('DB_HOST', '127.0.0.1;port=$DB_PORT');
define('DB_NAME', 'solar_test');
define('DB_USER', 'app');
define('DB_PASS', 'pw');
define('DEVICE_TOKEN', 'testtoken');
define('APP_TIMEZONE', 'Asia/Kolkata');
define('DEFAULT_LOG_INTERVAL_SEC', 900);
define('SESSION_LIFETIME', 3600);
PHP
SOLAR_SECRETS_PATH="$WORK/secrets.php" php -S "127.0.0.1:$WEB_PORT" -t "$ROOT/backend/public_html" >"$WORK/php.log" 2>&1 &
PHP_PID=$!
sleep 1

status=0
echo "== API scenarios"
python3 "$HERE/api_test.py" || status=1

if node -e "require(process.env.PLAYWRIGHT || 'playwright')" 2>/dev/null; then
  echo "== dashboard scenarios"
  python3 "$HERE/seed_dashboard.py" | "${M[@]}" solar_test
  node "$HERE/ui_test.js" || status=1
else
  echo "== dashboard scenarios SKIPPED (node + playwright not found)"
fi

if grep -qiE "fatal|warning|notice|deprecated" "$WORK/php.log"; then
  echo "== PHP reported problems:"; grep -iE "fatal|warning|notice|deprecated" "$WORK/php.log"; status=1
fi
[ $status -eq 0 ] && echo "== backend: ALL PASSED" || echo "== backend: FAILURES"
exit $status
