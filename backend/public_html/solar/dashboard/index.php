<?php
declare(strict_types=1);
require_once __DIR__ . '/../api/_db.php';
$user = require_login();
$pdo  = db();

// Devices the user can see
if (!empty($user['is_admin'])) {
    $dev_rows = $pdo->query(
        'SELECT d.device_id, d.friendly_name, d.location, d.capacity_kw,
                m.last_sync_at
           FROM energy_devices d
           LEFT JOIN device_meta m ON m.device_id = d.device_id
          ORDER BY d.friendly_name'
    )->fetchAll();
} else {
    $st = $pdo->prepare(
        'SELECT d.device_id, d.friendly_name, d.location, d.capacity_kw,
                m.last_sync_at
           FROM energy_devices d
           LEFT JOIN device_meta m ON m.device_id = d.device_id
          WHERE d.owner_user_id = ?
          ORDER BY d.friendly_name'
    );
    $st->execute([$user['id']]);
    $dev_rows = $st->fetchAll();
}
$selected = $_GET['device_id'] ?? ($dev_rows[0]['device_id'] ?? '');
?>
<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Solar Monitor — dashboard</title>
<link rel="stylesheet" href="/solar/dashboard/assets/style.css?v=2">
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.6/dist/chart.umd.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/chartjs-adapter-date-fns@3.0.0/dist/chartjs-adapter-date-fns.bundle.min.js"></script>
</head><body>

<header class="topbar">
  <div class="brand">Solar Monitor</div>
  <div class="user">
    Signed in as <b><?= h($user['username']) ?></b>
    <?php if (!empty($user['is_admin'])): ?>
      &middot; <a href="/solar/admin/">admin</a>
    <?php endif; ?>
    &middot; <a href="/solar/api/logout.php">sign out</a>
  </div>
</header>

<?php if (!$dev_rows): ?>
<main class="container">
  <div class="card empty">
    <p>You don't have any devices bound to your account yet.</p>
    <?php if (!empty($user['is_admin'])): ?>
      <p>Go to <a href="/solar/admin/">Admin</a> &rarr; Devices to bind one.</p>
    <?php else: ?>
      <p>Ask an administrator to bind your device to this account.</p>
    <?php endif; ?>
  </div>
</main>
<?php else: ?>
<main class="container">
  <form class="card controls" method="get">
    <label>Device
      <select name="device_id" onchange="this.form.submit()">
        <?php foreach ($dev_rows as $d): ?>
          <option value="<?= h($d['device_id']) ?>"
            <?= $d['device_id'] === $selected ? 'selected' : '' ?>>
            <?= h($d['friendly_name']) ?>
            <?php if ($d['location']) echo ' — ' . h($d['location']); ?>
          </option>
        <?php endforeach; ?>
      </select>
    </label>
    <div class="range-buttons">
      <button type="button" data-range="today">Today</button>
      <button type="button" data-range="24h">24 h</button>
      <button type="button" data-range="7d">7 days</button>
      <button type="button" data-range="30d">30 days</button>
      <button type="button" data-range="12m">12 months</button>
    </div>
  </form>

  <section class="cards stats">
    <div class="stat"><span>Current</span><b id="stat-now">—</b><i>W</i></div>
    <div class="stat"><span>Today</span><b id="stat-today">—</b><i>kWh</i></div>
    <div class="stat"><span>Peak</span><b id="stat-peak">—</b><i>W</i></div>
    <div class="stat"><span>Period total</span><b id="stat-total">—</b><i>kWh</i></div>
  </section>

  <section class="card">
    <h2 id="chart-title">Energy</h2>
    <canvas id="chart-energy" height="120"></canvas>
  </section>

  <section class="card">
    <h2>Power</h2>
    <canvas id="chart-power" height="120"></canvas>
  </section>
</main>

<script>
const DEVICE_ID = <?= json_encode($selected) ?>;

const RANGES = {
  today: { aggregate: 'hourly', from: () => startOfToday(), label: "Today's energy (per hour)", energyLabel: 'kWh / hour' },
  '24h': { aggregate: 'hourly', from: () => hoursAgo(24), label: 'Last 24 hours',           energyLabel: 'kWh / hour' },
  '7d':  { aggregate: 'daily',  from: () => daysAgo(7),   label: 'Last 7 days',              energyLabel: 'kWh / day'  },
  '30d': { aggregate: 'daily',  from: () => daysAgo(30),  label: 'Last 30 days',             energyLabel: 'kWh / day'  },
  '12m': { aggregate: 'monthly',from: () => monthsAgo(12),label: 'Last 12 months',           energyLabel: 'kWh / month'},
};

