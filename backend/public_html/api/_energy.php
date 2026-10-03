<?php
// Continuity for the PZEM's cumulative energy counter.
//
// Every kWh figure in this app is a difference between two readings of the
// PZEM's cumulative Wh counter. That counter is not truly monotonic: it
// restarts at 0 when someone sends the app's "Reset PZEM" command or the PZEM
// is swapped, and it rolls over at the top of its range (9,999.99 kWh). A plain
// MAX - MIN across such a drop is garbage — 9,999 kWh in a day, or 0.
//
// energy_resets (migration 009) records every drop between two readings that
// are adjacent in seq order. seq is the device's own logging order, so it is
// immune to the wall-clock reconstruction ingest.php does: a reading logged
// later always has a higher seq, whatever wall_time it ended up with.
//
// A reading's CONTINUOUS value is its raw energy_wh plus the wh_before of every
// drop at or before it — the counter as if it had never restarted. Energy
// generated between the last reading before a drop and the drop itself is
// unknowable and is dropped; everything after the drop is counted.
//
// Every query here is best-effort against a database that has not had
// migration 009 applied: no energy_resets table simply means "no resets", so
// a deploy that runs ahead of its migration degrades to the old arithmetic
// instead of failing.

declare(strict_types=1);

/**
 * Re-derive the drops between adjacent readings spanning [seq_lo, seq_hi],
 * including the pair that links the span to the reading just before it and
 * the pair that links it to the reading just after it — a row that arrives
 * late can create a drop on either side of itself, or remove one that
 * existed between its neighbours.
 */
function rescan_energy_resets(PDO $pdo, string $device, int $seq_lo, int $seq_hi): void {
    $st = $pdo->prepare('SELECT MAX(seq) FROM solar_readings WHERE device_id = ? AND seq < ?');
    $st->execute([$device, $seq_lo]);
    $pred = $st->fetchColumn();
    $from = ($pred === null || $pred === false) ? $seq_lo : (int)$pred;

    $st = $pdo->prepare('SELECT MIN(seq) FROM solar_readings WHERE device_id = ? AND seq > ?');
    $st->execute([$device, $seq_hi]);
    $succ = $st->fetchColumn();
    $to = ($succ === null || $succ === false) ? $seq_hi : (int)$succ;

    $st = $pdo->prepare(
        'SELECT seq, wall_time, energy_wh FROM solar_readings
          WHERE device_id = ? AND seq BETWEEN ? AND ?
          ORDER BY seq'
    );
    $st->execute([$device, $from, $to]);
    $drops = [];
    $prev  = null;
    while ($r = $st->fetch()) {
        $wh = (float)$r['energy_wh'];
        if ($prev !== null && $wh < $prev) {
            $drops[] = [(int)$r['seq'], $r['wall_time'], $prev, $wh];
        }
        $prev = $wh;
    }

    // Every pair examined above has its later reading in (from, to], so that is
    // exactly the range whose records this scan is authoritative for.
    $pdo->prepare('DELETE FROM energy_resets WHERE device_id = ? AND seq_after > ? AND seq_after <= ?')
        ->execute([$device, $from, $to]);
    if ($drops) {
        $ins = $pdo->prepare(
            'INSERT INTO energy_resets (device_id, seq_after, wall_time_after, wh_before, wh_after)
             VALUES (?, ?, ?, ?, ?)'
        );
        foreach ($drops as [$seq, $wt, $before, $after]) {
            $ins->execute([$device, $seq, $wt, $before, $after]);
        }
    }
}

/**
 * Devices that already had readings when migration 009 ran have never been
 * scanned; do it once, the first time their data is asked for. Ingest keeps
 * every device current from then on.
 */
function ensure_energy_resets_scanned(PDO $pdo, string $device): void {
    try {
        $st = $pdo->prepare('SELECT resets_scanned FROM device_meta WHERE device_id = ?');
        $st->execute([$device]);
        $flag = $st->fetchColumn();
        if ($flag === false || (int)$flag === 1) return;
        rescan_energy_resets($pdo, $device, 0, PHP_INT_MAX);
        $pdo->prepare('UPDATE device_meta SET resets_scanned = 1 WHERE device_id = ?')
            ->execute([$device]);
    } catch (Throwable $e) {
        // Migration 009 not applied yet — nothing to scan into.
    }
}

/**
 * The device's counter segments: ['seqs' => [seq_after, ...], 'comp' => [wh, ...]]
 * where comp[i] is the compensation for a reading with exactly i drops at or
 * before it (comp[0] = 0). Both lists are ascending.
 */
function load_energy_segments(PDO $pdo, string $device): array {
    $seqs = [];
    $comp = [0.0];
    try {
        $st = $pdo->prepare(
            'SELECT seq_after, wh_before FROM energy_resets WHERE device_id = ? ORDER BY seq_after'
        );
        $st->execute([$device]);
        foreach ($st->fetchAll() as $r) {
            $seqs[] = (int)$r['seq_after'];
            $comp[] = end($comp) + (float)$r['wh_before'];
        }
    } catch (Throwable $e) {
        // No energy_resets table yet: one segment, no compensation.
    }
    return ['seqs' => $seqs, 'comp' => $comp];
}

/**
 * SQL for a reading's segment index (the number of drops at or before its
 * seq), appending its bind values to $params. "0" when there are no drops, so
 * the common case costs nothing.
 */
function energy_segment_sql(array $segments, array &$params): string {
    if (!$segments['seqs']) return '0';
    $terms = [];
    foreach ($segments['seqs'] as $s) {
        $terms[]  = '(seq >= ?)';
        $params[] = $s;
    }
    return '(' . implode(' + ', $terms) . ')';
}

/** Compensation (Wh) for a reading with this seq. */
function energy_comp_for_seq(array $segments, int $seq): float {
    $n = 0;
    foreach ($segments['seqs'] as $s) {
        if ($seq >= $s) $n++; else break;
    }
    return $segments['comp'][$n];
}
