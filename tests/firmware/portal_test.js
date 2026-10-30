// The Wi-Fi setup hotspot's page (firmware setup_ap_page.h), in a phone-sized
// browser against mocked device endpoints. Usage: node portal_test.js <page.h>
// Environment: PLAYWRIGHT (module path, default 'playwright'); SM_SHOT_DIR
// (optional) for screenshots.
const fs = require('fs');
const { chromium } = require(process.env.PLAYWRIGHT || 'playwright');
const src = fs.readFileSync(process.argv[2], 'utf8');
const html = src.slice(src.indexOf('R"SETUPPAGE(') + 'R"SETUPPAGE('.length, src.indexOf(')SETUPPAGE"'));
let fails = 0;
const check = (n, c, d = '') => { console.log((c ? 'PASS ' : 'FAIL ') + n + (d !== '' ? '  -> ' + JSON.stringify(d) : '')); if (!c) fails++; };

(async () => {
  const b = await chromium.launch();
  for (const scheme of ['light', 'dark']) {
    // iPhone 12-15 viewport.
    const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, colorScheme: scheme });
    const p = await ctx.newPage();
    const errs = []; p.on('pageerror', e => errs.push(e.message));
    // Device state behind the mocks.
    let scanV = 1, saved = null, statusCalls = 0, outcome = 'ok', refreshes = 0;
    const nets = [
      { s: 'Home-WiFi', r: -48, e: 1 },
      { s: '<b>Evil</b><img src=x onerror=alert(1)>', r: -70, e: 0 },
      { s: 'Neighbour 5G', r: -80, e: 1 },
      { s: 'Home-WiFi', r: -60, e: 1 },   // duplicate SSID is listed once
    ];
    await p.route('http://192.168.4.1/**', async r => {
      const u = new URL(r.request().url());
      const json = o => r.fulfill({ contentType: 'application/json', body: JSON.stringify(o) });
      if (u.pathname === '/') return r.fulfill({ contentType: 'text/html', body: html });
      if (u.pathname === '/scan.json') {
        // Like the device: v = scans done so far (1 = the scan made when the
        // hotspot opened); refresh queues a new one, which then bumps v.
        if (u.searchParams.get('refresh') === '1') {
          refreshes++; const v = scanV; setTimeout(() => { scanV++; }, 500);
          return json({ queued: true, v, networks: nets });
        }
        return json({ queued: false, v: scanV, networks: nets });
      }
      if (u.pathname === '/save') {
        const f = new URLSearchParams(r.request().postData());
        saved = { ssid: f.get('ssid'), password: f.get('password') };
        statusCalls = 0;
        return json({ ok: true });
      }
      if (u.pathname === '/status.json') {
        const base = { device: 'solar-a3f29c', hotspot: 'SolarMonitor-F29C', ssid: saved ? saved.ssid : '' };
        if (!saved) return json({ ...base, state: 'idle' });
        statusCalls++;
        if (statusCalls < 2) return json({ ...base, state: 'connecting' });
        return json(outcome === 'ok' ? { ...base, state: 'connected', ip: '192.168.1.57' }
                                     : { ...base, state: 'failed', reason: 'wrong_password' });
      }
      return r.fulfill({ status: 302, headers: { Location: 'http://192.168.4.1/' } });
    });

    await p.goto('http://192.168.4.1/');
    await p.waitForSelector('.net', { timeout: 8000 });
    const names = await p.$$eval('.net .n', e => e.map(x => x.textContent));
    check(`${scheme}: networks listed once each`, names.length === 3 && names[0] === 'Home-WiFi', names);
    check(`${scheme}: a network name with HTML in it shows as text`,
          names[1] === nets[1].s && (await p.$$('.nets b, .nets img')).length === 0, names[1]);
    check(`${scheme}: device and hotspot shown`, (await p.textContent('#dev')).includes('SolarMonitor-F29C'));
    // Opening (or re-opening) the page must not start a scan: scanning knocks
    // the phone off the hotspot, and Android reloads the page on reconnect.
    await p.reload(); await p.waitForSelector('.net', { timeout: 8000 });
    check(`${scheme}: loading the page twice starts no scan (cached list)`, refreshes === 0, refreshes);
    await p.click('#rescan');
    await p.waitForFunction(() => document.querySelectorAll('.net').length === 3, null, { timeout: 8000 });
    check(`${scheme}: Scan again asks for exactly one fresh scan`, refreshes === 1, refreshes);
    if (process.env.SM_SHOT_DIR) await p.screenshot({ path: `${process.env.SM_SHOT_DIR}/portal-${scheme}.png`, fullPage: true });

    // Short password is refused before anything is sent.
    await p.click('.net >> nth=0');
    await p.fill('#pass', 'short');
    await p.click('#save');
    check(`${scheme}: short password refused on the page`, saved === null && (await p.textContent('#msg')).includes('8'));

    // Wrong password -> clear message, form usable again.
    outcome = 'bad';
    await p.fill('#pass', 'wrongpass1');
    await p.click('#save');
    await p.waitForFunction(() => document.getElementById('msg').className.includes('bad'), null, { timeout: 8000 });
    check(`${scheme}: wrong password reported`, (await p.textContent('#msg')).startsWith('Wrong password')
          && saved.ssid === 'Home-WiFi' && !(await p.isDisabled('#save')), await p.textContent('#msg'));

    // Right password -> connected.
    outcome = 'ok';
    await p.fill('#pass', 'correct horse');
    await p.click('#show');
    check(`${scheme}: Show reveals the password`, (await p.getAttribute('#pass', 'type')) === 'text');
    await p.click('#save');
    await p.waitForFunction(() => document.getElementById('msg').className.includes('ok'), null, { timeout: 8000 });
    check(`${scheme}: connected shown with the IP`, (await p.textContent('#msg')).includes('192.168.1.57')
          && saved.password === 'correct horse', await p.textContent('#msg'));
    if (process.env.SM_SHOT_DIR) await p.screenshot({ path: `${process.env.SM_SHOT_DIR}/portal-${scheme}-done.png`, fullPage: true });

    // Other network: typed SSID.
    await p.click('#other');
    await p.fill('#ssid', 'Hidden net');
    await p.fill('#pass', '');
    await p.click('#save');
    await p.waitForTimeout(300);
    check(`${scheme}: other (hidden) network with no password is sent`, saved.ssid === 'Hidden net' && saved.password === '', saved);

    check(`${scheme}: no sideways scroll at phone width`,
          await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    check(`${scheme}: no JS errors`, errs.length === 0, errs);
    await ctx.close();
  }
  await b.close();
  console.log(`\n${fails} failure(s)`);
  process.exit(fails ? 1 : 0);
})();
