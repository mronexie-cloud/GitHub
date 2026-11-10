// Security check for the travel guide page: every text that comes from outside the app (text shared from another
// app, pasted links, place search, routes, timetables, the nearby-shop search) carries a script payload; the page
// is driven through its screens, and nothing of it may run or become an element.
// usage: node tests/travel-xss.js
const { chromium } = require('playwright');
const path = require('path');
const PAGE = 'file://' + path.resolve(__dirname, '../travel/src/main/assets/index.html');
const P = `"><img src=x onerror="window.__xss=(window.__xss||0)+1"><svg onload="window.__xss=9">'"<b class=xssmark>`;
const JS_URL = 'javascript:window.__xss=7';

(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({viewport: {width: 390, height: 844}, geolocation: {latitude: 3.1505, longitude: 101.712}, permissions: ['geolocation']});
  const p = await ctx.newPage();
  const errs = [];
  p.on('pageerror', e => errs.push(String(e)));
  await p.route(/^https?:/, r => {
    const u = r.request().url();
    if (/nominatim.*reverse/.test(u)) return r.fulfill({json: {address: {road: P, suburb: P, town: P}}});
    if (/nominatim/.test(u)) return r.fulfill({json: [{display_name: P + ',' + P, lat: '3.15', lon: '101.71'}]});
    if (/photon/.test(u)) return r.fulfill({json: {features: [{geometry: {coordinates: [101.71, 3.15]}, properties: {countrycode: 'MY', name: P}}]}});
    if (/interpreter/.test(u)) return r.fulfill({json: {elements: [1, 2, 3].map(i => ({type: 'node', id: i, lat: 3.15 + i / 500, lon: 101.71,
      tags: {name: P + i, 'name:en': P, amenity: 'restaurant', shop: 'duty_free', opening_hours: P, cuisine: P, phone: P, website: i === 1 ? JS_URL : i === 2 ? 'https://x.test/' + P : P,
        'addr:street': P, 'addr:city': P, 'addr:suburb': P, 'addr:housenumber': P}}))}});
    if (/routing\.openstreetmap/.test(u)) return r.fulfill({json: {code: 'Ok', routes: [{distance: 5000, duration: 900, geometry: {type: 'LineString', coordinates: [[101.712, 3.1505], [101.7116, 3.1578]]},
      legs: [{distance: 5000, duration: 900, steps: [1, 2].map(i => ({name: P, ref: P, destinations: P, distance: 2500, duration: 450, geometry: {type: 'LineString', coordinates: [[101.712, 3.1505], [101.7116, 3.1578]]},
        maneuver: {type: i === 1 ? 'depart' : 'turn', modifier: 'left', location: [101.712, 3.1505]}}))}]}]}});
    if (/transitous/.test(u)) { const t0 = Date.now() + 600e3, iso = x => new Date(x).toISOString(); return r.fulfill({json: {itineraries: [{startTime: iso(t0), endTime: iso(t0 + 1800e3), transfers: 0, duration: 1800,
      legs: [{mode: 'SUBWAY', displayName: P, routeShortName: P, headsign: P, agencyName: P, routeColor: P, routeTextColor: P, duration: 1800, distance: 5000, intermediateStops: [{name: P}],
        from: {name: P, lat: 3.15, lon: 101.71, departure: iso(t0)}, to: {name: P, lat: 3.157, lon: 101.711, arrival: iso(t0 + 1800e3)}}]}]}}); }
    if (/open-meteo/.test(u)) return r.fulfill({json: {daily: {weather_code: [P], temperature_2m_max: [P], temperature_2m_min: [P], precipitation_probability_max: [P]}}});
    if (/er-api/.test(u)) return r.fulfill({json: {time_last_update_utc: P + P, rates: {MYR: 1, SGD: 0.3, THB: 8, IDR: 3600, USD: 0.22}}});
    if (/tile\.openstreetmap/.test(u)) return r.fulfill({status: 404, body: ''});
    return r.abort();
  });
  // saved data from an older or tampered copy
  await p.addInitScript(([P, JS_URL]) => { try {
    if (!localStorage.getItem('tg_myplaces')) localStorage.setItem('tg_myplaces', JSON.stringify([{id: 'myx1', name: P, lat: 3.151, lng: 101.713, cat: 'bar', dur: 1.5, addr: P, gurl: 'https://x.test/' + P}, {id: 'myx2', name: 'js', lat: 3.152, lng: 101.714, cat: 'dining', dur: 1, addr: '', gurl: JS_URL}]));
  } catch (e) {} }, [P, JS_URL]);
  await p.goto(PAGE);
  await p.waitForTimeout(600);
  const step = async (name, fn, wait = 500) => { try { await p.evaluate(fn, [P, JS_URL]); } catch (e) { errs.push(name + ': ' + String(e).split('\n')[0]); } await p.waitForTimeout(wait); };
  const links = async () => p.evaluate(() => [...document.querySelectorAll('a[href]')].map(a => a.getAttribute('href')).filter(h => !/^(https:\/\/|#|grab:|geo:|tel:)/.test(h)));
  const bad = new Set();
  const look = async where => { (await links()).forEach(h => bad.add(where + ': link ' + h.slice(0, 60))); if (await p.evaluate(() => document.querySelectorAll('.xssmark, img[src="x"], svg[onload]').length)) bad.add(where + ': markup injected'); };

  await step('start name', ([P]) => { pickStart({name: P, lat: 3.1505, lng: 101.712}); pickStart({name: P, lat: 5.41, lng: 100.33}, 'end'); });
  await step('locate', () => locate(), 900); await look('locate');
  await step('town search', ([P]) => geocode(P), 700); await look('town search');
  await step('plan', ([P]) => { const f = Object.assign({}, F, {start: {name: P, lat: 3.1505, lng: 101.712}, end: null, days: 2, interests: ['city', 'food', 'bar', 'dining'], must: ['myx1', 'myx2']}); F.start = f.start; F.end = null; F.must = f.must; F.interests = f.interests; F.days = 2; go(); }, 1500); await look('plan');
  await step('edit sheet', () => { const s = document.querySelector('[data-edit]'); if (s) s.click(); }); await look('edit sheet');
  await step('delete + undo bar', () => { const d = document.querySelector('[data-op="del"]'); if (d) d.click(); }, 800); await look('undo');
  await step('cost', () => window.runDemo('cost')); await look('cost');
  await step('calendar sheet', () => window.runDemo('cal')); await look('calendar');
  await step('share text', () => { window.__share = planText ? planText(CUR_PLAN) : ''; });
  await step('save plan', () => { const s = document.querySelector('#pSave, [data-save]'); if (s) s.click(); showTab('pMine'); }); await look('mine tab');
  await step('my place page', () => { while (OV.length) closeOverlay(); openPlace('myx1'); }); await look('my place');
  await step('my place page 2', () => { while (OV.length) closeOverlay(); openPlace('myx2'); }); await look('my place js link');
  await step('nav', () => { while (OV.length) closeOverlay(); openNavTo('myx1'); }, 2000); await look('nav');
  await step('nav transit', () => { const t = document.querySelector('#nMode [data-v="transit"]'); if (t) t.click(); }, 1500); await look('nav transit');
  await step('nav other from', ([P]) => { const o = document.querySelector('#nFrom [data-k="other"]'); if (o) o.click(); const q = document.querySelector('#nQ'); if (q) { q.value = P; q.dispatchEvent(new Event('input', {bubbles: true})); } }, 1500); await look('nav search');
  await step('transit plan', () => { while (OV.length) closeOverlay(); window.runDemo('planpub:吉隆坡:2'); }, 1500);
  await step('transit leg', () => window.runDemo('transit'), 1500); await look('plan transit');
  for (const cat of ['dining', 'ktv', 'bar', 'dutyfree']) {
    await step('near ' + cat, cat0 => { while (OV.length) closeOverlay(); closeSheet(); }, 100);
    await p.evaluate(cat => { EX.near = true; EX.cat = cat; EX.pos = null; showTab('pExplore'); }, cat); await p.waitForTimeout(1200); await look('near ' + cat);
    for (let i = 0; i < 3; i++) { await p.evaluate(i => { while (OV.length) closeOverlay(); const l = document.querySelectorAll('#exList [data-live]')[i]; if (l) l.click(); }, i); await p.waitForTimeout(300); await look('shop page ' + cat); }
  }
  await step('shop to my places', () => { const a = document.querySelector('#lvAdd'); if (a) a.click(); }, 700); await look('confirm from shop');
  await step('save it', () => { const a = document.querySelector('#mnSave'); if (a) a.click(); }, 600); await look('saved shop page');
  for (const t of [P, 'Shop ' + P + '\nJalan ' + P + ', 50000 Kuala Lumpur\nhttps://maps.app.goo.gl/' + P, 'https://www.google.com/maps/place/' + encodeURIComponent(P) + '/@3.15,101.71,17z', JS_URL, 'https://evil.test/' + P + '?q=' + P]) {
    await step('shared text', t0 => { while (OV.length) closeOverlay(); }, 50);
    await p.evaluate(t => window.onShared(t), t); await p.waitForTimeout(2600); await look('shared sheet');
    await step('save shared', () => { const m = document.querySelector('#mnMap'); const a = document.querySelector('#mnSave'); if (a) { if (!document.querySelector('.leaflet-marker-icon') && m) m.dispatchEvent(new MouseEvent('click', {bubbles: true, clientX: 200, clientY: 300})); a.click(); } }, 600); await look('shared place page');
  }
  await step('mine tab again', () => { while (OV.length) closeOverlay(); closeSheet(); showTab('pMine'); }); await look('mine tab 2');
  await step('food tab', () => showTab('pFood')); await look('food');
  await step('explore search', ([P]) => { EX.near = false; EX.cat = 'all'; EX.q = P; showTab('pExplore'); }); await look('explore search');
  // reload: everything saved above is read back
  await p.reload(); await p.waitForTimeout(800); await look('after reload');
  await step('mine after reload', () => showTab('pMine')); await look('mine after reload');
  const saved = await p.evaluate(() => D.places.filter(x => x.mine).map(m => m.gurl).filter(u => u && !/^https:\/\//.test(u)));
  if (await p.evaluate(() => D.places.filter(x => x.mine).length) < 3) bad.add('saved places were not read back');
  saved.forEach(u => bad.add('stored link not https: ' + u.slice(0, 50)));
  const ran = await p.evaluate(() => window.__xss || 0);
  await b.close();
  if (ran) bad.add('payload script ran (' + ran + ')');
  console.log(`xss check: ${bad.size} problems, page errors: ${errs.length}`);
  [...bad].forEach(x => console.log('  ' + x));
  errs.forEach(x => console.log('  err ' + x));
  process.exit(bad.size || errs.length ? 1 : 0);
})();
