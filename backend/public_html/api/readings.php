<?php
// GET /api/readings.php?device_id=X&from=ISO&to=ISO&aggregate=raw|hourly|daily|monthly
// Auth: session (browser/app). Returns JSON.
//
// Generated kWh is derived from the PZEM cumulative Wh counter (monotonically
// increasing; the firmware logs any reset). The response carries:
//   - points[].kwh : per-bucket energy. Buckets TELESCOPE (each spans from its
//                    own first reading to the NEXT bucket's first reading), so
//                    they sum exactly to total_kwh and leave no energy in the
//                    gaps between buckets.
//   - points[].wh_start / wh_end : the two cumulative meter readings (Wh) the
//                    bucket's kwh is the difference of, so a UI can show
//                    "start reading -> end reading = generated".
//   - total_kwh    : one MAX(energy_wh)-MIN(energy_wh) over the whole window,
//                    i.e. the energy GENERATED in it — never a running total.
//   - meter_wh     : the device's latest cumulative reading, ignoring the
//                    window entirely, for a "where the meter stands now" card.

declare(strict_types=1);
require_once __DIR__ . '/_db.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    json_response(405, ['ok' => false, 'error' => 'method_not_allowed']);
}
$user = require_login();

$device_id = (string)($_GET['device_id'] ?? '');
$aggregate = (string)($_GET['aggregate'] ?? 'raw');
$from      = (string)($_GET['from'] ?? '');
$to        = (string)($_GET['to']   ?? '');

if ($device_id === '' || !user_can_see_device($user, $device_id)) {
    json_response(403, ['ok' => false, 'error' => 'no_such_device']);
}

if (!in_array($aggregate, ['raw', 'hourly', 'daily', 'monthly'], true)) {
    json_response(400, ['ok' => false, 'error' => 'bad_aggregate']);
}

[$from_str, $to_str] = resolve_range($aggregate, $from, $to);

$pdo  = db();
$meta = $pdo->prepare('SELECT friendly_name, location, capacity_kw, adjustment_kwh FROM energy_devices WHERE device_id = ?');
$meta->execute([$device_id]);
$dev  = $meta->fetch() ?: [];

// Every bucket size telescopes (each spans to the next bucket's first reading)
// so the bars sum EXACTLY to total_kwh and consecutive buckets share a meter
// reading — one bucket's end reading IS the next one's start reading, which is
// what the dashboard prints under the chart.
$points = match ($aggregate) {
    'raw'     => fetch_raw($device_id, $from_str, $to_str),
    'hourly'  => fetch_bucketed($device_id, $from_str, $to_str, '%Y-%m-%d %H:00:00'),
    'daily'   => fetch_bucketed($device_id, $from_str, $to_str, '%Y-%m-%d 00:00:00'),
    'monthly' => fetch_bucketed($device_id, $from_str, $to_str, '%Y-%m-01 00:00:00'),
};

// Single whole-window meter delta (MAX-MIN of the cumulative Wh counter).
// Consumed by the dashboard "Period total" card and the report "Total".
$total_kwh = fetch_total_kwh($device_id, $from_str, $to_str);

// Where the meter stands right now — the newest row for this device, whatever
// the requested window is. The dashboard shows it as its own card, so the
// cumulative figure is never mixed into the per-period generation.
$meter = fetch_latest_reading($device_id);

json_response(200, [
    'ok'            => true,
    'device_id'     => $device_id,
    'friendly_name' => $dev['friendly_name'] ?? $device_id,
    'capacity_kw'   => $dev['capacity_kw'] ?? null,
    'adjustment_kwh'=> $dev['adjustment_kwh'] ?? 0,
    'from'          => $from_str,
    'to'            => $to_str,
    'aggregate'     => $aggregate,
    'total_kwh'     => $total_kwh,
    'meter_wh'      => $meter['wh'],
    'meter_at'      => $meter['t'],
    'points'        => $points,
]);


/* ---------- helpers ---------- */
function resolve_range(string $agg, string $from, string $to): array {
    $tz = new DateTimeZone(APP_TIMEZONE);
    $now = new DateTimeImmutable('now', $tz);
    $to_dt   = $to   !== '' ? new DateTimeImmutable($to,   $tz) : $now;
    $from_dt = $from !== '' ? new DateTimeImmutable($from, $tz) : $to_dt->modify('-1 day');

    // Sensible defaults per aggregate
    if ($from === '') {
        $from_dt = match ($agg) {
            'raw'     => $to_dt->modify('-1 day'),
            'hourly'  => $to_dt->modify('-7 days'),
            'daily'   => $to_dt->modify('-30 days'),
            'monthly' => $to_dt->modify('-12 months'),
        };
    }
    return [
        $from_dt->format('Y-m-d H:i:s'),
        $to_dt->format('Y-m-d H:i:s'),
    ];
}

