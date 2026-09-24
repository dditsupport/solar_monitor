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
$selected_meta = null;
foreach ($dev_rows as $d) {
    if ($d['device_id'] === $selected) { $selected_meta = $d; break; }
}
?>
<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Solar Monitor — dashboard</title>
<link rel="stylesheet" href="/dashboard/assets/style.css?v=12">
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.6/dist/chart.umd.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/chartjs-adapter-date-fns@3.0.0/dist/chartjs-adapter-date-fns.bundle.min.js"></script>
</head><body>

<header class="topbar">
  <div class="brand">Solar Monitor</div>
  <div class="user">
    Signed in as <b><?= h($user['username']) ?></b>
    &middot; <a href="/dashboard/report.php<?= $selected ? '?device_id=' . urlencode($selected) : '' ?>">reports</a>
    <?php if (!empty($user['is_admin'])): ?>
      &middot; <a href="/admin/">admin</a>
    <?php endif; ?>
    &middot; <a href="/api/logout.php">sign out</a>
  </div>
</header>

<?php if (!$dev_rows): ?>
<main class="container">
  <div class="card empty">
    <p>You don't have any devices bound to your account yet.</p>
    <?php if (!empty($user['is_admin'])): ?>
      <p>Go to <a href="/admin/">Admin</a> &rarr; Devices to bind one.</p>
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
      <button type="button" data-range="7d">7 days</button>
      <button type="button" data-range="30d">30 days</button>
      <button type="button" data-range="12m">12 months</button>
      <button type="button" id="btn-custom" aria-expanded="false"
              aria-controls="custom-range">Custom</button>
    </div>
    <div class="custom-range" id="custom-range" hidden>
      <label>From <input type="date" id="date-from"></label>
      <label>To <input type="date" id="date-to"></label>
      <button type="button" id="btn-apply">Apply</button>
    </div>
    <?php if ($selected_meta): ?>
      <span class="last-sync">
        Last sync:
        <?php if (!empty($selected_meta['last_sync_at'])): ?>
          <b><?= h($selected_meta['last_sync_at']) ?></b>
          <span class="rel" data-ts="<?= h($selected_meta['last_sync_at']) ?>"></span>
        <?php else: ?>
          <b>never</b>
        <?php endif; ?>
      </span>
    <?php endif; ?>
  </form>

  <section class="cards stats">
    <div class="stat"><span>Current</span>     <div class="stat-val"><b id="stat-now">—</b><i>W</i></div></div>
    <div class="stat"><span>Peak</span>        <div class="stat-val"><b id="stat-peak">—</b><i>W</i></div></div>
    <div class="stat"><span>Period total</span><div class="stat-val"><b id="stat-total">—</b><i>kWh</i></div></div>
    <div class="stat" id="stat-meter-card" title="Latest cumulative meter reading">
      <span>Meter reading</span><div class="stat-val"><b id="stat-meter">—</b><i>kWh</i></div>
    </div>
  </section>

  <section class="card">
    <h2 id="chart-title">Energy</h2>
    <canvas id="chart-energy" height="120"></canvas>
    <p class="meter-span" id="meter-span"></p>
  </section>

  <section class="card" id="readings-card">
    <h2>Meter readings per bar</h2>
    <p class="muted">The cumulative meter reading at the start and end of each
       bar above &mdash; continued from the old meter's baseline, so it reads
       like the physical meter. Generated = end &minus; start, and the newest
       end reading is the Meter reading card above.</p>
    <table class="grid readings">
      <thead>
        <tr>
          <th id="readings-bucket-head">Bucket</th>
          <th>Start reading (kWh)</th>
          <th>End reading (kWh)</th>
          <th>Generated (kWh)</th>
        </tr>
      </thead>
      <tbody id="readings-body"></tbody>
      <tfoot>
        <tr><th>Total</th><th></th><th></th><th id="readings-total">&mdash;</th></tr>
      </tfoot>
    </table>
    <p class="muted" id="readings-empty" style="display:none">No readings in this range.</p>
  </section>

  <section class="card">
    <h2>Power</h2>
    <canvas id="chart-power" height="120"></canvas>
  </section>
</main>

<script>
const DEVICE_ID = <?= json_encode($selected) ?>;

