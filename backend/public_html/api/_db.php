<?php
// Shared bootstrap: DB connection, session, auth helpers, JSON responses.
// Included by every endpoint and page.

declare(strict_types=1);

// Target: PHP 8.4+. The codebase uses:
//   - Random\Randomizer       (PHP 8.2+)
//   - JSON_THROW_ON_ERROR     (PHP 7.3+)
//   - readonly + strict types (PHP 8.2+ readonly classes)
//   - match expressions, str_*_with, named args (PHP 8.0+)
//   - never / ?type / ?:    everywhere
// Soft-fail on older PHP so the cause is obvious rather than a cryptic
// syntax error somewhere deep in an admin page.
if (PHP_VERSION_ID < 80200) {
    http_response_code(500);
    header('Content-Type: text/plain');
    exit("Solar Monitor backend requires PHP 8.2+; this host runs " . PHP_VERSION . ".\n");
}

// Locate secrets.php. Preferred location is outside the document root
// (e.g. /home/<cpaneluser>/solar_secrets/secrets.php) so the file is
// unreachable over HTTP even if the web server's deny-rules are removed.
//
// SOLAR_SECRETS_PATH wins if set. Otherwise every folder above the site root
// is tried for solar_secrets/secrets.php, nearest first, so the same file is
// found whether the site sits in public_html, in a subdomain folder
// (/home/<user>/solar.example.com) or in a subfolder of either. A fixed
// "N levels up" broke on every move between those layouts. Falls back to the
// in-tree _config/ directory (handy for local dev).
(function (): void {
    $candidates = [];
    if ($env = getenv('SOLAR_SECRETS_PATH')) $candidates[] = $env;
    for ($dir = dirname(__DIR__, 2); ; $dir = dirname($dir)) {
        $candidates[] = rtrim($dir, '/') . '/solar_secrets/secrets.php';
        if (dirname($dir) === $dir) break;   // reached the filesystem root
    }
    $candidates[] = __DIR__ . '/../_config/secrets.php';   // legacy in-tree
    foreach ($candidates as $p) {
        if (is_file($p)) { require_once $p; return; }
    }
    http_response_code(500);
    header('Content-Type: text/plain');
    exit("Solar Monitor: secrets.php not found. Tried: " . implode(', ', $candidates) . "\n");
})();

// Force a sane timezone. The whole stack (firmware ISO offsets, stored
// wall_time, readings/report queries, dashboard JS) assumes the server runs
// in APP_TIMEZONE. Default to Asia/Kolkata (IST, UTC+5:30) if secrets.php
// didn't set it or set an invalid zone, so a misconfigured host can't
// silently shift every chart by the server's local offset.
if (!defined('APP_TIMEZONE') || @date_default_timezone_set(APP_TIMEZONE) === false) {
    date_default_timezone_set('Asia/Kolkata');
}

/* ---------- PDO singleton ---------- */
function db(): PDO {
    static $pdo = null;
    if ($pdo !== null) return $pdo;
    $dsn = sprintf('mysql:host=%s;dbname=%s;charset=utf8mb4', DB_HOST, DB_NAME);
    $pdo = new PDO($dsn, DB_USER, DB_PASS, [
        PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        PDO::ATTR_EMULATE_PREPARES   => false,
    ]);
    // Pin the MySQL session to APP_TIMEZONE too. PHP writes wall_time and the
    // other DATETIME columns in APP_TIMEZONE, but NOW() / CURRENT_TIMESTAMP
    // (last_sync_at, ingest_log.received_at, ...) follow the MySQL server's own
    // zone — fine on a host that happens to run IST, 5.5 h off on a UTC one.
    // A numeric offset rather than the zone name, because named zones need the
    // MySQL time-zone tables, which shared hosts often don't load.
    $offset = (new DateTimeImmutable('now'))->format('P');   // e.g. "+05:30"
    $pdo->prepare('SET time_zone = ?')->execute([$offset]);
    return $pdo;
}

/* ---------- JSON helpers ---------- */
function json_response(int $code, array $data): never {
    http_response_code($code);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    echo json_encode($data, JSON_UNESCAPED_SLASHES | JSON_THROW_ON_ERROR);
    exit;
}

function json_body(): array {
    $raw = file_get_contents('php://input');
    if ($raw === false || $raw === '') return [];
    try {
        $doc = json_decode($raw, true, 512, JSON_THROW_ON_ERROR);
    } catch (JsonException) {
        return [];
    }
    return is_array($doc) ? $doc : [];
}

