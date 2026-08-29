<?php
// POST /api/reset_device_data.php
// Clears a device's accumulated cloud data, keeping the device itself
// registered (row, friendly name, owner, location stay put).
//
// Required:
//   device_id
//
// Deletes solar_readings + rtc_drift_log for the device and zeroes the
// device_meta counters, so the device starts from a clean slate on both
// sides. Intended to be called by the Android app right after it wipes the
// device's NVS ("Erase device data"): the firmware's seq counter restarts at
// 1 after that wipe, and solar_readings has UNIQUE(device_id, seq) with
// ingest.php inserting via "ON DUPLICATE KEY UPDATE id = id". Without
// clearing the old rows first, every post-erase reading silently collides
// with a stale row, is dropped, and is still reported as acked — so the
// firmware deletes its local copy too. Dropping the old rows keeps the
// sequence space free for the device's fresh start.
//
// Auth: cookie session + X-CSRF header. The caller must own the device;
// admins may reset any device.

declare(strict_types=1);
require_once __DIR__ . '/_db.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    json_response(405, ['ok' => false, 'error' => 'method_not_allowed']);
}

$user = require_login();
check_csrf();

$body = $_POST;
if (!$body) $body = json_body();

$device_id = trim((string)($body['device_id'] ?? ''));
if ($device_id === '') {
    json_response(400, ['ok' => false, 'error' => 'missing_device_id']);
}

$pdo = db();

$st = $pdo->prepare('SELECT owner_user_id FROM energy_devices WHERE device_id = ?');
$st->execute([$device_id]);
$dev = $st->fetch();
if (!$dev) {
    json_response(404, ['ok' => false, 'error' => 'unknown_device']);
}

$owner    = $dev['owner_user_id'];
$is_admin = !empty($user['is_admin']);
if (!$is_admin && ($owner === null || (int)$owner !== (int)$user['id'])) {
    json_response(403, ['ok' => false, 'error' => 'not_your_device']);
}

$pdo->beginTransaction();
try {
    $st = $pdo->prepare('DELETE FROM solar_readings WHERE device_id = ?');
    $st->execute([$device_id]);
    $deleted = $st->rowCount();

    $pdo->prepare('DELETE FROM rtc_drift_log WHERE device_id = ?')->execute([$device_id]);

    // Keep the row (and its log_interval_sec override) but reset the
    // progress counters so the next ingest is treated as a fresh start.
    $pdo->prepare(
        'UPDATE device_meta
            SET last_seq       = 0,
                last_boot_id   = 0,
                total_readings = 0
          WHERE device_id = ?'
    )->execute([$device_id]);

    $pdo->commit();
} catch (Throwable $e) {
    $pdo->rollBack();
    json_response(500, ['ok' => false, 'error' => 'server_error']);
}

json_response(200, [
    'ok'             => true,
    'device_id'      => $device_id,
    'rows_deleted'   => $deleted,
]);