function fetch_raw(string $device, string $from, string $to): array {
    $st = db()->prepare(
        'SELECT wall_time, voltage, current_a, power_w, energy_wh, power_factor,
                frequency_hz, time_confidence
           FROM solar_readings
          WHERE device_id = ? AND wall_time BETWEEN ? AND ?
          ORDER BY wall_time ASC
          LIMIT 5000'
    );
    $st->execute([$device, $from, $to]);
    return array_map(fn($r) => [
        't'    => format_iso($r['wall_time']),
        'V'    => (float)$r['voltage'],
        'I'    => (float)$r['current_a'],
        'P'    => (float)$r['power_w'],
        'Wh'   => (float)$r['energy_wh'],
        'PF'   => (float)$r['power_factor'],
        'Hz'   => $r['frequency_hz'] !== null ? (float)$r['frequency_hz'] : null,
        'conf' => $r['time_confidence'],
    ], $st->fetchAll());
}

function fetch_bucketed(string $device, string $from, string $to, string $fmt): array {
    // Per-bucket aggregates. The PZEM Wh counter is monotonically increasing,
    // so a bucket's earliest reading has its smallest energy_wh — i.e.
    // MIN(energy_wh) is that bucket's "first reading". Plus avg/peak power for
    // context.
    $st = db()->prepare(
        "SELECT DATE_FORMAT(wall_time, ?) AS bucket,
                MIN(wall_time)      AS bucket_start,
                MAX(wall_time)      AS bucket_end,
                MIN(energy_wh)      AS wh_min,
                MAX(energy_wh)      AS wh_max,
                AVG(power_w)        AS p_avg,
                MAX(power_w)        AS p_peak,
                AVG(voltage)        AS v_avg,
                COUNT(*)            AS samples,
                SUM(time_confidence='approx') AS approx_count
           FROM solar_readings
          WHERE device_id = ? AND wall_time BETWEEN ? AND ?
          GROUP BY bucket
          ORDER BY bucket ASC
          LIMIT 5000"
    );
    $st->execute([$fmt, $device, $from, $to]);
    $rows = $st->fetchAll();
    $n    = count($rows);

    // Window max = the last (latest) bucket's max reading, since energy is
    // monotonic and buckets are ordered ascending. Used as the tail boundary
    // for telescoping so the final bucket absorbs everything up to the
    // window's last reading.
    $range_wh_max = $n > 0 ? (float)$rows[$n - 1]['wh_max'] : 0.0;

    $out = [];
    foreach ($rows as $i => $r) {
        // Each bucket spans from its own first reading to the NEXT bucket's
        // first reading (the last bucket runs to the window max). These deltas
        // telescope, so they sum EXACTLY to MAX-MIN over the window and no
        // energy is lost in the gaps between buckets (e.g. overnight).
        $wh_start = (float)$r['wh_min'];
        $wh_end   = ($i + 1 < $n) ? (float)$rows[$i + 1]['wh_min'] : $range_wh_max;
        $kwh = max(0.0, ($wh_end - $wh_start) / 1000.0);
        $out[] = [
            't'        => format_iso($r['bucket']),
            't_end'    => format_iso($r['bucket_end']),
            'kwh'      => round($kwh, 3),
            // The two meter readings this bucket's kwh is the difference of:
            // wh_end - wh_start == kwh * 1000, exactly.
            'wh_start' => round($wh_start, 1),
            'wh_end'   => round($wh_end, 1),
            'P_avg'    => round((float)$r['p_avg'], 1),
            'P_peak'   => round((float)$r['p_peak'], 1),
            'V_avg'    => round((float)$r['v_avg'], 1),
            'samples'  => (int)$r['samples'],
            'approx'   => (int)$r['approx_count'] > 0,
        ];
    }
    return $out;
}

// Whole-window meter delta: a single MAX-MIN of the cumulative Wh counter over
// [from, to], in kWh. This is the source of truth for the "Period total" /
// "Total" figures and equals the sum of the telescoping daily/monthly buckets.
function fetch_total_kwh(string $device, string $from, string $to): float {
    $st = db()->prepare(
        'SELECT MIN(energy_wh) AS wh_min, MAX(energy_wh) AS wh_max
           FROM solar_readings
          WHERE device_id = ? AND wall_time BETWEEN ? AND ?'
    );
    $st->execute([$device, $from, $to]);
    $r = $st->fetch() ?: [];
    if (!isset($r['wh_min']) || $r['wh_min'] === null) {
        return 0.0;
    }
    return round(max(0.0, ((float)$r['wh_max'] - (float)$r['wh_min']) / 1000.0), 3);
}

// The device's most recent cumulative meter reading (Wh) and when it was
// logged, independent of the requested window. energy_wh is monotonic, so the
// newest row carries the highest value; ORDER BY wall_time rides the
// (device_id, wall_time) index instead of scanning for a MAX.
function fetch_latest_reading(string $device): array {
    $st = db()->prepare(
        'SELECT energy_wh, wall_time
           FROM solar_readings
          WHERE device_id = ?
          ORDER BY wall_time DESC
          LIMIT 1'
    );
    $st->execute([$device]);
    $r = $st->fetch();
    if (!$r) {
        return ['wh' => null, 't' => null];
    }
    return [
        'wh' => round((float)$r['energy_wh'], 1),
        't'  => format_iso($r['wall_time']),
    ];
}

function format_iso(string $datetime): string {
    return (new DateTimeImmutable($datetime, new DateTimeZone(APP_TIMEZONE)))
        ->format('c');
}
