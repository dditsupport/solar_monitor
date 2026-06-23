<?php
declare(strict_types=1);
require_once __DIR__ . '/../api/_db.php';
require_admin();
$pdo = db();

$devices = $pdo->query(
    'SELECT d.device_id, d.friendly_name, d.location, d.capacity_kw,
            d.owner_user_id, u.username AS owner_username, d.first_seen_at,
            m.fw_version, m.last_sync_at, m.last_seq, m.last_boot_id,
            m.total_readings, m.log_interval_sec
       FROM energy_devices d
       LEFT JOIN users        u ON u.id = d.owner_user_id
       LEFT JOIN device_meta  m ON m.device_id = d.device_id
      ORDER BY d.friendly_name'
)->fetchAll();

$users = $pdo->query('SELECT id, username FROM users ORDER BY username')->fetchAll();
?>
<!doctype html><html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Solar Monitor — devices</title>
<link rel="stylesheet" href="/solar/dashboard/assets/style.css?v=2">
<style>
  /* Per-column sizing for the devices admin grid. Inputs fill their cell so
     the column header drives width, not the input default. */
  table.devices            { table-layout: auto; }
  table.devices td input,
  table.devices td select  { width: 100%; box-sizing: border-box; }
  table.devices .col-id    { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
                             font-size: 0.82rem; white-space: nowrap; }
  table.devices .col-cap   input { width: 4.5rem; }
  table.devices .col-int   { white-space: nowrap; }
  table.devices .col-int   input  { width: 5rem; display: inline-block; }
  table.devices .col-int   button { margin-left: 0.35rem; }
  table.devices .col-meta  { white-space: nowrap; color: var(--muted); font-size: 0.82rem; }
  table.devices .col-rows  { text-align: right; font-variant-numeric: tabular-nums; }
  table.devices .actions   { white-space: nowrap; display: flex; gap: 0.5rem; align-items: center; }
  table.devices .actions a { font-size: 0.85rem; }
  /* Visual grouping: zebra stripe + breathing room */
  table.devices tbody tr:nth-child(odd) td { background: #fafbf8; }
  table.devices td, table.devices th { padding: 0.55rem 0.6rem; vertical-align: middle; }
</style>
</head><body>
<header class="topbar">
  <div class="brand">Solar Monitor — admin</div>
  <div class="user">
    <a href="/solar/admin/">overview</a>
    &middot; <a href="/solar/admin/users.php">users</a>
    &middot; <a href="/solar/api/logout.php">sign out</a>
  </div>
</header>
<main class="container">
  <section class="card">
    <h2>Devices</h2>
    <p class="muted">Devices auto-register on first ingest POST. Assign each one to a user below.</p>
    <table class="grid devices">
      <thead><tr>
        <th>Device ID</th>
        <th>Friendly name</th>
        <th>Location</th>
        <th>kW</th>
        <th>Owner</th>
        <th>Interval&nbsp;(s)</th>
        <th>Last sync</th>
        <th>FW</th>
        <th class="col-rows">Rows</th>
        <th></th>
      </tr></thead>
      <tbody>
      <?php foreach ($devices as $d): ?>
        <tr data-id="<?= h($d['device_id']) ?>">
          <td class="col-id"><?= h($d['device_id']) ?></td>
          <td><input class="name"     value="<?= h($d['friendly_name']) ?>"></td>
          <td><input class="location" placeholder="—"
                     value="<?= h((string)($d['location'] ?? '')) ?>"></td>
          <td class="col-cap">
            <input class="capacity" type="number" step="0.01" min="0" placeholder="—"
                   value="<?= h((string)($d['capacity_kw'] ?? '')) ?>">
          </td>
          <td>
            <select class="owner">
              <option value="">— unassigned —</option>
              <?php foreach ($users as $u): ?>
                <option value="<?= (int)$u['id'] ?>"
                  <?= $u['id'] == ($d['owner_user_id'] ?? -1) ? 'selected' : '' ?>>
                  <?= h($u['username']) ?>
                </option>
              <?php endforeach; ?>
            </select>
          </td>
          <td class="col-int">
            <input class="interval" type="number" min="60" max="86400" step="1"
                   value="<?= (int)($d['log_interval_sec'] ?? 900) ?>">
            <button class="set-interval">Set</button>
          </td>
          <td class="col-meta"><?= h((string)($d['last_sync_at'] ?? '—')) ?></td>
          <td class="col-meta"><?= h((string)($d['fw_version'] ?? '—')) ?></td>
          <td class="col-rows"><?= number_format((int)($d['total_readings'] ?? 0)) ?></td>
          <td class="actions">
            <button class="rename">Save</button>
            <a href="/solar/dashboard/?device_id=<?= urlencode($d['device_id']) ?>">view</a>
            <button class="danger delete-device">Delete</button>
          </td>
        </tr>
      <?php endforeach; ?>
      </tbody>
    </table>
  </section>
</main>

<script>
const CSRF = <?= json_encode(csrf_token()) ?>;

async function post(action, fields){
  const fd = new FormData();
  fd.append('action', action);
  fd.append('csrf', CSRF);
  for (const k in fields) fd.append(k, fields[k]);
  const res = await fetch('/solar/api/admin_devices.php', { method: 'POST', body: fd, credentials: 'same-origin' });
  return res.json();
}

document.querySelectorAll('select.owner').forEach(sel => sel.addEventListener('change', async () => {
  const tr = sel.closest('tr');
  const r  = await post('bind', { device_id: tr.dataset.id, user_id: sel.value || '' });
  if (!r.ok) alert('Error: ' + r.error);
}));

document.querySelectorAll('button.rename').forEach(btn => btn.addEventListener('click', async () => {
  const tr = btn.closest('tr');
  const r  = await post('rename', {
    device_id:     tr.dataset.id,
    friendly_name: tr.querySelector('.name').value,
    location:      tr.querySelector('.location').value,
    capacity_kw:   tr.querySelector('.capacity').value,
  });
  alert(r.ok ? 'Saved.' : 'Error: ' + r.error);
}));

document.querySelectorAll('button.set-interval').forEach(btn => btn.addEventListener('click', async () => {
  const tr = btn.closest('tr');
  const r  = await post('set_interval', {
    device_id: tr.dataset.id,
    log_interval_sec: tr.querySelector('.interval').value,
  });
  alert(r.ok ? 'Saved. Takes effect on the device\'s next sync.' : 'Error: ' + r.error);
}));

document.querySelectorAll('button.delete-device').forEach(btn => btn.addEventListener('click', async () => {
  const tr = btn.closest('tr');
  if (!confirm('Delete this device and ALL its readings? This cannot be undone.')) return;
  const r = await post('delete', { device_id: tr.dataset.id });
  if (!r.ok) { alert('Error: ' + r.error); return; }
  tr.remove();
}));
</script>
</body></html>
