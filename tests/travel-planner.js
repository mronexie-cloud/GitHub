// Planner checks for the travel guide: runs the app page in Chromium (no network) and checks many plans,
// the order improvement, the edits (delete / swap / move / add), the cost estimate and the calendar file.
// usage: node tests/travel-planner.js            (needs: npm i playwright; npx playwright install chromium)
const { chromium } = require('playwright');
const path = require('path');

const PAGE = 'file://' + path.resolve(__dirname, '../travel/src/main/assets/index.html');
const CASES = [];
const towns = ['吉隆坡', '新山', '槟城', '怡保', '马六甲', '关丹', '新加坡', '曼谷', '合艾', '雅加达', '巴厘岛'];
for (const town of towns) for (const days of [1, 3, 5, 8]) CASES.push({town, days});
CASES.push({town: '吉隆坡', days: 4, mode: 'public'}, {town: '吉隆坡', days: 3, pace: 'tight'}, {town: '新山', days: 6, pace: 'relax', mode: 'public'},
  {town: '吉隆坡', days: 5, must: ['tioman', 'bohtea']}, {town: '槟城', days: 3, roundtrip: false},
  {town: '吉隆坡', days: 4, countries: ['MY'], interests: ['beach', 'nature']}, {town: '新加坡', days: 4, countries: ['SG', 'ID']},
  {town: '吉隆坡', end: '槟城', days: 3}, {town: '新山', end: '吉隆坡', days: 2}, {town: '吉隆坡', end: '新加坡', days: 4, mode: 'public'},
  {town: '吉隆坡', end: '曼谷', days: 6}, {town: '槟城', end: '马六甲', days: 4, interests: ['beach', 'food']}, {town: '吉隆坡', end: '怡保', days: 1},
  {town: '关丹', days: 1, interests: ['city', 'culture']}, {town: '吉隆坡', days: 3, date: '2027-01-10'}, {town: '吉隆坡', days: 6, date: '2026-07-04', party: {a: 2, c: 2}, resident: 'intl'});

