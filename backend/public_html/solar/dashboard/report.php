<?php
declare(strict_types=1);
require_once __DIR__ . '/../api/_db.php';
$user = require_login();
$pdo  = db();

// Devices the user can see (same visibility rule as the dashboard).
if (!empty($user['is_admin'])) {
    $dev_rows = $pdo->query(
        'SELECT d.device_id, d.friendly_name, d.location
           FROM energy_devices d ORDER BY d.friendly_name'
    )->fetchAll();
} else {
    $st = $pdo->prepare(
        'SELECT d.device_id, d.friendly_name, d.location
           FROM energy_devices d
          WHERE d.owner_user_id = ?
          ORDER BY d.friendly_name'
    );
    $st->execute([$user['id']]);
    $dev_rows = $st->fetchAll();
}
$selected = $_GET['device_id'] ?? ($dev_rows[0]['device_id'] ?? '');

// All date math happens here in PHP, which _db.php has pinned to IST
// (APP_TIMEZONE / Asia/Kolkata). That way the report is anchored to IST
// regardless of the viewer's browser timezone.
$now = new DateTimeImmutable('now');

// Week starts Monday. ISO-8601: N = 1 (Mon) .. 7 (Sun).
$dow            = (int)$now->format('N');
$thisWeekStart  = $now->modify('-' . ($dow - 1) . ' days')->setTime(0, 0, 0);
$lastWeekStart  = $thisWeekStart->modify('-7 days');

$thisMonthStart = $now->modify('first day of this month')->setTime(0, 0, 0);
$lastMonthStart = $thisMonthStart->modify('-1 month');

$fmt = 'Y-m-d\TH:i:s';   // naive local; server interprets as IST
?>
<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Solar Monitor — reports</title>
<link rel="stylesheet" href="/solar/dashboard/assets/style.css?v=7">
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.6/dist/chart.umd.min.js"></script>
</head><body>

<header class="topbar">
  <div class="brand">Solar Monitor — reports</div>
  <div class="user">
    Signed in as <b><?= h($user['username']) ?></b>
    &middot; <a href="/solar/dashboard/">dashboard</a>
    <?php if (!empty($user['is_admin'])): ?>
      &middot; <a href="/solar/admin/">admin</a>
    <?php endif; ?>
    &middot; <a href="/solar/api/logout.php">sign out</a>
  </div>
</header>

<?php if (!$dev_rows): ?>
<main class="container">
  <div class="card empty"><p>No devices bound to your account yet.</p></div>
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
  </form>

  <!-- Weekly comparison -->
  <section class="card">
    <h2>Weekly comparison <span class="muted">(this week vs last week)</span></h2>
    <section class="cards stats">
      <div class="stat"><span>This week</span>
        <div class="stat-val"><b id="wk-this">—</b><i>kWh</i></div></div>
      <div class="stat"><span>Last week</span>
        <div class="stat-val"><b id="wk-last">—</b><i>kWh</i></div></div>
      <div class="stat"><span>Change</span>
        <div class="stat-val"><b id="wk-delta">—</b><i>%</i></div></div>
    </section>
    <canvas id="chart-week" height="120"></canvas>
  </section>

  <!-- Monthly comparison -->
  <section class="card">
    <h2>Monthly comparison <span class="muted">(this month vs last month)</span></h2>
    <section class="cards stats">
      <div class="stat"><span>This month</span>
        <div class="stat-val"><b id="mo-this">—</b><i>kWh</i></div></div>
      <div class="stat"><span>Last month</span>
        <div class="stat-val"><b id="mo-last">—</b><i>kWh</i></div></div>
      <div class="stat"><span>Change</span>
        <div class="stat-val"><b id="mo-delta">—</b><i>%</i></div></div>
    </section>
    <canvas id="chart-month" height="120"></canvas>
  </section>
</main>

<script>
const DEVICE_ID       = <?= json_encode($selected) ?>;
const THIS_WEEK_START = <?= json_encode($thisWeekStart->format('Y-m-d')) ?>;
const LAST_WEEK_START = <?= json_encode($lastWeekStart->format('Y-m-d')) ?>;
const THIS_MONTH_START= <?= json_encode($thisMonthStart->format('Y-m-d')) ?>;
const LAST_MONTH_START= <?= json_encode($lastMonthStart->format('Y-m-d')) ?>;
const WEEK_FETCH_FROM = <?= json_encode($lastWeekStart->format($fmt)) ?>;
const MONTH_FETCH_FROM= <?= json_encode($lastMonthStart->format($fmt)) ?>;
const NOW_ISO         = <?= json_encode($now->format($fmt)) ?>;

