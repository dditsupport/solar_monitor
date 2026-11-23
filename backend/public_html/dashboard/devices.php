<?php
declare(strict_types=1);
require_once __DIR__ . '/../api/_db.php';
require_once __DIR__ . '/../api/_ui.php';
// Device settings. Each owner manages their own devices here; admins see every
// device and also choose its owner. api/manage_devices.php enforces the same
// rule, so hiding a control is never the only check.
$user     = require_login();
$is_admin = !empty($user['is_admin']);
$pdo      = db();

$st = $pdo->prepare(
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
      WHERE ? OR d.owner_user_id = ?
      ORDER BY d.friendly_name'
);
$st->execute([(int)$is_admin, (int)$user['id']]);
$devices = $st->fetchAll();

// Devices with a Reset PZEM still waiting for their next Wi-Fi sync. Separate
// and best-effort, so the page still opens before migration 010 is run.
$reset_pending = [];
try {
    foreach ($pdo->query(
        "SELECT DISTINCT device_id FROM device_commands WHERE cmd = 'reset_pzem' AND done_at IS NULL"
    ) as $row) {
        $reset_pending[$row['device_id']] = true;
    }
} catch (Throwable $e) { /* no device_commands table yet */ }

$users = $is_admin ? $pdo->query('SELECT id, username FROM users ORDER BY username')->fetchAll() : [];

// Explains the repurposed capacity_kw column in the UI.
$OLD_KWH_HELP = "The meter this device replaced: its last reading in kWh at install. "
              . "Added to the dashboard's Meter reading card and to the start/end "
              . "readings under the chart, so they continue from the old meter "
              . "instead of restarting at zero. Period total is unaffected.";
$ADJUST_HELP  = "Signed correction (kWh) added alongside Old meter kWh, so the dashboard's "
              . "Meter reading card matches your physical solar meter. "
              . "Set it to (actual meter reading - the Meter reading shown). "
              . "Can be negative. Period total is unaffected.";
$INTERVAL_HELP = "Seconds between logged readings. Leave blank to follow the server "
               . "default (" . (int)DEFAULT_LOG_INTERVAL_SEC . " s).";
$MAINT_HELP = "Blank = leave the device on its own default. Nightly reboot: 1 = on, 0 = off, "
            . "inside the From-To hours. Radio rest: seconds between short Wi-Fi rests "
            . "(0 = never), and how long each rest lasts.";
?>
<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Solar Monitor — devices</title>
<meta name="theme-color" content="#f6fbf2" media="(prefers-color-scheme: light)">
<meta name="theme-color" content="#101510" media="(prefers-color-scheme: dark)">
<link rel="stylesheet" href="/dashboard/assets/style.css?v=18">
</head><body>

<header class="topbar">
  <div class="brand"><span class="logo"><?= ui_icon('device') ?></span><?= $is_admin ? 'All devices' : 'My devices' ?></div>
  <div class="user">
    <span class="who">Signed in as <b><?= h($user['username']) ?></b></span>
    <a href="/dashboard/"><?= ui_icon('home') ?>Dashboard</a>
    <a href="/dashboard/report.php"><?= ui_icon('chart') ?>Reports</a>
    <?php if ($is_admin): ?>
      <a href="/admin/"><?= ui_icon('shield') ?>Admin</a>
    <?php endif; ?>
    <a href="/api/logout.php"><?= ui_icon('logout') ?>Sign out</a>
  </div>
</header>

<main class="container">
<?php if (!$devices): ?>
  <div class="card empty">
    <p>You don't have any devices yet.</p>
    <p>Add one from the Android app (Devices &rarr; scan &rarr; claim), or ask an administrator to bind it to your account.</p>
  </div>
<?php endif; ?>