const RANGES = {
  today: {
    aggregate: 'hourly', from: () => startOfToday(),
    // Energy stays bucketed per hour, but power is drawn from raw rows so it
    // shows the device's real logging resolution (5 min by default) instead
    // of one flat hourly average that hides every load switch.
    powerAggregate: 'raw',
    label: "Today's energy (per hour)", energyLabel: 'kWh / hour',
    // Clamp the X axis to the daylight window so the shape of the day
    // is consistent and "nothing yet" is obvious. 06:00-19:00 matches the
    // report page's default solar window.
    xMin: () => hourOfToday(6), xMax: () => hourOfToday(19),
    xUnit: 'hour',
  },
  '7d':  { aggregate: 'daily',  from: () => daysAgo(7),   label: 'Last 7 days',              energyLabel: 'kWh / day',  xUnit: 'day'   },
  '30d': { aggregate: 'daily',  from: () => daysAgo(30),  label: 'Last 30 days',             energyLabel: 'kWh / day',  xUnit: 'day'   },
  '12m': { aggregate: 'monthly',from: () => monthsAgo(12),label: 'Last 12 months',           energyLabel: 'kWh / month',xUnit: 'month' },
};

// A range spec built from the two date pickers. The bucket size follows the
// span — hours for a day or two, days up to a quarter, months beyond that —
// so a custom range is drawn like the fixed range of the same length.
// Returns null if the dates are missing or the wrong way round.
function customRange(){
  const fromVal = document.getElementById('date-from').value;
  const toVal   = document.getElementById('date-to').value;
  if (!fromVal || !toVal) return null;
  const from = new Date(fromVal + 'T00:00:00');
  const to   = new Date(toVal   + 'T23:59:59');
  if (isNaN(from.getTime()) || isNaN(to.getTime()) || to < from) return null;

  const days = (to - from) / 86400e3;
  const agg  = days <= 2 ? 'hourly' : (days <= 92 ? 'daily' : 'monthly');
  const unit = agg === 'hourly' ? 'hour' : (agg === 'daily' ? 'day' : 'month');
  return {
    aggregate: agg,
    // Short spans get the device's real logging resolution for power.
    powerAggregate: agg === 'hourly' ? 'raw' : null,
    from: () => from,
    to:   () => to,
    label: dayText(from) + ' – ' + dayText(to),
    energyLabel: 'kWh / ' + unit,
    xUnit: unit,
  };
}
function dayText(d){ return d.toLocaleDateString([], { day:'numeric', month:'short', year:'numeric' }); }

function startOfToday(){ const d=new Date(); d.setHours(0,0,0,0); return d; }
function hourOfToday(h){ const d=new Date(); d.setHours(h,0,0,0); return d; }
function hoursAgo(h){ return new Date(Date.now() - h*3600e3); }
function daysAgo(d){ return new Date(Date.now() - d*86400e3); }
function monthsAgo(m){ const d=new Date(); d.setMonth(d.getMonth()-m); return d; }
function isoLocal(d){
  const pad=n=>String(n).padStart(2,'0');
  return d.getFullYear()+'-'+pad(d.getMonth()+1)+'-'+pad(d.getDate())+'T'+
         pad(d.getHours())+':'+pad(d.getMinutes())+':'+pad(d.getSeconds());
}

let energyChart, powerChart;
function makeChart(canvasId, type, datasets, yLabel, xOpts, tooltipCallbacks){
  const ctx = document.getElementById(canvasId).getContext('2d');
  const x = {
    type: 'time',
    time: { tooltipFormat: 'PPp', unit: xOpts.unit || undefined },
  };
  if (xOpts.min) x.min = xOpts.min.getTime();
  if (xOpts.max) x.max = xOpts.max.getTime();
  return new Chart(ctx, {
    type, data: { datasets },
    options: {
      responsive: true, animation: false,
      parsing: { xAxisKey: 't', yAxisKey: 'y' },
      scales: {
        x,
        y: { beginAtZero: true, title: { display: true, text: yLabel } },
      },
      plugins: {
        legend: { display: false },
        tooltip: tooltipCallbacks ? { callbacks: tooltipCallbacks } : {},
      },
    },
  });
}

/* ---------- meter readings under the chart ---------- */

