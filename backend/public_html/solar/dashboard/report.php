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

// Date anchors computed in PHP, which _db.php pins to IST (Asia/Kolkata),
// so the report is correct regardless of the viewer's browser timezone.
$now = new DateTimeImmutable('now');
?>
<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Solar Monitor — reports</title>
<link rel="stylesheet" href="/solar/dashboard/assets/style.css?v=8">
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
    <div class="range-buttons">
      <button type="button" id="btn-weekly"  class="on">Weekly (last 7 days)</button>
      <button type="button" id="btn-monthly">Monthly</button>
    </div>
    <label id="month-wrap" style="display:none">Month
      <input type="month" id="month-input" max="<?= $now->format('Y-m') ?>"
             value="<?= $now->format('Y-m') ?>">
    </label>
    <label>From
      <select id="hour-from"></select>
    </label>
    <label>To
      <select id="hour-to"></select>
    </label>
  </form>

  <section class="card" id="totals-card" style="display:none">
    <div class="totals-head">
      <h2>Total</h2>
      <div class="grand-total"><b id="grand-total">—</b> <i>kWh</i></div>
    </div>
    <p class="muted">Each chip is that day's start&rarr;end meter difference. Chips sum to the Total.</p>
    <div class="day-chips" id="day-chips"></div>
  </section>

  <section class="card">
    <h2 id="chart-title">Hourly energy — last 7 days</h2>
    <p class="muted" id="chart-sub">Each line is one day. X = hour of day (IST), Y = kWh generated that hour.</p>
    <canvas id="chart-hours" height="150"></canvas>
    <p class="muted" id="chart-empty" style="display:none">No data for this range.</p>
  </section>
</main>

<script>
const DEVICE_ID = <?= json_encode($selected) ?>;
const TODAY_YMD = <?= json_encode($now->format('Y-m-d')) ?>;
const NOW_ISO   = <?= json_encode($now->format('Y-m-d\TH:i:s')) ?>;

const MON = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
// 25 boundary labels 00:00..24:00. A point at "23:00" is the energy of the
// 23:00->24:00 bucket; the trailing "24:00" boundary lets that final hour's
// line segment render instead of being clipped.
const HOUR_LABELS = Array.from({length:25}, (_,h) => String(h).padStart(2,'0') + ':00');

// Solar generation standard window — sensible default so the chart opens on
// daylight hours instead of a flat 00:00-06:00 stretch. User can widen it.
// From is an hour-start (0..23); To is an hour-boundary (1..24, inclusive end).
const SOLAR_START_DEFAULT = 6;   // 06:00
const SOLAR_END_DEFAULT   = 19;  // 19:00

// "YYYY-MM-DD" + N days -> "YYYY-MM-DD" (UTC math avoids tz/DST drift).
function addDays(ymd, n) {
  const [y,m,d] = ymd.split('-').map(Number);
  const dt = new Date(Date.UTC(y, m-1, d));
  dt.setUTCDate(dt.getUTCDate() + n);
  const p = x => String(x).padStart(2,'0');
  return dt.getUTCFullYear()+'-'+p(dt.getUTCMonth()+1)+'-'+p(dt.getUTCDate());
}
function daysInMonth(y, m) { return new Date(Date.UTC(y, m, 0)).getUTCDate(); }
function dayLabel(ymd) {
  const [y,m,d] = ymd.split('-').map(Number);
  return d + '-' + MON[m-1];
}
// Distinct color per line, spread around the hue wheel.
function color(i, total) {
  const hue = Math.round((360 * i) / Math.max(total,1));
  return `hsl(${hue}, 65%, 45%)`;
}

let chart = null;
let lastLoad = null;   // { days, byDay, title, sub } cached so hour-range
                       // changes re-render without refetching.