function startOfToday(){ const d=new Date(); d.setHours(0,0,0,0); return d; }
function hoursAgo(h){ return new Date(Date.now() - h*3600e3); }
function daysAgo(d){ return new Date(Date.now() - d*86400e3); }
function monthsAgo(m){ const d=new Date(); d.setMonth(d.getMonth()-m); return d; }
function isoLocal(d){
  const pad=n=>String(n).padStart(2,'0');
  return d.getFullYear()+'-'+pad(d.getMonth()+1)+'-'+pad(d.getDate())+'T'+
         pad(d.getHours())+':'+pad(d.getMinutes())+':'+pad(d.getSeconds());
}

let energyChart, powerChart;
function makeChart(canvasId, type, datasets, yLabel){
  const ctx = document.getElementById(canvasId).getContext('2d');
  return new Chart(ctx, {
    type, data: { datasets },
    options: {
      responsive: true, animation: false,
      parsing: { xAxisKey: 't', yAxisKey: 'y' },
      scales: {
        x: { type: 'time', time: { tooltipFormat: 'PPp' } },
        y: { beginAtZero: true, title: { display: true, text: yLabel } },
      },
      plugins: { legend: { display: false } },
    },
  });
}

async function loadRange(rangeKey){
  const R = RANGES[rangeKey];
  document.getElementById('chart-title').textContent = R.label;
  const from = isoLocal(R.from());
  const url = `/solar/api/readings.php?device_id=${encodeURIComponent(DEVICE_ID)}` +
              `&aggregate=${R.aggregate}&from=${encodeURIComponent(from)}`;
  const res = await fetch(url, { credentials: 'same-origin' });
  const j   = await res.json();
  if (!j.ok) { alert('Error: ' + j.error); return; }

  const energyPoints = j.points.map(p => ({ t: p.t, y: p.kwh }));
  const powerPoints  = j.points.map(p => ({ t: p.t, y: p.P_avg }));

  if (energyChart) energyChart.destroy();
  if (powerChart)  powerChart.destroy();
  energyChart = makeChart('chart-energy', 'bar', [{
    label: R.energyLabel, data: energyPoints, backgroundColor: 'rgba(31,110,42,0.7)',
  }], R.energyLabel);
  powerChart = makeChart('chart-power', 'line', [{
    label: 'Avg power (W)', data: powerPoints, borderColor: '#c97a1a', tension: 0.25,
  }], 'W');

  // Stats
  const periodTotal = energyPoints.reduce((a, p) => a + (p.y || 0), 0);
  const peakP = powerPoints.reduce((m, p) => Math.max(m, p.y || 0), 0);
  document.getElementById('stat-total').textContent = periodTotal.toFixed(2);
  document.getElementById('stat-peak').textContent  = peakP.toFixed(0);

  // "Today" + "Current" come from a raw query of the last hour
  loadLive();
}

async function loadLive(){
  const from = isoLocal(hoursAgo(1));
  const url = `/solar/api/readings.php?device_id=${encodeURIComponent(DEVICE_ID)}&aggregate=raw&from=${encodeURIComponent(from)}`;
  let now = null;
  try {
    const res = await fetch(url, { credentials: 'same-origin' });
    const j   = await res.json();
    if (j.ok && j.points.length) {
      const p = j.points[j.points.length-1];
      if (typeof p.P === 'number') now = p.P;
    }
  } catch (e) { /* network/parse error — fall through to dash */ }
  document.getElementById('stat-now').textContent =
    now === null ? '—' : now.toFixed(0);

  // Today kWh via the daily bucket
  let today_kwh = null;
  try {
    const today = isoLocal(startOfToday());
    const url2 = `/solar/api/readings.php?device_id=${encodeURIComponent(DEVICE_ID)}&aggregate=daily&from=${encodeURIComponent(today)}`;
    const r2 = await (await fetch(url2, { credentials: 'same-origin' })).json();
    if (r2.ok && r2.points.length && typeof r2.points[0].kwh === 'number') {
      today_kwh = r2.points[0].kwh;
    } else if (r2.ok) {
      today_kwh = 0;
    }
  } catch (e) { /* fall through */ }
  document.getElementById('stat-today').textContent =
    today_kwh === null ? '—' : today_kwh.toFixed(2);
}

document.querySelectorAll('.range-buttons button').forEach(b => {
  b.addEventListener('click', () => {
    document.querySelectorAll('.range-buttons button').forEach(x => x.classList.remove('on'));
    b.classList.add('on');
    loadRange(b.dataset.range);
  });
});
// initial load: today
document.querySelector('.range-buttons button[data-range="today"]').click();
</script>
<?php endif; ?>

</body></html>