// Chart bucket -> a human label matching the X axis tick.
function bucketLabel(iso, unit){
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  if (unit === 'month') return d.toLocaleDateString([], { month:'short', year:'numeric' });
  if (unit === 'hour')  return d.toLocaleDateString([], { month:'short', day:'numeric' }) +
                               ', ' + d.toLocaleTimeString([], { hour:'numeric' });
  return d.toLocaleDateString([], { month:'short', day:'numeric' });
}
// Meter readings come back as raw Wh off the device's cumulative counter.
// `offset` (kWh) carries the same old-meter baseline + manual adjustment that
// Period total uses, so a printed reading matches the physical solar meter.
// It shifts both ends equally, so the difference is untouched either way.
function fmtReading(wh, offset){
  return (wh == null) ? '\u2014' : (wh / 1000 + (offset || 0)).toFixed(3);
}

// Drop leading/trailing buckets where the meter didn't move at all — on the
// Today range those are the night hours, which draw no bar and would only pad
// the table with zeros. Anything in between is kept, so the rows still sum to
// the range's generation.
function trimIdleEdges(points){
  let a = 0, b = points.length - 1;
  while (a <= b && !(points[a].kwh > 0)) a++;
  while (b >= a && !(points[b].kwh > 0)) b--;
  // Nothing generated in the whole range: show it as it is rather than blank.
  return a > b ? points : points.slice(a, b + 1);
}