const BLUE  = '#2e6baa';
const GREEN = '#1f6e2a';
const DOW   = ['Mon','Tue','Wed','Thu','Fri','Sat','Sun'];

// Add N days to a "YYYY-MM-DD" string, return "YYYY-MM-DD".
function addDays(ymd, n) {
  const [y,m,d] = ymd.split('-').map(Number);
  const dt = new Date(Date.UTC(y, m-1, d));     // UTC math = no DST/tz drift
  dt.setUTCDate(dt.getUTCDate() + n);
  const p = x => String(x).padStart(2,'0');
  return dt.getUTCFullYear()+'-'+p(dt.getUTCMonth()+1)+'-'+p(dt.getUTCDate());
}
function daysInMonth(ymd) {
  const [y,m] = ymd.split('-').map(Number);
  return new Date(Date.UTC(y, m, 0)).getUTCDate();
}

// Fetch daily kWh for [from, NOW]; return a map { "YYYY-MM-DD": kwh }.
async function fetchDaily(fromIso) {
  const url = `/solar/api/readings.php?device_id=${encodeURIComponent(DEVICE_ID)}` +
              `&aggregate=daily&from=${encodeURIComponent(fromIso)}&to=${encodeURIComponent(NOW_ISO)}`;
  const map = {};
  try {
    const j = await (await fetch(url, { credentials:'same-origin' })).json();
    if (j.ok) j.points.forEach(p => { map[p.t.slice(0,10)] = p.kwh || 0; });
  } catch (e) { /* leave map empty */ }
  return map;
}

function fmt2(n) { return (Math.round(n*100)/100).toFixed(2); }
function setDelta(el, thisV, lastV) {
  if (lastV <= 0) { el.textContent = thisV > 0 ? '+∞' : '0'; return; }
  const pct = ((thisV - lastV) / lastV) * 100;
  el.textContent = (pct >= 0 ? '+' : '') + Math.round(pct);
  el.style.color = pct >= 0 ? GREEN : '#b3261e';
}

function barChart(canvasId, labels, thisData, lastData, thisLabel, lastLabel) {
  return new Chart(document.getElementById(canvasId).getContext('2d'), {
    type: 'bar',
    data: {
      labels,
      datasets: [
        { label: lastLabel, data: lastData, backgroundColor: 'rgba(46,107,170,0.55)' },
        { label: thisLabel, data: thisData, backgroundColor: 'rgba(31,110,42,0.75)' },
      ],
    },
    options: {
      responsive: true, animation: false,
      scales: { y: { beginAtZero: true, title: { display:true, text:'kWh / day' } } },
      plugins: { legend: { display: true } },
    },
  });
}

async function loadWeekly() {
  const map = await fetchDaily(WEEK_FETCH_FROM);
  const thisData = [], lastData = [];
  for (let i = 0; i < 7; i++) {
    thisData.push(map[addDays(THIS_WEEK_START, i)] || 0);
    lastData.push(map[addDays(LAST_WEEK_START, i)] || 0);
  }
  const tTot = thisData.reduce((a,b)=>a+b,0);
  const lTot = lastData.reduce((a,b)=>a+b,0);
  document.getElementById('wk-this').textContent = fmt2(tTot);
  document.getElementById('wk-last').textContent = fmt2(lTot);
  setDelta(document.getElementById('wk-delta'), tTot, lTot);
  barChart('chart-week', DOW, thisData, lastData, 'This week', 'Last week');
}

async function loadMonthly() {
  const map = await fetchDaily(MONTH_FETCH_FROM);
  const nThis = daysInMonth(THIS_MONTH_START);
  const nLast = daysInMonth(LAST_MONTH_START);
  const n = Math.max(nThis, nLast);
  const labels = [], thisData = [], lastData = [];
  for (let i = 0; i < n; i++) {
    labels.push(String(i+1));
    thisData.push(i < nThis ? (map[addDays(THIS_MONTH_START, i)] || 0) : 0);
    lastData.push(i < nLast ? (map[addDays(LAST_MONTH_START, i)] || 0) : 0);
  }
  const tTot = thisData.reduce((a,b)=>a+b,0);
  const lTot = lastData.reduce((a,b)=>a+b,0);
  document.getElementById('mo-this').textContent = fmt2(tTot);
  document.getElementById('mo-last').textContent = fmt2(lTot);
  setDelta(document.getElementById('mo-delta'), tTot, lTot);
  barChart('chart-month', labels, thisData, lastData, 'This month', 'Last month');
}

loadWeekly();
loadMonthly();
</script>
<?php endif; ?>

</body></html>
