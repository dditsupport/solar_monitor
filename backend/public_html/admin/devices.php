<?php
declare(strict_types=1);
require_once __DIR__ . '/../api/_db.php';
require_admin();
$pdo = db();

$devices = $pdo->query(
    'SELECT d.device_id, d.friendly_name, d.location, d.capacity_kw, d.adjustment_kwh,
            d.owner_user_id, u.username AS owner_username, d.first_seen_at,
            m.fw_version, m.last_sync_at, m.last_seq, m.last_boot_id,
            m.total_readings, m.log_interval_sec,
            m.heap_free, m.heap_largest, m.heap_min, m.heap_at,
            m.nightly_reboot_enable, m.nightly_reboot_start_hour,
            m.nightly_reboot_end_hour, m.radio_rest_interval_sec,
            m.radio_rest_duration_sec,
            (SELECT r.drift_sec FROM rtc_drift_log r
              WHERE r.device_id = d.device_id
              ORDER BY r.measured_at DESC LIMIT 1) AS rtc_drift_sec,
            (SELECT r.rssi_dbm FROM rtc_drift_log r
              WHERE r.device_id = d.device_id
              ORDER BY r.measured_at DESC LIMIT 1) AS rssi_dbm,
            (SELECT r.coin_cell_v FROM rtc_drift_log r
              WHERE r.device_id = d.device_id
              ORDER BY r.measured_at DESC LIMIT 1) AS coin_cell_v
       FROM energy_devices d
       LEFT JOIN users        u ON u.id = d.owner_user_id
       LEFT JOIN device_meta  m ON m.device_id = d.device_id
      ORDER BY d.friendly_name'
)->fetchAll();

$users = $pdo->query('SELECT id, username FROM users ORDER BY username')->fetchAll();

// Explains the repurposed capacity_kw column in the UI.
$OLD_KWH_HELP = "The meter this device replaced: its last reading in kWh at install. "
              . "Added to the dashboard Today and Period total so they continue from "
              . "the old meter instead of restarting at zero.";
$ADJUST_HELP  = "Signed correction (kWh) added to the dashboard Period total so the "
              . "displayed cumulative matches your physical solar meter. "
              . "Set it to (actual meter reading - the Period total shown). "
              . "Can be negative.";
?>
<!doctype html><html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Solar Monitor — devices</title>
<link rel="stylesheet" href="/dashboard/assets/style.css?v=8">
<style>
  /* Devices render as a card list (one .dev per device) on two wrapping lines,
     so nothing overflows the viewport and no horizontal scroll is needed. */
  .dev {
    border: 1px solid var(--border); border-radius: 8px;
    padding: 0.75rem 0.9rem; margin-bottom: 0.75rem;
    background: var(--surface); box-shadow: var(--shadow);
  }
  .dev-line { display: flex; flex-wrap: wrap; gap: 0.55rem 0.9rem; align-items: flex-end; }
  .dev-line + .dev-line {
    margin-top: 0.6rem; padding-top: 0.6rem; border-top: 1px dashed var(--border);
    align-items: center;
  }
  .dev .col-id {
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 0.82rem; white-space: nowrap; align-self: center;
  }
  /* Labelled fields. The label caption sits above each control. */
  .dev .f { display: flex; flex-direction: column; gap: 0.2rem; }
  .dev .f > span,
  .dev .m > span {
    font-size: 0.68rem; color: var(--muted);
    text-transform: uppercase; letter-spacing: 0.04em;
  }
  .dev .f input, .dev .f select {
    width: 100%; box-sizing: border-box;
    padding: 0.35rem 0.5rem; border: 1px solid var(--border); border-radius: 6px;
    font-size: 0.9rem;
  }
  /* Flex weights: location gets the most room; others share what's left. */
  .dev .f-name  { flex: 1 1 9rem; }
  .dev .f-loc   { flex: 3 1 12rem; }
  .dev .f-cap   { flex: 0 1 8rem; }
  .dev .f-adj   { flex: 0 1 8rem; }
  .dev .f-owner { flex: 1 1 9rem; }
  .dev .int-wrap { display: flex; gap: 0.35rem; }
  .dev .int-wrap input { width: 5.5rem; }
  /* The config line carries six controls; keep the numeric inputs narrow
     so they stay on one row at normal widths and wrap cleanly below that. */
  .dev .dev-cfg input[type=number] { width: 5.5rem; }
  /* Read-only status items on line 2 */
  .dev .m { display: flex; flex-direction: column; gap: 0.1rem; }
  .dev .m b {
    font-size: 0.85rem; color: var(--text); font-weight: 500;
    font-variant-numeric: tabular-nums; white-space: nowrap;
  }
  .dev .actions { margin-left: auto; display: flex; gap: 0.5rem; align-items: center; }
  .dev .actions a { font-size: 0.85rem; }