<?php foreach ($devices as $d): ?>
  <section class="card dev" data-id="<?= h($d['device_id']) ?>">
    <div class="dev-head">
      <span class="badge tint-primary"><?= ui_icon('device') ?></span>
      <div class="dev-title">
        <h2><?= h($d['friendly_name']) ?></h2>
        <span class="col-id" title="Device ID"><?= h($d['device_id']) ?></span>
      </div>
      <a class="chip" href="/dashboard/?device_id=<?= urlencode($d['device_id']) ?>"><?= ui_icon('chart') ?>Dashboard</a>
    </div>

    <h3>Name &amp; meter</h3>
    <div class="dev-line">
      <label class="f f-name"><span>Name</span>
        <input class="name" value="<?= h($d['friendly_name']) ?>" required>
      </label>
      <label class="f f-loc"><span>Location</span>
        <input class="location" placeholder="—"
               value="<?= h((string)($d['location'] ?? '')) ?>">
      </label>
      <label class="f f-cap" title="<?= h($OLD_KWH_HELP) ?>"><span>Old meter kWh</span>
        <input class="capacity" type="number" step="0.01" min="0" placeholder="—"
               value="<?= h((string)($d['capacity_kw'] ?? '')) ?>">
      </label>
      <label class="f f-adj" title="<?= h($ADJUST_HELP) ?>"><span>Adjust kWh</span>
        <input class="adjustment" type="number" step="0.01" placeholder="0"
               value="<?= h((string)(float)($d['adjustment_kwh'] ?? 0)) ?>">
      </label>
      <button class="rename">Save</button>
    </div>

    <h3>Device settings <small>sent to the device on its next sync</small></h3>
    <div class="dev-line dev-cfg">
      <!-- Interval has its own Set because it is stored and pushed
           independently of the maintenance block. For the maintenance fields,
           BLANK means "do not manage": the field is omitted from the ingest
           response and the device keeps its compiled default. To actually stop
           the nightly reboot, set Nightly reboot to 0. -->
      <label class="f f-int" title="<?= h($INTERVAL_HELP) ?>"><span>Log every (s)</span>
        <span class="int-wrap">
          <input class="interval" type="number" min="60" max="86400" step="1"
                 placeholder="<?= (int)DEFAULT_LOG_INTERVAL_SEC ?: 'fw' ?>"
                 value="<?= (int)($d['log_interval_sec'] ?? 0) ?: '' ?>">
          <button class="set-interval">Set</button>
        </span>
      </label>
      <span class="sep" aria-hidden="true"></span>
      <label class="f" title="<?= h($MAINT_HELP) ?>"><span>Nightly reboot</span>
        <input class="nr-en" type="number" min="0" max="1" step="1" placeholder="—"
               value="<?= $d['nightly_reboot_enable'] === null ? '' : (int)$d['nightly_reboot_enable'] ?>">
      </label>
      <label class="f" title="<?= h($MAINT_HELP) ?>"><span>From (h)</span>
        <input class="nr-sh" type="number" min="0" max="23" step="1" placeholder="—"
               value="<?= $d['nightly_reboot_start_hour'] === null ? '' : (int)$d['nightly_reboot_start_hour'] ?>">
      </label>
      <label class="f" title="<?= h($MAINT_HELP) ?>"><span>To (h)</span>
        <input class="nr-eh" type="number" min="1" max="24" step="1" placeholder="—"
               value="<?= $d['nightly_reboot_end_hour'] === null ? '' : (int)$d['nightly_reboot_end_hour'] ?>">
      </label>
      <label class="f" title="<?= h($MAINT_HELP) ?>"><span>Radio rest (s)</span>
        <input class="rr-int" type="number" min="0" max="86400" step="1" placeholder="—"
               value="<?= $d['radio_rest_interval_sec'] === null ? '' : (int)$d['radio_rest_interval_sec'] ?>">
      </label>
      <label class="f" title="<?= h($MAINT_HELP) ?>"><span>Rest for (s)</span>
        <input class="rr-dur" type="number" min="5" max="120" step="1" placeholder="—"
               value="<?= $d['radio_rest_duration_sec'] === null ? '' : (int)$d['radio_rest_duration_sec'] ?>">
      </label>
      <button class="set-maint">Set maintenance</button>
    </div>

    <h3>Health</h3>
    <div class="dev-line dev-meta">
      <span class="m"><span>Last sync</span><b><?= h((string)($d['last_sync_at'] ?? '') ?: '—') ?></b></span>
      <span class="m"><span>FW</span><b><?= h((string)($d['fw_version'] ?? '') ?: '—') ?></b></span>
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
    </div>

    <h3>PZEM energy counter</h3>
    <div class="dev-line dev-pzem">
      <button class="tonal reset-pzem" title="Zero the PZEM's cumulative energy counter, e.g. after replacing it.">Reset PZEM to 0</button>
      <span class="muted pzem-note"><?= isset($reset_pending[$d['device_id']])
        ? 'Reset pending &mdash; applies on the device\'s next Wi-Fi sync.'
        : 'The dashboard\'s meter reading carries on; only the device\'s own counter restarts at 0.' ?></span>
    </div>

    <h3>Ownership &amp; data</h3>
    <div class="dev-line dev-own">
      <?php if ($is_admin): ?>
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
      <?php else: ?>
        <button class="tonal release" title="Unbind this device from your account; it stays registered and can be claimed again.">Release from my account</button>
      <?php endif; ?>
      <span class="actions">
        <button class="tonal reset-data" title="Delete this device's readings in the cloud; the device stays registered.">Clear readings</button>
        <button class="danger delete-device">Delete device</button>
      </span>
    </div>
    <p class="dev-msg" role="status" aria-live="polite"></p>
  </section>