/* ---------- Device auth (X-Device-Token) ---------- */
function require_device_auth(): void {
    $sent = $_SERVER['HTTP_X_DEVICE_TOKEN'] ?? '';
    if (!hash_equals(DEVICE_TOKEN, $sent)) {
        json_response(401, ['ok' => false, 'error' => 'unauthorized']);
    }
}

/* ---------- Browser sessions ---------- */
function start_user_session(): void {
    if (session_status() === PHP_SESSION_ACTIVE) return;
    session_set_cookie_params([
        'lifetime' => SESSION_LIFETIME,
        'path'     => '/',
        'secure'   => !empty($_SERVER['HTTPS']),
        'httponly' => true,
        'samesite' => 'Lax',
    ]);
    session_name('solar_sess');
    session_start();
    // Idle timeout
    if (isset($_SESSION['last_activity']) &&
        time() - $_SESSION['last_activity'] > SESSION_LIFETIME) {
        session_unset();
        session_destroy();
    }
    $_SESSION['last_activity'] = time();
}

function current_user(): ?array {
    start_user_session();
    if (empty($_SESSION['user_id'])) return null;
    $st = db()->prepare('SELECT id, username, email, is_admin FROM users WHERE id = ?');
    $st->execute([$_SESSION['user_id']]);
    $u = $st->fetch();
    return $u ?: null;
}

function require_login(): array {
    $u = current_user();
    if (!$u) {
        if (str_starts_with($_SERVER['REQUEST_URI'] ?? '', '/api/')) {
            json_response(401, ['ok' => false, 'error' => 'login_required']);
        }
        header('Location: /dashboard/login.php');
        exit;
    }
    return $u;
}

function require_admin(): array {
    $u = require_login();
    if (empty($u['is_admin'])) {
        json_response(403, ['ok' => false, 'error' => 'admin_only']);
    }
    return $u;
}

function login_user(int $user_id): void {
    start_user_session();
    session_regenerate_id(true);
    $_SESSION['user_id'] = $user_id;
    $_SESSION['last_activity'] = time();
    db()->prepare('UPDATE users SET last_login_at = NOW() WHERE id = ?')
        ->execute([$user_id]);
}

function logout_user(): void {
    start_user_session();
    $_SESSION = [];
    if (ini_get('session.use_cookies')) {
        $p = session_get_cookie_params();
        setcookie(session_name(), '', time() - 42000,
            $p['path'], $p['domain'], $p['secure'], $p['httponly']);
    }
    session_destroy();
}

/* ---------- CSRF ---------- */
function csrf_token(): string {
    start_user_session();
    if (empty($_SESSION['csrf'])) {
        // Random\Randomizer (PHP 8.2+) over bare random_bytes — same security,
        // explicit about which engine and reusable across the request.
        static $rand = null;
        $rand ??= new Random\Randomizer();
        $_SESSION['csrf'] = bin2hex($rand->getBytes(16));
    }
    return $_SESSION['csrf'];
}

// True when $sent matches this session's CSRF token. A session that has no
// token yet never matches — not even an empty $sent. hash_equals('', '') is
// true, so without that guard any session that never rendered a token (a
// dashboard-only login, say) accepted state-changing requests with no token
// at all.
function csrf_matches(mixed $sent): bool {
    start_user_session();
    $known = $_SESSION['csrf'] ?? '';
    return is_string($known) && $known !== ''
        && is_string($sent) && hash_equals($known, $sent);
}

function check_csrf(): void {
    $sent = $_POST['csrf'] ?? $_SERVER['HTTP_X_CSRF'] ?? '';
    if (!csrf_matches($sent)) {
        json_response(403, ['ok' => false, 'error' => 'bad_csrf']);
    }
}

/* ---------- Convenience ---------- */
function client_ip(): string {
    return $_SERVER['REMOTE_ADDR'] ?? '';
}

function h(?string $s): string {
    return htmlspecialchars((string)$s, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
}

/* ---------- Device access control ---------- */
// Returns the list of device_ids the user is allowed to query.
// Admins see all devices; others only their owned ones.
function visible_device_ids(array $user): array {
    if (!empty($user['is_admin'])) {
        $st = db()->query('SELECT device_id FROM energy_devices ORDER BY device_id');
    } else {
        $st = db()->prepare('SELECT device_id FROM energy_devices WHERE owner_user_id = ?');
        $st->execute([$user['id']]);
    }
    return array_column($st->fetchAll(), 'device_id');
}

function user_can_see_device(array $user, string $device_id): bool {
    if (!empty($user['is_admin'])) return true;
    $st = db()->prepare('SELECT 1 FROM energy_devices WHERE device_id = ? AND owner_user_id = ?');
    $st->execute([$device_id, $user['id']]);
    return (bool)$st->fetchColumn();
}