</style>
</head><body>
<header class="topbar">
  <div class="brand">Solar Monitor — admin</div>
  <div class="user">
    <a href="/admin/">overview</a>
    &middot; <a href="/admin/users.php">users</a>
    &middot; <a href="/api/logout.php">sign out</a>
  </div>
</header>
<main class="container">
  <section class="card">
    <h2>Devices</h2>
    <p class="muted">Devices auto-register on first ingest POST. Assign each one to a user below.</p>
    <?php foreach ($devices as $d): ?>
      <div class="dev" data-id="<?= h($d['device_id']) ?>">
        <div class="dev-line">
          <span class="col-id" title="Device ID"><?= h($d['device_id']) ?></span>
          <label class="f f-name"><span>Name</span>
            <input class="name" value="<?= h($d['friendly_name']) ?>">
          </label>
          <label class="f f-loc"><span>Location</span>
            <input class="location" placeholder="—"
                   value="<?= h((string)($d['location'] ?? '')) ?>">
          </label>
          <label class="f f-cap" title="<?= h($OLD_KWH_HELP) ?>"><span>Old kWh</span>
            <input class="capacity" type="number" step="0.01" min="0" placeholder="—"
                   value="<?= h((string)($d['capacity_kw'] ?? '')) ?>">
          </label>
          <label class="f f-adj" title="<?= h($ADJUST_HELP) ?>"><span>Adjust kWh</span>
            <input class="adjustment" type="number" step="0.01" placeholder="0"
                   value="<?= h((string)(float)($d['adjustment_kwh'] ?? 0)) ?>">
          </label>
          <label class="f f-owner"><span>Owner</span>
            <select class="owner">
              <option value="">— unassigned —</option>
              <?php foreach ($users as $u): ?>
                <option value="<?= (int)$u['id'] ?>"
                  <?= $u['id'] == ($d['owner_user_id'] ?? -1) ? 'selected' : '' ?>>
                  <?= h($u['username']) ?>
                </option>
              <?php endforeach; ?>
            </select>
          </label>
        </div>
        <div class="dev-line dev-cfg">
          <!-- Every editable setting on one line; the line below is read-only
               telemetry. Interval has its own Set because it is stored and
               pushed independently of the maintenance block.
               For the maintenance fields, BLANK means "do not manage": the
               field is omitted from the ingest response and the device keeps
               its compiled default. To actually stop the nightly reboot, set
               Nightly reboot to 0. -->
          <label class="f f-int"><span>Interval (s)</span>
            <span class="int-wrap">
              <input class="interval" type="number" min="60" max="86400" step="1"
                     value="<?= (int)($d['log_interval_sec'] ?? 900) ?>">
              <button class="set-interval">Set</button>
            </span>
          </label>
          <label class="f"><span>Nightly reboot</span>
            <input class="nr-en" type="number" min="0" max="1" step="1" placeholder="—"
                   value="<?= $d['nightly_reboot_enable'] === null ? '' : (int)$d['nightly_reboot_enable'] ?>">
          </label>
          <label class="f"><span>From (h)</span>
            <input class="nr-sh" type="number" min="0" max="23" step="1" placeholder="—"
                   value="<?= $d['nightly_reboot_start_hour'] === null ? '' : (int)$d['nightly_reboot_start_hour'] ?>">
          </label>
          <label class="f"><span>To (h)</span>
            <input class="nr-eh" type="number" min="1" max="24" step="1" placeholder="—"
                   value="<?= $d['nightly_reboot_end_hour'] === null ? '' : (int)$d['nightly_reboot_end_hour'] ?>">
          </label>
          <label class="f"><span>Radio rest (s)</span>
            <input class="rr-int" type="number" min="0" max="86400" step="1" placeholder="—"
                   value="<?= $d['radio_rest_interval_sec'] === null ? '' : (int)$d['radio_rest_interval_sec'] ?>">
          </label>
          <label class="f"><span>Rest for (s)</span>
            <input class="rr-dur" type="number" min="5" max="120" step="1" placeholder="—"
                   value="<?= $d['radio_rest_duration_sec'] === null ? '' : (int)$d['radio_rest_duration_sec'] ?>">
          </label>
          <button class="set-maint">Set maintenance</button>
        </div>
        <div class="dev-line dev-meta">
          <span class="m"><span>Last sync</span><b><?= h((string)($d['last_sync_at'] ?? '—')) ?></b></span>
          <span class="m"><span>FW</span><b><?= h((string)($d['fw_version'] ?? '—')) ?></b></span>
          <span class="m"><span>RTC drift</span><b><?php
            if ($d['rtc_drift_sec'] === null) { echo '—'; }
            else { $ds = (int)$d['rtc_drift_sec']; echo ($ds > 0 ? '+' : '') . $ds . 's'; }
          ?></b></span>
          <span class="m"><span>Wi-Fi</span><b><?php
            echo $d['rssi_dbm'] === null ? '—' : (int)$d['rssi_dbm'] . ' dBm';
          ?></b></span>
          <span class="m"><span>Heap free</span><b><?php
            // Falling steadily = leak. Flat while `largest` falls = fragmentation.
            echo $d['heap_free'] === null ? '—' : number_format((int)$d['heap_free'] / 1024, 1) . ' KB';
          ?></b></span>
          <span class="m"><span>Heap largest</span><b><?php
            echo $d['heap_largest'] === null ? '—' : number_format((int)$d['heap_largest'] / 1024, 1) . ' KB';
          ?></b></span>
          <span class="m"><span>Heap min</span><b><?php
            echo $d['heap_min'] === null ? '—' : number_format((int)$d['heap_min'] / 1024, 1) . ' KB';
          ?></b></span>
          <span class="m"><span>Coin cell</span><b><?php
            echo $d['coin_cell_v'] === null ? '—' : number_format((float)$d['coin_cell_v'], 2) . ' V';
          ?></b></span>
          <span class="m"><span>Rows</span><b><?= number_format((int)($d['total_readings'] ?? 0)) ?></b></span>
          <span class="actions">
            <button class="rename">Save</button>
            <a href="/dashboard/?device_id=<?= urlencode($d['device_id']) ?>">view</a>
            <button class="danger delete-device">Delete</button>
          </span>
        </div>
      </div>
    <?php endforeach; ?>
  </section>