function hourRange() {
  let from = parseInt(document.getElementById('hour-from').value, 10);
  let to   = parseInt(document.getElementById('hour-to').value, 10);   // boundary 1..24
  if (isNaN(from)) from = SOLAR_START_DEFAULT;
  if (isNaN(to))   to   = SOLAR_END_DEFAULT;
  if (to <= from) to = from + 1;   // always show at least one hour
  if (to > 24)    to = 24;
  return [from, to];
}

// Fetch hourly kWh for [fromIso, toIso]; return map { "YYYY-MM-DD": [24 kwh] }.
async function fetchHourly(fromIso, toIso) {
  const url = `/solar/api/readings.php?device_id=${encodeURIComponent(DEVICE_ID)}` +
              `&aggregate=hourly&from=${encodeURIComponent(fromIso)}&to=${encodeURIComponent(toIso)}`;
  const byDay = {};
  try {
    const j = await (await fetch(url, { credentials:'same-origin' })).json();
    if (j.ok) {
      j.points.forEach(p => {
        const day  = p.t.slice(0,10);
        const hour = parseInt(p.t.slice(11,13), 10);
        if (isNaN(hour)) return;
        if (!byDay[day]) byDay[day] = new Array(24).fill(0);
        byDay[day][hour] = p.kwh || 0;
      });
    }
  } catch (e) { /* leave empty */ }
  return byDay;
}

// Fetch telescoping daily buckets for [fromIso, toIso]. Each point.kwh is that
// day's start->end meter difference; total_kwh is the whole-period difference.
// The daily buckets telescope, so the chips sum exactly to the Total.
async function fetchDaily(fromIso, toIso) {
  const url = `/solar/api/readings.php?device_id=${encodeURIComponent(DEVICE_ID)}` +
              `&aggregate=daily&from=${encodeURIComponent(fromIso)}&to=${encodeURIComponent(toIso)}`;
  try {
    const j = await (await fetch(url, { credentials:'same-origin' })).json();
    if (j.ok) return { total: (typeof j.total_kwh === 'number' ? j.total_kwh : null), points: j.points || [] };
  } catch (e) { /* leave empty */ }
  return { total: null, points: [] };
}

// Render the Grand Total + one chip per day (each day's start->end delta).
function renderTotals(daily) {
  const card   = document.getElementById('totals-card');
  const grandEl = document.getElementById('grand-total');
  const chipsEl = document.getElementById('day-chips');
  chipsEl.innerHTML = '';

  if (!daily.points.length) { card.style.display = 'none'; return; }
  card.style.display = '';

  let sum = 0;
  daily.points.forEach(p => {
    const ymd = p.t.slice(0, 10);
    const kwh = p.kwh || 0;
    sum += kwh;
    const chip = document.createElement('span');
    chip.className = 'day-chip';
    chip.innerHTML = `<b>${dayLabel(ymd)}</b> ${kwh.toFixed(2)}`;
    chipsEl.appendChild(chip);
  });
  // Prefer the server's whole-period delta; fall back to the chip sum (which,
  // thanks to telescoping, is the same value) if an older server omits it.
  const total = (daily.total !== null) ? daily.total : sum;
  grandEl.textContent = total.toFixed(2);
}

function render(days, byDay, title, sub) {
  lastLoad = { days, byDay, title, sub };
  redraw();
}

