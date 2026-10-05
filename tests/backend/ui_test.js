// Dashboard browser scenarios against a live backend (see run.sh).
// Environment: SM_BASE; PLAYWRIGHT (module path, default 'playwright');
// SM_SHOT_DIR (optional): save phone screenshots of the readings table there.
// CHARTJS_DIR (optional): a folder holding chart.umd.js and
// chartjs-adapter-date-fns.bundle.min.js, served in place of the CDN when the
// CDN is not reachable from the test machine.
const { chromium } = require(process.env.PLAYWRIGHT || 'playwright');
const BASE = process.env.SM_BASE || 'http://127.0.0.1:8097';
let fails = 0;
const check = (n, c, d='') => { console.log((c ? 'PASS ' : 'FAIL ') + n + (d !== '' ? '  -> ' + JSON.stringify(d) : '')); if (!c) fails++; };
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ timezoneId: 'Asia/Kolkata', viewport: { width: 1250, height: 1000 } });
  const p = await ctx.newPage();
  const errs = []; p.on('pageerror', e => errs.push(e.message));
  const reqs = [];
  let delayDaily = false;
  if (process.env.CHARTJS_DIR) {
    const dir = process.env.CHARTJS_DIR;
    await p.route('https://cdn.jsdelivr.net/**', r => r.fulfill({
      path: dir + (r.request().url().includes('adapter') ? '/chartjs-adapter-date-fns.bundle.min.js' : '/chart.umd.js'),
      contentType: 'application/javascript' }));
  }
  await p.route('**/api/readings.php**', async r => {
    const u = new URL(r.request().url()); reqs.push(u.searchParams);
    if (delayDaily && u.searchParams.get('aggregate') === 'daily') await new Promise(res => setTimeout(res, 1500));
    r.continue();
  });
  await p.goto(BASE + '/dashboard/login.php');
  await p.fill('input[name=username]', 'admin'); await p.fill('input[name=password]', 'adminpass');
  await Promise.all([p.waitForNavigation(), p.click('button[type=submit]')]);
  await p.goto(BASE + '/dashboard/?device_id=dev-c');
  await p.waitForTimeout(1500);
  const txt = s => p.textContent(s);

  // Today
  const todayPeriod = await txt('#stat-total');
  check('Today renders, period total > 0', parseFloat(todayPeriod) > 0, todayPeriod);

  // 7 days: whole days, peak is the real peak
  await p.click('button[data-range="7d"]'); await p.waitForTimeout(1200);
  const fromParam = reqs.filter(q => q.get('aggregate') === 'daily').pop().get('from');
  check('7 days starts at local midnight 6 days back', /T00:00:00$/.test(fromParam), fromParam);
  const rows7 = await p.$$eval('#readings-body tr', t => t.map(r => r.children[0].textContent));
  check('7 days -> 7 whole-day rows', rows7.length === 7, rows7);
  check('Peak on 7 days is the real 2543 W peak, not a daily average', (await txt('#stat-peak')) === '2543', await txt('#stat-peak'));

  // 30 days spans the PZEM reset (10 days ago)
  await p.click('button[data-range="30d"]'); await p.waitForTimeout(1200);
  const total30 = parseFloat(await txt('#stat-total'));
  const rows30 = await p.$$eval('#readings-body tr', t => t.map(r => parseFloat(r.children[1].textContent)));
  const sum30 = rows30.reduce((a, x) => a + x, 0);
  const maxDay = Math.max(...rows30);
  check('30 days: no giant/negative day across the counter reset', maxDay < 20 && Math.min(...rows30) >= 0, { maxDay });
  check('30 days: 30 rows, rows sum to Period total', rows30.length === 30 && Math.abs(sum30 - total30) < 0.05, { n: rows30.length, sum30, total30 });
  const ends = await p.$$eval('#readings-body tr', t => t.map(r => parseFloat(r.children[3].textContent.replace(/,/g, ''))));
  check('meter readings keep rising through the reset', ends.every((v, i) => i === 0 || v <= ends[i - 1] + 1e-6), ends.slice(0, 3));
  check('Meter reading card = newest end reading', Math.abs(parseFloat((await txt('#stat-meter')).replace(/,/g, '')) - ends[0]) < 0.01,
        [await txt('#stat-meter'), ends[0]]);

  // Phone width (360 px, a small Android), with the widest numbers (30 days, 5-digit meter readings):
  // the readings table fits without sideways scrolling, so End and
  // Generated stay on screen.
  await p.setViewportSize({ width: 360, height: 800 });
  await p.waitForTimeout(400);
  const shot = async name => process.env.SM_SHOT_DIR &&
    (await p.locator('#readings-card').screenshot({ path: `${process.env.SM_SHOT_DIR}/${name}.png` }));
  await shot('readings-30d-phone');
  const fit = await p.evaluate(() => {
    const t = document.querySelector('table.readings');
    const card = document.getElementById('readings-card');
    const last = t.querySelector('tbody tr td:last-child').getBoundingClientRect();
    return { table: [t.scrollWidth, t.clientWidth], card: card.getBoundingClientRect().right,
             lastCell: last.right, page: [document.documentElement.scrollWidth, innerWidth],
             heads: [...t.querySelectorAll('thead th')].map(th => th.innerText.trim()) };
  });
  check('phone: readings table fits (no sideways scroll, End column visible)',
        fit.table[0] <= fit.table[1] + 1 && fit.lastCell <= fit.card + 1 && fit.page[0] <= fit.page[1], fit);
  check('phone: short column labels', JSON.stringify(fit.heads.slice(1)) === '["GENERATED","START","END"]', fit.heads);
  await p.click('button[data-range="today"]'); await p.waitForTimeout(1200);
  await shot('readings-today-phone');
  await p.setViewportSize({ width: 1250, height: 1000 });

  // 12 months request starts on the 1st of a month
  await p.click('button[data-range="12m"]'); await p.waitForTimeout(1200);
  const from12 = reqs.filter(q => q.get('aggregate') === 'monthly').pop().get('from');
  check('12 months starts on the 1st of a month at midnight', /-01T00:00:00$/.test(from12), from12);

  // Race: slow 30-day reply must not overwrite a newer Today
  delayDaily = true;
  await p.click('button[data-range="30d"]'); await p.waitForTimeout(100);
  await p.click('button[data-range="today"]'); await p.waitForTimeout(2500);
  check('slow older response is dropped (still Today / per-hour rows)',
        (await txt('#chart-title')).startsWith("Today") && (await txt('#readings-bucket-head')) === 'Hour',
        [await txt('#chart-title'), await txt('#readings-bucket-head')]);
  delayDaily = false;

  // Session expiry -> login page instead of alert
  let dialog = null; p.on('dialog', d => { dialog = d.message(); d.dismiss(); });
  await ctx.clearCookies();
  await p.click('button[data-range="7d"]'); await p.waitForTimeout(1500);
  check('expired session redirects to login (no alert)', p.url().includes('/dashboard/login.php') && dialog === null, [p.url(), dialog]);
  check('no JS errors', errs.length === 0, errs);
  await b.close();
  console.log(`\n${fails} failure(s)`); process.exit(fails ? 1 : 0);
})();
