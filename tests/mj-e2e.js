// Two browser contexts play the mahjong page over the real public MQTT broker.
const { chromium } = require('playwright');
const path = require('path');
const url = 'file://' + path.resolve(process.argv[2] || 'mahjong/src/main/assets/index.html');
const shotDir = process.argv[3] || 'shots';
require('fs').mkdirSync(shotDir, { recursive: true });
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);
(async () => {
  const b = await chromium.launch();
  const mk = async (name) => {
    const c = await b.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 });
    const p = await c.newPage();
    p.on('pageerror', e => log(name, 'PAGEERROR', e.message));
    p.on('console', m => { if (m.type() === 'error') log(name, 'console.error', m.text()); });
    await p.goto(url);
    await p.fill('#nameIn', name);
    return p;
  };
  const host = await mk('房主'), guest = await mk('好友');
  let ok = false;
  try {
    await host.click('#createBtn');
    await host.waitForFunction(() => /^\d{6}$/.test(document.getElementById('waitCode').textContent), null, { timeout: 40000 });
    const code = await host.textContent('#waitCode');
    log('room code', code, 'line', code[0]);
    await host.screenshot({ path: `${shotDir}/1-host-wait.png` });
    await guest.fill('#codeIn', code);
    await guest.click('#joinBtn');
    for (const [p, n] of [[host, 'host'], [guest, 'guest']]) {
      await p.waitForSelector('#table:not([hidden])', { timeout: 40000 });
      await p.waitForFunction(() => document.querySelectorAll('#hand .tile').length >= 13, null, { timeout: 20000 });
      log(n, 'sees table, hand =', await p.$$eval('#hand .tile', e => e.length));
    }
    // host is dealer: discard the drawn tile
    await host.click('#hand .tile:last-child'); await host.click('#hand .tile:last-child');
    await guest.waitForFunction(() => document.querySelectorAll('#oppRiver .tile').length === 1, null, { timeout: 20000 });
    log('guest saw host discard');
    // guest: pass if asked, then discard
    await guest.waitForFunction(() => { const v = currentView(); return v && v.opts && (v.opts.discard || v.opts.pass); }, null, { timeout: 20000 });
    if (await guest.evaluate(() => !!currentView().opts.pass)) { await guest.click('#acts button[data-a="pass"]'); log('guest passed'); }
    await guest.waitForFunction(() => { const v = currentView(); return v && v.opts && v.opts.discard; }, null, { timeout: 20000 });
    await guest.click('#hand .tile:last-child'); await guest.click('#hand .tile:last-child');
    await host.waitForFunction(() => document.querySelectorAll('#oppRiver .tile').length === 1, null, { timeout: 20000 });
    log('host saw guest discard');
    // play out several more turns automatically using the built-in AI for both seats
    for (let i = 0; i < 40; i++) {
      for (const p of [host, guest]) {
        await p.evaluate(() => {
          const v = currentView(); if (!v || !v.opts) return;
          const o = v.opts;
          if (o.hu) return sendAct({ type: 'hu' }); if (o.zimo) return sendAct({ type: 'zimo' });
          if (o.pass) return sendAct({ type: 'pass' });
          if (o.discard) return sendAct({ type: 'discard', t: v.newT != null ? v.newT : v.hand[0] });
        });
        await p.waitForTimeout(250);
      }
      if (await host.evaluate(() => currentView().phase === 'over')) break;
    }
    const hv = await host.evaluate(() => { const v = currentView(); return { seq: v.seq, wall: v.wall, phase: v.phase }; });
    await guest.waitForFunction(s => currentView().seq === s, hv.seq, { timeout: 20000 });
    log('in sync after many turns', JSON.stringify(hv));
    ok = true;
  } catch (e) { log('FAILED', e.message); }
  await host.screenshot({ path: `${shotDir}/2-host.png` });
  await guest.screenshot({ path: `${shotDir}/3-guest.png` });
  await b.close();
  process.exit(ok ? 0 : 1);
})();
