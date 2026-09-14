<?php
// Admin-only device management.
//   action=list           -> all devices + owner + meta
//   action=bind           -> assign owner_user_id to a device (or null to unbind)
//   action=rename         -> set friendly_name / location / capacity_kw / notes
//   action=set_interval   -> override device_meta.log_interval_sec (0 = use default)
//   action=set_maintenance-> nightly-reboot window + radio-rest knobs, pushed to
//                            the device on its next ingest response (blank = clear,
//                            i.e. leave the device on its compiled default)
//   action=delete         -> delete device + all its readings (cascades)

declare(strict_types=1);
require_once __DIR__ . '/_db.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    json_response(405, ['ok' => false, 'error' => 'method_not_allowed']);
}
require_admin();
check_csrf();

$pdo    = db();
$action = (string)($_POST['action'] ?? '');

switch ($action) {

case 'list':
    $rows = $pdo->query(
        'SELECT d.device_id, d.friendly_name, d.location, d.capacity_kw, d.adjustment_kwh, d.notes,
                d.owner_user_id, u.username AS owner_username, d.first_seen_at,
                m.fw_version, m.last_sync_at, m.last_seq, m.last_boot_id,
                m.total_readings, m.log_interval_sec,
                m.heap_free, m.heap_largest, m.heap_min, m.heap_at,
                m.nightly_reboot_enable, m.nightly_reboot_start_hour,
                m.nightly_reboot_end_hour, m.radio_rest_interval_sec,
                m.radio_rest_duration_sec
           FROM energy_devices d
           LEFT JOIN users        u ON u.id = d.owner_user_id
           LEFT JOIN device_meta  m ON m.device_id = d.device_id
          ORDER BY d.friendly_name'
    )->fetchAll();
    json_response(200, ['ok' => true, 'devices' => $rows]);

case 'bind':
    $device_id = (string)($_POST['device_id'] ?? '');
    $user_id   = $_POST['user_id'] ?? '';
    $user_id   = ($user_id === '' || $user_id === '0') ? null : (int)$user_id;
    if ($device_id === '') json_response(400, ['ok' => false, 'error' => 'bad_input']);
    if ($user_id !== null) {
        $st = $pdo->prepare('SELECT 1 FROM users WHERE id = ?');
        $st->execute([$user_id]);
        if (!$st->fetchColumn()) json_response(404, ['ok' => false, 'error' => 'no_such_user']);
    }
    $pdo->prepare('UPDATE energy_devices SET owner_user_id = ? WHERE device_id = ?')
        ->execute([$user_id, $device_id]);
    json_response(200, ['ok' => true]);

case 'rename':
    $device_id    = (string)($_POST['device_id'] ?? '');
    $friendly     = trim((string)($_POST['friendly_name'] ?? ''));
    $location     = trim((string)($_POST['location'] ?? '')) ?: null;
    $capacity_kw  = $_POST['capacity_kw'] === '' || !isset($_POST['capacity_kw'])
                        ? null : (float)$_POST['capacity_kw'];
    // Signed correction; empty means "no adjustment" = 0 (column is NOT NULL).
    $adjustment   = ($_POST['adjustment_kwh'] ?? '') === '' ? 0.0 : (float)$_POST['adjustment_kwh'];
    $notes        = trim((string)($_POST['notes'] ?? '')) ?: null;
    if ($device_id === '' || $friendly === '') {
        json_response(400, ['ok' => false, 'error' => 'bad_input']);
    }
    $pdo->prepare(
        'UPDATE energy_devices SET friendly_name = ?, location = ?, capacity_kw = ?, adjustment_kwh = ?, notes = ? WHERE device_id = ?'
    )->execute([$friendly, $location, $capacity_kw, $adjustment, $notes, $device_id]);
    json_response(200, ['ok' => true]);

case 'set_interval':
    $device_id = (string)($_POST['device_id'] ?? '');
    $sec       = (int)($_POST['log_interval_sec'] ?? 0);
    if ($device_id === '') json_response(400, ['ok' => false, 'error' => 'bad_input']);
    if ($sec !== 0 && ($sec < 60 || $sec > 86400)) {
        json_response(400, ['ok' => false, 'error' => 'interval_out_of_range']);
    }
    $pdo->prepare(
        'INSERT INTO device_meta (device_id, log_interval_sec) VALUES (?, ?)
         ON DUPLICATE KEY UPDATE log_interval_sec = VALUES(log_interval_sec)'
    )->execute([$device_id, $sec ?: 900]);
    json_response(200, ['ok' => true]);

case 'set_maintenance':
    // Every field is independent and may be cleared: an empty string stores
    // NULL, which makes ingest.php omit it, which makes the firmware keep its
    // own cached/compiled value. So clearing is "stop managing this", not
    // "disable it" -- to actually disable the nightly reboot, set enable=0.
    $device_id = (string)($_POST['device_id'] ?? '');
    if ($device_id === '') json_response(400, ['ok' => false, 'error' => 'bad_input']);

    $nullable = static function (string $key): ?int {
        $raw = $_POST[$key] ?? '';
        return ($raw === '' || $raw === null) ? null : (int)$raw;
    };
    $en = $nullable('nightly_reboot_enable');
    $sh = $nullable('nightly_reboot_start_hour');
    $eh = $nullable('nightly_reboot_end_hour');
    $ri = $nullable('radio_rest_interval_sec');
    $rd = $nullable('radio_rest_duration_sec');

    // Validate to the same bounds the firmware enforces, so an out-of-range
    // value is rejected here rather than silently dropped on the device.
    if ($en !== null && $en !== 0 && $en !== 1) {
        json_response(400, ['ok' => false, 'error' => 'enable_must_be_0_or_1']);
    }
    // The window must be a non-empty span inside one local day, and both ends
    // must be set together -- a start without an end is not a window.
    if (($sh === null) !== ($eh === null)) {
        json_response(400, ['ok' => false, 'error' => 'window_needs_both_hours']);
    }
    if ($sh !== null && ($sh < 0 || $sh > 23 || $eh < 1 || $eh > 24 || $eh <= $sh)) {
        json_response(400, ['ok' => false, 'error' => 'bad_reboot_window']);
    }
    // 0 = periodic rest disabled; otherwise at least 10 min so a mistyped value
    // cannot put the radio off-air on a loop.
    if ($ri !== null && $ri !== 0 && ($ri < 600 || $ri > 86400)) {
        json_response(400, ['ok' => false, 'error' => 'bad_rest_interval']);
    }
    if ($rd !== null && ($rd < 5 || $rd > 120)) {
        json_response(400, ['ok' => false, 'error' => 'bad_rest_duration']);
    }

    $pdo->prepare(
        'INSERT INTO device_meta
           (device_id, nightly_reboot_enable, nightly_reboot_start_hour,
            nightly_reboot_end_hour, radio_rest_interval_sec, radio_rest_duration_sec)
         VALUES (?, ?, ?, ?, ?, ?)
         ON DUPLICATE KEY UPDATE
           nightly_reboot_enable     = VALUES(nightly_reboot_enable),
           nightly_reboot_start_hour = VALUES(nightly_reboot_start_hour),
           nightly_reboot_end_hour   = VALUES(nightly_reboot_end_hour),
           radio_rest_interval_sec   = VALUES(radio_rest_interval_sec),
           radio_rest_duration_sec   = VALUES(radio_rest_duration_sec)'
    )->execute([$device_id, $en, $sh, $eh, $ri, $rd]);
    json_response(200, ['ok' => true]);

case 'delete':
    $device_id = (string)($_POST['device_id'] ?? '');
    if ($device_id === '') json_response(400, ['ok' => false, 'error' => 'bad_input']);
    $pdo->prepare('DELETE FROM energy_devices WHERE device_id = ?')->execute([$device_id]);
    json_response(200, ['ok' => true]);

default:
    json_response(400, ['ok' => false, 'error' => 'unknown_action']);
}