</main>

<script>
const CSRF = <?= json_encode(csrf_token()) ?>;

async function post(action, fields){
  const fd = new FormData();
  fd.append('action', action);
  fd.append('csrf', CSRF);
  for (const k in fields) fd.append(k, fields[k]);
  const res = await fetch('/api/admin_devices.php', { method: 'POST', body: fd, credentials: 'same-origin' });
  return res.json();
}

document.querySelectorAll('select.owner').forEach(sel => sel.addEventListener('change', async () => {
  const dev = sel.closest('.dev');
  const r   = await post('bind', { device_id: dev.dataset.id, user_id: sel.value || '' });
  if (!r.ok) alert('Error: ' + r.error);
}));

document.querySelectorAll('button.rename').forEach(btn => btn.addEventListener('click', async () => {
  const dev = btn.closest('.dev');
  const r   = await post('rename', {
    device_id:      dev.dataset.id,
    friendly_name:  dev.querySelector('.name').value,
    location:       dev.querySelector('.location').value,
    capacity_kw:    dev.querySelector('.capacity').value,
    adjustment_kwh: dev.querySelector('.adjustment').value,
  });
  alert(r.ok ? 'Saved.' : 'Error: ' + r.error);
}));

document.querySelectorAll('button.set-interval').forEach(btn => btn.addEventListener('click', async () => {
  const dev = btn.closest('.dev');
  const r   = await post('set_interval', {
    device_id: dev.dataset.id,
    log_interval_sec: dev.querySelector('.interval').value,
  });
  alert(r.ok ? 'Saved. Takes effect on the device\'s next sync.' : 'Error: ' + r.error);
}));

document.querySelectorAll('button.set-maint').forEach(btn => btn.addEventListener('click', async () => {
  const dev = btn.closest('.dev');
  // Empty inputs are sent as empty strings; the API stores NULL for those,
  // which makes ingest.php omit the field and leaves the device on its own
  // default. That is deliberate -- blank means "unmanaged", not "off".
  const r = await post('set_maintenance', {
    device_id:                 dev.dataset.id,
    nightly_reboot_enable:     dev.querySelector('.nr-en').value,
    nightly_reboot_start_hour: dev.querySelector('.nr-sh').value,
    nightly_reboot_end_hour:   dev.querySelector('.nr-eh').value,
    radio_rest_interval_sec:   dev.querySelector('.rr-int').value,
    radio_rest_duration_sec:   dev.querySelector('.rr-dur').value,
  });
  alert(r.ok ? 'Saved. Takes effect on the device\'s next sync.' : 'Error: ' + r.error);
}));

document.querySelectorAll('button.delete-device').forEach(btn => btn.addEventListener('click', async () => {
  const dev = btn.closest('.dev');
  if (!confirm('Delete this device and ALL its readings? This cannot be undone.')) return;
  const r = await post('delete', { device_id: dev.dataset.id });
  if (!r.ok) { alert('Error: ' + r.error); return; }
  dev.remove();
}));
</script>
</body></html>