<?php endforeach; ?>
</main>

<script>
const CSRF = <?= json_encode(csrf_token()) ?>;

async function post(url, action, fields){
  const fd = new FormData();
  if (action) fd.append('action', action);
  fd.append('csrf', CSRF);
  for (const k in fields) fd.append(k, fields[k]);
  const res = await fetch(url, { method: 'POST', body: fd, credentials: 'same-origin' });
  if (res.status === 401) { location.href = '/dashboard/login.php'; return { ok: false, error: 'login_required' }; }
  try { return await res.json(); } catch (e) { return { ok: false, error: 'server returned HTTP ' + res.status }; }
}
const manage = (action, fields) => post('/api/manage_devices.php', action, fields);

// Result of the last action, under the card (no pop-ups).
function say(dev, r, okText){
  const el = dev.querySelector('.dev-msg');
  el.textContent = r.ok ? okText : 'Error: ' + r.error;
  el.className = 'dev-msg ' + (r.ok ? 'ok' : 'bad');
}
const devOf = el => el.closest('.dev');
const on = (sel, fn) => document.querySelectorAll(sel).forEach(el =>
  el.addEventListener(el.tagName === 'SELECT' ? 'change' : 'click', () => fn(devOf(el), el)));

on('select.owner', async (dev, sel) => {
  say(dev, await manage('bind', { device_id: dev.dataset.id, user_id: sel.value || '' }), 'Owner saved.');
});

on('button.rename', async dev => {
  const r = await manage('rename', {
    device_id:      dev.dataset.id,
    friendly_name:  dev.querySelector('.name').value,
    location:       dev.querySelector('.location').value,
    capacity_kw:    dev.querySelector('.capacity').value,
    adjustment_kwh: dev.querySelector('.adjustment').value,
  });
  if (r.ok) dev.querySelector('h2').textContent = dev.querySelector('.name').value.trim();
  say(dev, r, 'Saved.');
});

on('button.set-interval', async dev => {
  say(dev, await manage('set_interval', {
    device_id: dev.dataset.id,
    // Blank = follow the server default; the API stores that as 0.
    log_interval_sec: dev.querySelector('.interval').value || 0,
  }), 'Saved. Takes effect on the device\'s next sync.');
});

on('button.set-maint', async dev => {
  // Empty inputs are sent as empty strings; the API stores NULL for those,
  // which makes ingest.php omit the field and leaves the device on its own
  // default. That is deliberate -- blank means "unmanaged", not "off".
  say(dev, await manage('set_maintenance', {
    device_id:                 dev.dataset.id,
    nightly_reboot_enable:     dev.querySelector('.nr-en').value,
    nightly_reboot_start_hour: dev.querySelector('.nr-sh').value,
    nightly_reboot_end_hour:   dev.querySelector('.nr-eh').value,
    radio_rest_interval_sec:   dev.querySelector('.rr-int').value,
    radio_rest_duration_sec:   dev.querySelector('.rr-dur').value,
  }), 'Saved. Takes effect on the device\'s next sync.');
});

on('button.release', async dev => {
  if (!confirm('Release this device from your account? Its readings stay; you can claim it again from the app.')) return;
  const r = await manage('bind', { device_id: dev.dataset.id, user_id: '' });
  if (r.ok) dev.remove(); else say(dev, r, '');
});

on('button.reset-data', async dev => {
  if (!confirm('Delete ALL readings of this device in the cloud? The device stays registered. This cannot be undone.')) return;
  const r = await post('/api/reset_device_data.php', null, { device_id: dev.dataset.id });
  say(dev, r, r.ok ? `Cleared ${r.rows_deleted} readings.` : '');
});

on('button.reset-pzem', async dev => {
  if (!confirm('Reset the PZEM energy counter to 0? It happens on the device\'s next Wi-Fi sync. '
             + 'The dashboard\'s meter reading and history carry on unchanged.')) return;
  const r = await manage('reset_pzem', { device_id: dev.dataset.id });
  if (r.ok) dev.querySelector('.pzem-note').textContent = 'Reset pending \u2014 applies on the device\'s next Wi-Fi sync.';
  say(dev, r, 'Reset PZEM queued.');
});

on('button.delete-device', async dev => {
  if (!confirm('Delete this device and ALL its records in the cloud? This cannot be undone. '
             + 'The device itself keeps running: on its next sync it shows up again as a new, '
             + 'unowned device to claim.')) return;
  const r = await manage('delete', { device_id: dev.dataset.id });
  if (r.ok) dev.remove(); else say(dev, r, '');
});
</script>
</body></html>