// Re-slice the cached data to the selected hour window and (re)draw.
function redraw() {
  if (!lastLoad) return;
  const { days, byDay, title, sub } = lastLoad;
  const [from, to] = hourRange();   // inclusive hour indices

  document.getElementById('chart-title').textContent = title;
  document.getElementById('chart-sub').textContent   = sub;

  const present = days.filter(d => byDay[d]);
  const emptyEl = document.getElementById('chart-empty');
  const canvas  = document.getElementById('chart-hours');
  if (present.length === 0) {
    if (chart) { chart.destroy(); chart = null; }
    canvas.style.display = 'none';
    emptyEl.style.display = 'block';
    return;
  }
  canvas.style.display = 'block';
  emptyEl.style.display = 'none';

  // labels[from..to] inclusive of the end boundary (to up to 24).
  const labels = HOUR_LABELS.slice(from, to + 1);
  const datasets = present.map((d, i) => {
    // 24 hourly values + a trailing 24:00 boundary point (0) so the last
    // hour's segment is drawn rather than clipped at 23:00.
    const ext = byDay[d].concat([0]);
    return {
      label: dayLabel(d),
      data: ext.slice(from, to + 1),
      borderColor: color(i, present.length),
      backgroundColor: color(i, present.length),
      borderWidth: 2,
      pointRadius: 0,
      tension: 0.3,
      spanGaps: true,
    };
  });

  if (chart) chart.destroy();
  chart = new Chart(canvas.getContext('2d'), {
    type: 'line',
    data: { labels, datasets },
    options: {
      responsive: true, animation: false,
      interaction: { mode: 'nearest', intersect: false },
      scales: {
        x: { title: { display:true, text:'Hour of day (IST)' } },
        y: { beginAtZero:true, title: { display:true, text:'kWh' } },
      },
      plugins: { legend: { position: 'bottom' } },
    },
  });
}

async function loadWeekly() {
  // Last 7 days including today.
  const days = [];
  for (let i = 6; i >= 0; i--) days.push(addDays(TODAY_YMD, -i));
  const fromIso = days[0] + 'T00:00:00';
  const byDay = await fetchHourly(fromIso, NOW_ISO);
  render(days, byDay,
    'Hourly energy — last 7 days',
    'Each line is one day. X = hour of day (IST), Y = kWh generated that hour.');
  renderTotals(await fetchDaily(fromIso, NOW_ISO));
}

async function loadMonthly(ym) {
  const [y, m] = ym.split('-').map(Number);
  const n = daysInMonth(y, m);
  const days = [];
  for (let d = 1; d <= n; d++) days.push(`${y}-${String(m).padStart(2,'0')}-${String(d).padStart(2,'0')}`);
  const fromIso = days[0] + 'T00:00:00';
  const toIso   = days[n-1] + 'T23:59:59';
  const byDay = await fetchHourly(fromIso, toIso);
  render(days, byDay,
    `Hourly energy — ${MON[m-1]} ${y}`,
    'Each line is one day of the month. X = hour of day (IST), Y = kWh generated that hour.');
  renderTotals(await fetchDaily(fromIso, toIso));
}

const btnWeekly  = document.getElementById('btn-weekly');
const btnMonthly = document.getElementById('btn-monthly');
const monthWrap  = document.getElementById('month-wrap');
const monthInput = document.getElementById('month-input');
const hourFrom   = document.getElementById('hour-from');
const hourTo     = document.getElementById('hour-to');

// From = hour start (00:00..23:00). To = hour boundary / inclusive end
// (01:00..24:00), so the final hour up to midnight can be shown.
for (let h = 0; h <= 23; h++) hourFrom.add(new Option(HOUR_LABELS[h], h));
for (let h = 1; h <= 24; h++) hourTo.add(new Option(HOUR_LABELS[h], h));
hourFrom.value = SOLAR_START_DEFAULT;
hourTo.value   = SOLAR_END_DEFAULT;
hourFrom.addEventListener('change', redraw);
hourTo.addEventListener('change', redraw);

btnWeekly.addEventListener('click', () => {
  btnWeekly.classList.add('on'); btnMonthly.classList.remove('on');
  monthWrap.style.display = 'none';
  loadWeekly();
});
btnMonthly.addEventListener('click', () => {
  btnMonthly.classList.add('on'); btnWeekly.classList.remove('on');
  monthWrap.style.display = '';
  loadMonthly(monthInput.value);
});
monthInput.addEventListener('change', () => loadMonthly(monthInput.value));

// Initial view: weekly.
loadWeekly();
</script>
<?php endif; ?>

</body></html>
