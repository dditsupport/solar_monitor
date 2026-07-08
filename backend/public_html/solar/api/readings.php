<?php
// GET /solar/api/readings.php?device_id=X&from=ISO&to=ISO&aggregate=raw|hourly|daily|monthly
// Auth: session (browser/app). Returns JSON.
//
// Generated kWh is derived from the PZEM cumulative Wh counter (monotonically
// increasing; the firmware logs any reset). The response carries:
//   - points[].kwh : per-bucket energy. daily/monthly buckets TELESCOPE (each
//                    spans to the next bucket's first reading) so they sum
//                    exactly to total_kwh; hourly is a plain within-bucket
//                    MAX-MIN.
//   - total_kwh    : one MAX(energy_wh)-MIN(energy_wh) over the whole window.

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

// Daily/monthly buckets telescope (each spans to the next bucket's first
// reading) so they sum EXACTLY to total_kwh. Hourly stays a plain per-bucket
// MAX-MIN — the dashboard "Today" card sums those hourly bars deliberately, so
// it can differ slightly from the whole-day meter delta.
$points = match ($aggregate) {
    'raw'     => fetch_raw($device_id, $from_str, $to_str),
    'hourly'  => fetch_bucketed($device_id, $from_str, $to_str, '%Y-%m-%d %H:00:00', false),
    'daily'   => fetch_bucketed($device_id, $from_str, $to_str, '%Y-%m-%d 00:00:00', true),
    'monthly' => fetch_bucketed($device_id, $from_str, $to_str, '%Y-%m-01 00:00:00', true),
};

// Single whole-window meter delta (MAX-MIN of the cumulative Wh counter).
// Consumed by the dashboard "Period total" card and the report "Total".
$total_kwh = fetch_total_kwh($device_id, $from_str, $to_str);

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

function fetch_bucketed(string $device, string $from, string $to, string $fmt, bool $telescope): array {
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
        if ($telescope) {
            // Each bucket spans from its own first reading to the NEXT bucket's
            // first reading (last bucket runs to the window max). These deltas
            // telescope, so they sum EXACTLY to MAX-MIN over the window and no
            // energy is lost in the gaps between buckets (e.g. overnight).
            $this_first = (float)$r['wh_min'];
            $next_first = ($i + 1 < $n) ? (float)$rows[$i + 1]['wh_min'] : $range_wh_max;
            $kwh = max(0.0, ($next_first - $this_first) / 1000.0);
        } else {
            // Plain within-bucket delta (drops energy between buckets on purpose).
            $kwh = max(0.0, ((float)$r['wh_max'] - (float)$r['wh_min']) / 1000.0);
        }
        $out[] = [
            't'        => format_iso($r['bucket']),
            't_end'    => format_iso($r['bucket_end']),
            'kwh'      => round($kwh, 3),
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

function format_iso(string $datetime): string {
    return (new DateTimeImmutable($datetime, new DateTimeZone(APP_TIMEZONE)))
        ->format('c');
}