(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({viewport: {width: 390, height: 844}});
  const errs = [];
  p.on('pageerror', e => errs.push(String(e)));
  p.on('console', m => { if (m.type() === 'error' && !/ERR_FAILED|net::/.test(m.text())) errs.push(m.text()); });
  await p.route(/^https?:/, r => r.abort());
  await p.goto(PAGE);
  await p.waitForTimeout(500);
  const fails = [];
  let n = 0;
  for (const c of CASES) {
    const res = await p.evaluate(c => {
      const bad = [];
      const t = matchTowns(c.town)[0];
      if (!t) return {bad: ['no town ' + c.town]};
      const f = Object.assign({}, F, {start: t, days: c.days, countries: c.countries || ['MY', 'SG', 'TH', 'ID'], interests: c.interests || ['city', 'culture', 'nature', 'food'],
        mode: c.mode || 'car', pace: c.pace || 'normal', roundtrip: c.roundtrip !== false, fixed: null, must: c.must || [], date: c.date || '2026-12-05',
        party: c.party || {a: 2, c: 0}, resident: c.resident || 'my', end: null});
      if (c.end) { const e = matchTowns(c.end)[0]; if (!e) return {bad: ['no town ' + c.end]}; f.end = {name: e.name, lat: e.lat, lng: e.lng}; }
      const check = (pl, what) => {
        const seen = new Set();
        pl.days.forEach((d, di) => {
          let prev = -1e9;
          d.items.forEach(it => {
            if (it.end < it.start) bad.push(`${what} D${di + 1}: ends before it starts`);
            if (it.start < prev - 1) bad.push(`${what} D${di + 1}: ${it.type} at ${hhmm(it.start)} before ${hhmm(prev)}`);
            prev = it.start;
            if (it.type === 'leg' && segMin(it.segs) <= 0) bad.push(`${what} D${di + 1}: 0-minute leg`);
            if (it.type === 'stop') { if (seen.has(it.id)) bad.push(`${what}: ${it.id} twice`); seen.add(it.id); }
          });
        });
        for (const id of f.must) if (!seen.has(id)) bad.push(`${what}: must-go ${id} missing`);
        // A to B: the trip ends at B, and does not wander far off the way
        if (f.end) {
          const ld = pl.days[pl.days.length - 1], li = ld.items[ld.items.length - 1];
          const endPt = li ? (li.type === 'leg' ? li.toPt : li.type === 'stop' ? PL[li.id] : li.at) : null;
          if (!endPt || km(endPt, f.end) > 3.5) bad.push(`${what}: does not end at ${f.end.name}`);
          const direct = km(f.start, f.end);
          if (what !== 'move' && what !== 'add' && what !== 'swap') for (const id of seen) { const p = PL[id]; if (km(f.start, p) + km(p, f.end) - direct > Math.max(90, direct * 0.45)) bad.push(`${what}: ${id} off the way`); }
        }
        // no flying back to where the last flight came from on the next leg (except the trip home)
        const fl = [];
        pl.days.forEach(d => d.items.forEach(it => { if (it.type === 'leg' && !it.back) it.segs.filter(s => s.type === 'flight').forEach(s => fl.push(s)); }));
        // (a spot the person moves to another day may need a flight back: that is their choice)
        if (what !== 'move') for (let i = 1; i < fl.length; i++) if (fl[i].to.includes(fl[i - 1].from)) bad.push(`${what}: flies back ${fl[i - 1].from}`);
        return seen;
      };
      const pl0 = plan(f);
      check(pl0, 'plan');
      const pl = improvePlan(pl0);
      const stops = check(pl, 'improved');
      if (stopKey(pl) !== stopKey(pl0) || pl.days.length !== pl0.days.length) bad.push('improved plan changed the spots or days');
      const saved = planScore(pl0) - planScore(pl);
      // edits
      CUR_PLAN = pl;
      const ids = [...stops];
      const apply = (mut, what) => {
        const days = planEntries(CUR_PLAN); mut(days);
        const np = plan(f, {days: cleanEntries(days)});
        check(np, what);
        return np;
      };
      if (ids.length >= 2) {
        const del = ids[Math.floor(ids.length / 2)];
        const a = apply(days => days.forEach(d => { const i = d.indexOf(del); if (i >= 0) d.splice(i, 1); }), 'delete');
        if (planStops(a).has(del)) bad.push('delete: still there');
        if (planStops(a).size !== ids.length - 1) bad.push(`delete: ${planStops(a).size} spots, wanted ${ids.length - 1}`);
        if (a.days.length > pl.days.length + 1) bad.push(`delete: ${a.days.length} days from ${pl.days.length}`);
        // swap for a nearby spot
        const di = stopDay(del), cand = choicesNear(PL[del], pl.days[di].date, x => (x.cat === 'food') === (PL[del].cat === 'food'), 3);
        if (cand.length) {
          const s = apply(days => days.forEach(d => { const i = d.indexOf(del); if (i >= 0) d[i] = cand[0].p.id; }), 'swap');
          if (!planStops(s).has(cand[0].p.id) || planStops(s).has(del)) bad.push('swap failed');
        }
        // move the first spot to the last day
        if (pl.days.length > 1) {
          const mv = ids[0], to = pl.days.length - 1;
          const m = apply(days => { days.forEach(d => { const i = d.indexOf(mv); if (i >= 0) d.splice(i, 1); }); insertInto(days[to], mv); }, 'move');
          const where = m.days.findIndex(d => d.items.some(x => x.type === 'stop' && x.id === mv));
          if (where < to) bad.push(`move: ${mv} on day ${where + 1}, wanted ${to + 1}`);
          if (planStops(m).size !== ids.length) bad.push('move: lost spots');
        }
        // add a spot near day 1
        const d0 = pl.days.find(d => d.items.some(x => x.type === 'stop'));
        const add = choicesNear(PL[d0.items.find(x => x.type === 'stop').id], d0.date, () => true, 1)[0];
        if (add) {
          const k = pl.days.indexOf(d0);
          const ad = apply(days => insertInto(days[k], add.p.id), 'add');
          if (!planStops(ad).has(add.p.id)) bad.push('add failed');
        }
      }
      // cost and calendar file
      const tc = tripCost(pl);
      if (!(tc.total >= 0) || !isFinite(tc.total)) bad.push('cost not a number');
      const ics = icsText(pl);
      const lines = ics.split('\r\n');
      const enc = new TextEncoder();
      if (lines.some(l => enc.encode(l).length > 75)) bad.push('ics line over 75 bytes');
      const nev = lines.filter(l => l === 'BEGIN:VEVENT').length;
      if (nev !== lines.filter(l => l === 'END:VEVENT').length || nev !== calEvents(pl).length) bad.push('ics events do not match');
      if (calEvents(pl).some(e => !(e.end > e.start))) bad.push('event without length');
      const route = f.end ? pl.days.map(d => (d.regions || []).map(k => R[k].name.split(' ')[0]).join('+') || '路上').join(' / ') : '';
      return {bad, route, days: pl.days.length, stops: ids.length, saved, cost: Math.round(tc.total), events: nev};
    }, c);
    n++;
    const tag = JSON.stringify(c);
    if (res.bad.length) fails.push(tag + '\n   ' + res.bad.join('\n   '));
    console.log(`${res.bad.length ? 'FAIL' : 'ok  '} ${tag} → ${res.days} 天 ${res.stops} 站${res.route ? ' ' + res.route : ''}，顺序省 ${Math.round(res.saved || 0)} 分，费用 RM${res.cost}，日历 ${res.events} 条`);
  }
  await b.close();
  console.log(`\n${n} plans, ${fails.length} failed, page errors: ${errs.length}`);
  if (fails.length) console.log(fails.join('\n'));
  if (errs.length) console.log(errs.join('\n'));
  process.exit(fails.length || errs.length ? 1 : 0);
})();
