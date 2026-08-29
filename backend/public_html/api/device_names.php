<?php
// GET /api/device_names.php?device_ids=solar-a1fc64,solar-2e5694
// Returns the registered friendly_name for one or more device_ids.
//
//   { "ok": true, "names": { "solar-a1fc64": "salex" } }
//
// Unregistered / unknown ids are simply omitted from the map.
//
// Auth: NONE. This is deliberately public so the Android app can label a
// device the moment it is added, before (or without) the user signing in on
// the Cloud tab. It exposes only the display name, never readings, owner,
// location or any other device metadata — and only to a caller that already
// knows the exact device_id, which is derived from the device's MAC and is
// already visible to anyone in BLE range.

declare(strict_types=1);
require_once __DIR__ . '/_db.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    json_response(405, ['ok' => false, 'error' => 'method_not_allowed']);
}

$raw = trim((string)($_GET['device_ids'] ?? $_GET['device_id'] ?? ''));
if ($raw === '') {
    json_response(400, ['ok' => false, 'error' => 'missing_device_ids']);
}

// Cap the batch so this can't be turned into a bulk name-harvesting call.
$ids = array_slice(
    array_values(array_unique(array_filter(
        array_map('trim', explode(',', $raw)),
        // Mirror the firmware's device_id charset; ignore anything else.
        static fn(string $id): bool => $id !== '' && preg_match('/^[A-Za-z0-9_\-:.]{1,32}$/', $id) === 1,
    ))),
    0,
    25
);

if (!$ids) {
    json_response(400, ['ok' => false, 'error' => 'bad_device_ids']);
}

$place = implode(',', array_fill(0, count($ids), '?'));
$st = db()->prepare(
    "SELECT device_id, friendly_name FROM energy_devices WHERE device_id IN ($place)"
);
$st->execute($ids);

$names = [];
foreach ($st->fetchAll() as $row) {
    $names[$row['device_id']] = $row['friendly_name'];
}

json_response(200, ['ok' => true, 'names' => (object)$names]);