// One line under the chart: the meter reading the range starts at, the one it
// ends at, and the difference — the same arithmetic as the table, for the
// whole window. Buckets telescope, so the first bucket's start reading and the
// last one's end reading are the window's two ends.
function renderMeterSpan(points, offset){
  const el = document.getElementById('meter-span');
  const first = points[0], last = points[points.length - 1];
  if (!first || !last || first.wh_start == null || last.wh_end == null) {
    el.textContent = '';
    return;
  }
  const start = first.wh_start / 1000 + offset;
  const end   = last.wh_end   / 1000 + offset;
  el.innerHTML = 'Meter reading: ' +
    `<b>${fmtKwh(start)}</b> &rarr; <b>${fmtKwh(end)}</b> kWh ` +
    `<span class="gen">(${fmtKwh(end - start)} kWh)</span>`;
}
// Grouped thousands — these run to five digits once the old meter's baseline
// is on them, and 8,984.85 is a lot easier to read than 8984.85.
function fmtKwh(kwh){
  return kwh.toLocaleString([], { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

// One row per bar: the two meter readings it spans and their difference.
// wh_end - wh_start is exactly the bar's kWh (the server guarantees it), so
// the table is the arithmetic behind the chart, not a second estimate.
function renderReadings(points, R, offset){
  const body   = document.getElementById('readings-body');
  const totalEl= document.getElementById('readings-total');
  const emptyEl= document.getElementById('readings-empty');
  const table  = document.querySelector('table.readings');
  document.getElementById('readings-bucket-head').textContent =
    R.xUnit === 'month' ? 'Month' : (R.xUnit === 'hour' ? 'Hour' : 'Day');
  body.innerHTML = '';

  if (!points.length) {
    table.style.display = 'none';
    emptyEl.style.display = '';
    return;
  }
  table.style.display = '';
  emptyEl.style.display = 'none';

  let sum = 0;
  // Newest first — with 30 rows the day you care about is the one on top.
  trimIdleEdges(points).slice().reverse().forEach(p => {
    const kwh = p.kwh || 0;
    sum += kwh;
    const tr = document.createElement('tr');
    tr.innerHTML =
      `<td>${bucketLabel(p.t, R.xUnit)}</td>` +
      `<td class="mono">${fmtReading(p.wh_start, offset)}</td>` +
      `<td class="mono">${fmtReading(p.wh_end, offset)}</td>` +
      `<td class="mono gen">${kwh.toFixed(3)}</td>`;
    body.appendChild(tr);
  });
  totalEl.textContent = sum.toFixed(3);
}

// device_id + window, shared by the energy and power queries.
function readingsUrl(aggregate, R){
  const to = R.to ? R.to() : null;
  return `/api/readings.php?device_id=${encodeURIComponent(DEVICE_ID)}` +
         `&aggregate=${aggregate}&from=${encodeURIComponent(isoLocal(R.from()))}` +
         (to ? `&to=${encodeURIComponent(isoLocal(to))}` : '');
}

async function loadRange(rangeKey){
  const R = rangeKey === 'custom' ? customRange() : RANGES[rangeKey];
  if (!R) return;   // the click handler has already said why
  document.getElementById('chart-title').textContent = R.label;
  const res = await fetch(readingsUrl(R.aggregate, R), { credentials: 'same-origin' });
  const j   = await res.json();
  if (!j.ok) { alert('Error: ' + j.error); return; }

  // Carry each bar's two meter readings along so the tooltip (and the table
  // below the chart) can show the arithmetic behind the bar.
  const energyPoints = j.points.map(p => ({
    t: p.t, y: p.kwh, wh_start: p.wh_start, wh_end: p.wh_end,
  }));

  // Power is plotted from its own query when the range asks for a finer
  // resolution than the energy bars (raw rows land at the device's log
  // interval — 5 min by default). Raw rows carry `P`, bucketed ones `P_avg`.
  let powerPoints = j.points.map(p => ({ t: p.t, y: p.P_avg }));
  let powerLabel  = 'Avg power (W)';
  if (R.powerAggregate && R.powerAggregate !== R.aggregate) {
    try {
      const pj = await (await fetch(readingsUrl(R.powerAggregate, R),
                                    { credentials: 'same-origin' })).json();
      if (pj.ok && pj.points.length) {
        powerPoints = pj.points.map(p => ({ t: p.t, y: (p.P ?? p.P_avg) }));
        powerLabel  = 'Power (W)';
      }
    } catch (e) { /* keep the bucketed series we already have */ }
  }

  // Continue from the meter this device replaced: capacity_kw holds the old
  // meter's last reading (kWh) at install, adjustment_kwh is a signed manual
  // correction so the cumulative figures match the physical solar meter.
  const baseline = parseFloat(j.capacity_kw) || 0;
  const adjust   = parseFloat(j.adjustment_kwh) || 0;
  const readingOffset = baseline + adjust;

  if (energyChart) energyChart.destroy();
  if (powerChart)  powerChart.destroy();
  const xOpts = {
    unit: R.xUnit,
    min:  R.xMin ? R.xMin() : null,
    max:  R.xMax ? R.xMax() : null,
  };
  energyChart = makeChart('chart-energy', 'bar', [{
    label: R.energyLabel, data: energyPoints, backgroundColor: 'rgba(31,110,42,0.7)',
  }], R.energyLabel, xOpts, {
    afterBody: (items) => {
      const r = (items[0] && items[0].raw) || {};
      if (r.wh_start == null || r.wh_end == null) return [];
      return [
        'Start reading: ' + fmtReading(r.wh_start, readingOffset) + ' kWh',
        'End reading: '   + fmtReading(r.wh_end,   readingOffset) + ' kWh',
        'Generated: '     + (r.y || 0).toFixed(3) + ' kWh',
      ];
    },
  });
  renderReadings(j.points, R, readingOffset);
  renderMeterSpan(j.points, readingOffset);
  // A raw day is ~288 points at a 5-min log interval; full-size markers turn
  // that into a solid band, so shrink them once the series gets dense.
  const dense = powerPoints.length > 60;
  powerChart = makeChart('chart-power', 'line', [{
    label: powerLabel, data: powerPoints, borderColor: '#c97a1a',
    tension: dense ? 0.1 : 0.25,
    pointRadius: dense ? 0 : 3,
    pointHitRadius: 6,
    borderWidth: dense ? 1.5 : 2,
  }], 'W', xOpts);

  // Stats. Period total is what the meter generated inside the selected range:
  // end reading - start reading (server total_kwh; the telescoping bars sum to
  // the same number, so the bar sum is a safe fallback on an older server).
  // The old-meter baseline is deliberately NOT added — adding it made every
  // range read ~21,000 kWh and hid the actual difference. The cumulative
  // figure lives in its own "Meter reading" card below.
  const periodTotal = (typeof j.total_kwh === 'number')
    ? j.total_kwh
    : energyPoints.reduce((a, p) => a + (p.y || 0), 0);
  const peakP = powerPoints.reduce((m, p) => Math.max(m, p.y || 0), 0);
  document.getElementById('stat-total').textContent = periodTotal.toFixed(2);
  document.getElementById('stat-peak').textContent  = peakP.toFixed(0);

  // Meter reading = where the meter stands now: the device's newest reading
  // plus the old-meter baseline, so it matches the physical meter's display.
  // It's the same number whichever range is selected, and it's the End
  // reading on the newest row of the table below.
  document.getElementById('stat-meter').textContent =
    (typeof j.meter_wh === 'number') ? fmtKwh(j.meter_wh / 1000 + readingOffset) : '—';
  document.getElementById('stat-meter-card').title = j.meter_at
    ? 'Latest cumulative meter reading, logged ' + new Date(j.meter_at).toLocaleString()
    : 'Latest cumulative meter reading';

  // "Current" comes from a raw query of the last hour
  loadLive();
}

async function loadLive(){
  const from = isoLocal(hoursAgo(1));
  const url = `/api/readings.php?device_id=${encodeURIComponent(DEVICE_ID)}&aggregate=raw&from=${encodeURIComponent(from)}`;
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
}

const customToggle = document.getElementById('btn-custom');
const customPanel  = document.getElementById('custom-range');
const applyBtn     = document.getElementById('btn-apply');

function setActiveRange(btn){
  document.querySelectorAll('.range-buttons button').forEach(x => x.classList.remove('on'));
  btn.classList.add('on');
}
function showCustomPanel(show){
  customPanel.hidden = !show;
  customToggle.setAttribute('aria-expanded', String(show));
}

// The fixed ranges load straight away and put the date filter away again.
document.querySelectorAll('.range-buttons button[data-range]').forEach(b => {
  b.addEventListener('click', () => {
    showCustomPanel(false);
    setActiveRange(b);
    loadRange(b.dataset.range);
  });
});

// "Custom" only reveals the From/To filter — Apply is what loads the range,
// so the chart doesn't change under you while you're still picking dates.
customToggle.addEventListener('click', () => {
  const opening = customPanel.hidden;
  showCustomPanel(opening);
  if (opening) document.getElementById('date-from').focus();
});

applyBtn.addEventListener('click', () => {
  // Half-filled or reversed dates leave the current view alone rather than
  // blanking the chart.
  if (!customRange()) {
    alert('Pick a From and To date, with From on or before To.');
    return;
  }
  setActiveRange(customToggle);   // Custom stays lit as the active range
  loadRange('custom');
});

// Custom range starts on the last week and can't be pointed at the future.
(function initCustomDates(){
  const pad = n => String(n).padStart(2, '0');
  const ymd = d => d.getFullYear() + '-' + pad(d.getMonth()+1) + '-' + pad(d.getDate());
  const from = document.getElementById('date-from');
  const to   = document.getElementById('date-to');
  const today = ymd(new Date());
  from.value = ymd(daysAgo(6));
  to.value   = today;
  from.max = to.max = today;
  // Enter in a date field applies the range; without this the surrounding
  // form submits and the page reloads on the old range.
  [from, to].forEach(el => el.addEventListener('keydown', e => {
    if (e.key === 'Enter') { e.preventDefault(); applyBtn.click(); }
  }));
})();

// initial load: today
document.querySelector('.range-buttons button[data-range="today"]').click();

// "Last sync" relative time. Server timestamp is already in the
// configured APP_TIMEZONE (Asia/Kolkata), so treat it as local.
(function annotateLastSync(){
  const el = document.querySelector('.last-sync .rel');
  if (!el) return;
  const ts = el.dataset.ts;
  if (!ts) return;
  const d = new Date(ts.replace(' ', 'T'));
  if (isNaN(d.getTime())) return;
  const tick = () => {
    const secs = Math.max(0, Math.round((Date.now() - d.getTime()) / 1000));
    let s;
    if      (secs < 60)        s = `${secs}s ago`;
    else if (secs < 3600)      s = `${Math.round(secs/60)} min ago`;
    else if (secs < 86400)     s = `${Math.round(secs/3600)} h ago`;
    else                       s = `${Math.round(secs/86400)} d ago`;
    el.textContent = ` (${s})`;
  };
  tick();
  setInterval(tick, 30_000);
})();
</script>
<?php endif; ?>

</body></html>
