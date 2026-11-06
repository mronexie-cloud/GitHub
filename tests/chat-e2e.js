// Four browsers in four languages chat in one room over the real public brokers,
// checking live delivery, automatic translation and history for a late joiner.
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
const url = 'file://' + path.resolve(process.argv[2] || 'chat/src/main/assets/index.html');
const shotDir = process.argv[3] || 'shots';
fs.mkdirSync(shotDir, { recursive: true });
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);
const room = 'e2e-' + Math.random().toString(36).slice(2, 10);

(async () => {
  const b = await chromium.launch({ args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'] });
  const people = {};
  const open = async (key, nick, lang, locale) => {
    const c = await b.newContext({ viewport: { width: 390, height: 780 }, deviceScaleFactor: 2, locale });
    const p = await c.newPage();
    p.on('pageerror', e => log(key, 'PAGEERROR', e.message));
    p.on('console', m => { if (m.type() === 'error') log(key, 'console.error', m.text()); });
    await p.goto(url);
    await p.fill('#nickIn', nick);
    await p.selectOption('#langSel', lang);
    await p.fill('#roomIn', room);
    await p.click('#joinBtn');
    await p.waitForSelector('#chat:not([hidden])');
    people[key] = p;
    return p;
  };
  const send = async (p, text) => { await p.fill('#textIn', text); await p.click('#sendBtn'); };
  // wait until page p shows a message whose original text is `orig` and return what it displays
  const seen = async (p, orig, needTranslation) => {
    const h = await p.waitForFunction(([o, need]) => {
      for (const el of document.querySelectorAll('.msg')) {
        const bub = el.querySelector('.bub');
        const txt = bub.innerText;
        if (!txt.includes(o)) continue;
        if (!need) return { shown: txt };
        const tr = bub.querySelector('.tr');
        if (tr) return { shown: bub.querySelector('.tx').innerText, label: tr.innerText };
        if (bub.querySelector('.fail')) return { failed: true, shown: txt };
      }
      return false;
    }, [orig, needTranslation], { timeout: 30000 });
    return h.jsonValue();
  };
  let ok = true;
  const check = (cond, msg) => { if (!cond) { ok = false; log('CHECK FAILED:', msg); } };
  try {
    const A = await open('A', '小明', 'zh-CN', 'zh-CN');
    // translation services reachable from the page?
    const probe = await A.evaluate(async () => {
      const out = {};
      for (const [name, f] of [['google', () => trGoogle('你好，朋友', 'en')], ['googleAlt', () => trGoogleAlt('你好，朋友', 'en')],
        ['mymemory zh>en', () => trMyMemory('你好，朋友', 'zh-CN', 'en')], ['mymemory ja>zh', () => trMyMemory('よろしくお願いします！', 'ja', 'zh-CN')],
        ['googleAlt ja>zh', () => trGoogleAlt('よろしくお願いします！', 'zh-CN')],
        ['google zh>ne', () => trGoogle('你好，朋友', 'ne')], ['google ne>zh', () => trGoogle('नमस्ते साथी, तिमीलाई कस्तो छ?', 'zh-CN')]]) {
        try { out[name] = (await f()).text; } catch (e) { out[name] = 'ERR ' + e.message; }
      }
      out.secure = window.isSecureContext;
      return out;
    });
    log('translation probe', JSON.stringify(probe));
    const B = await open('B', 'Sarah', 'en', 'en-US');
    const C = await open('C', 'Aiman', 'ms', 'ms-MY');
    for (const [k, p] of [['A', A], ['B', B], ['C', C]]) {
      await p.waitForFunction(() => /\b3\b/.test(document.getElementById('onlineLbl').textContent), null, { timeout: 30000 });
      log(k, 'header:', await p.textContent('#onlineLbl'));
    }

    const zh = '大家好，今天晚上我们一起去吃火锅吧！';
    await send(A, zh);
    let r = await seen(B, zh, true); log('B (English) sees:', JSON.stringify(r)); check(r.label && !r.failed, 'B translation');
    r = await seen(C, zh, true); log('C (Malay) sees:', JSON.stringify(r)); check(r.label && !r.failed, 'C translation');

    const ms = 'Boleh, saya datang pukul lapan malam.';
    await send(C, ms);
    r = await seen(A, ms, true); log('A (Chinese) sees:', JSON.stringify(r)); check(r.label && !r.failed, 'A translation');

    const en = 'Great, I will book a table for four people.';
    await send(B, en);
    r = await seen(C, en, true); log('C (Malay) sees:', JSON.stringify(r)); check(r.label && !r.failed, 'C translation 2');
    r = await seen(A, en, true); log('A (Chinese) sees:', JSON.stringify(r));

    // a late joiner must receive history from the retained message
    const D = await open('D', 'Yuki', 'ja', 'ja-JP');
    await D.waitForFunction(() => document.querySelectorAll('.msg').length >= 3, null, { timeout: 30000 });
    log('D (late joiner) history count:', await D.$$eval('.msg', e => e.length));
    r = await seen(D, zh, true); log('D (Japanese) sees history:', JSON.stringify(r)); check(r.label && !r.failed, 'D translation');
    await A.waitForFunction(() => /\b4\b/.test(document.getElementById('onlineLbl').textContent), null, { timeout: 30000 });
    log('A header after D joined:', await A.textContent('#onlineLbl'));
    const ja = 'よろしくお願いします！';
    await send(D, ja);
    r = await seen(A, ja, true); log('A (Chinese) sees:', JSON.stringify(r)); check(r.label && !r.failed, 'A translation from ja');
    // Nepali speaker joins: sees history in Nepali, and her Nepali message reaches the Chinese speaker translated
    const E = await open('E', 'Sita', 'ne', 'ne-NP');
    r = await seen(E, zh, true); log('E (Nepali) sees history:', JSON.stringify(r)); check(r.label && !r.failed, 'E translation into Nepali');
    const ne = 'नमस्ते सबैलाई, म पनि आउँछु।';
    await send(E, ne);
    r = await seen(A, ne, true); log('A (Chinese) sees Nepali:', JSON.stringify(r)); check(r.label && !r.failed, 'A translation from Nepali');
    r = await seen(B, ne, true); log('B (English) sees Nepali:', JSON.stringify(r)); check(r.label && !r.failed, 'B translation from Nepali');
    // Arabic speaker: interface must be Arabic and right-to-left
    const F = await open('F', 'Omar', 'ar', 'ar-SA');
    r = await seen(F, en, true); log('F (Arabic) sees history:', JSON.stringify(r)); check(r.label && !r.failed, 'F translation into Arabic');
    const ui = {};
    for (const [k, p] of Object.entries(people)) ui[k] = await p.evaluate(() => ({ placeholder: document.getElementById('textIn').placeholder, dir: document.documentElement.dir, online: document.getElementById('onlineLbl').textContent }));
    log('UI language per person:', JSON.stringify(ui));
    const expect = { A: '输入消息', B: 'Message', C: 'Mesej', D: 'メッセージ', E: 'सन्देश', F: 'رسالة' };
    for (const [k, v] of Object.entries(expect)) check(ui[k] && ui[k].placeholder === v, 'UI text for ' + k);
    check(ui.F && ui.F.dir === 'rtl', 'Arabic is right-to-left');
    // A switches to English: A's own Chinese message must now get an English translation
    await A.click('#setBtn'); await A.selectOption('#langSel2', 'en');
    await A.click('#fontSeg [data-fs="1.36"]');
    log('A text size after choosing large:', await A.evaluate(() => getComputedStyle(document.documentElement).fontSize));
    await A.click('#t_close2');
    const own = await A.waitForFunction(o => { for (const el of document.querySelectorAll('.msg.me')) { if (!el.innerText.includes(o)) continue; const t = el.querySelector('.mytr'); if (t) return t.innerText; if (el.querySelector('.fail')) return 'FAILED'; } return false; }, zh, { timeout: 30000 });
    const ownTr = await own.jsonValue(); log('A own message after switching to English:', ownTr); check(ownTr !== 'FAILED', 'own message translated');
    // leaving needs an explicit confirmation; cancelling keeps you in the room
    check(!(await A.$('#backBtn')), 'no back arrow in chat header');
    await A.click('#setBtn'); await A.click('#leaveBtn');
    check(await A.isVisible('#leaveAsk'), 'leave asks for confirmation');
    await A.click('#leaveNo'); await A.click('#t_close2');
    check(await A.isVisible('#chat'), 'still in room after cancelling');
    check(await A.evaluate(() => HIST_KEEP) === 500, 'keeps 500 messages');
    check(await A.evaluate(() => window.onAndroidBack()) === 'bg', 'Android back keeps the room open');
    // ---- photos and videos ----
    const tPhoto = Date.now();
    await A.setInputFiles('#pickImg', 'tests/fixtures/photo.png');
    for (const [k, p] of [['B', B], ['D', D]]) {
      await p.waitForFunction(() => document.querySelectorAll('.msg:not(.me) img.media').length >= 1, null, { timeout: 60000 });
      log(k, 'received photo in', Date.now() - tPhoto, 'ms');
    }
    const tClip = Date.now();
    await C.setInputFiles('#pickVid', 'tests/fixtures/clip.webm');
    await A.waitForFunction(() => document.querySelectorAll('.msg:not(.me) video.media').length >= 1, null, { timeout: 60000 });
    log('A received video clip in', Date.now() - tClip, 'ms');
    const dur = await A.evaluate(() => new Promise(res => { const v = document.querySelector('.msg:not(.me) video.media'); if (v.readyState >= 1) return res(v.duration); v.onloadedmetadata = () => res(v.duration); v.onerror = () => res('error'); setTimeout(() => res('timeout'), 8000); }));
    log('received clip duration:', dur); check(typeof dur === 'number' && dur > 2, 'received video plays');
    const big = await B.evaluate(async () => { const n = 6 * 1048576, a = new Uint8Array(n); for (let i = 0; i < n; i += 65536) crypto.getRandomValues(a.subarray(i, Math.min(n, i + 65536))); const t0 = Date.now(); await sendMedia(new File([a], 'big.webm', { type: 'video/webm' }), 'vid'); return Date.now() - t0; });
    log('B finished sending a 6 MB video in', big, 'ms');
    const tBig = Date.now();
    try {
      await E.waitForFunction(() => document.querySelectorAll('.msg:not(.me) video.media').length >= 2, null, { timeout: 180000 });
      log('E got the 6 MB video', Date.now() - tBig, 'ms after B finished sending');
    } catch (e) { check(false, '6 MB video received'); log('E state', await E.evaluate(() => JSON.stringify([...MS.values()].map(s => ({ st: s.status, got: s.got, n: s.n }))))); }
    const G = await open('G', 'Lena', 'de', 'de-DE');
    const tG = Date.now();
    try {
      await G.waitForFunction(() => document.querySelectorAll('img.media').length >= 1, null, { timeout: 60000 });
      log('G (late joiner) loaded the earlier photo in', Date.now() - tG, 'ms');
    } catch (e) { check(false, 'late joiner loads earlier photo'); }
    // ---- voice call ----
    const callState = p => p.evaluate(async () => {
      if (!CALL) return null;
      const peers = [];
      for (const [u, q] of CALL.peers) {
        let bytes = 0; try { (await q.pc.getStats()).forEach(r => { if (r.type === 'inbound-rtp' && r.kind === 'audio') bytes += r.bytesReceived || 0; }); } catch (e) {}
        peers.push({ name: q.name, state: q.state, bytes });
      }
      return { cid: CALL.cid, peers };
    });
    const waitConnected = (p, n) => p.waitForFunction(n => CALL && [...CALL.peers.values()].filter(q => q.state === 'connected').length >= n, n, { timeout: 30000 });
    await A.click('#callBtn');
    await B.waitForSelector('#callBar [data-call="join"]', { timeout: 20000 });
    log('B sees call bar:', (await B.textContent('#callBar')).replace(/\s+/g, ' ').trim());
    const tJoin = Date.now();
    await B.click('#callBar [data-call="join"]');
    await waitConnected(A, 1); await waitConnected(B, 1);
    log('A and B connected in', Date.now() - tJoin, 'ms');
    await A.waitForTimeout(2500);
    let sa = await callState(A), sb = await callState(B);
    log('A call:', JSON.stringify(sa)); log('B call:', JSON.stringify(sb));
    check(sa.peers[0].bytes > 1000 && sb.peers[0].bytes > 1000, 'audio flows both ways');
    await C.waitForSelector('#callBar [data-call="join"]', { timeout: 20000 });
    await C.click('#callBar [data-call="join"]');
    await waitConnected(A, 2); await waitConnected(B, 2); await waitConnected(C, 2);
    log('three-way call connected');
    await D.waitForFunction(() => /\b3\b/.test(document.getElementById('callBar').textContent), null, { timeout: 15000 });
    log('D (not in call) sees:', (await D.textContent('#callBar')).replace(/\s+/g, ' ').trim());
    await A.waitForTimeout(2000);
    sa = await callState(A); log('A three-way:', JSON.stringify(sa));
    check(sa.peers.length === 2 && sa.peers.every(q => q.state === 'connected' && q.bytes > 1000), 'three-way audio');
    await B.click('#callBar [data-call="end"]');
    await A.waitForFunction(() => CALL && CALL.peers.size === 1, null, { timeout: 15000 });
    log('after B hung up, A has', (await callState(A)).peers.length, 'peer');
    await A.click('#callBar [data-call="end"]'); await C.click('#callBar [data-call="end"]');
    await D.waitForFunction(() => document.getElementById('callBar').hidden, null, { timeout: 20000 });
    log('call ended for everyone');
    // a message A sent must show as delivered (not "sending") on A's own screen
    const pend = await A.$$eval('.msg.me .time', e => e.map(x => x.textContent));
    log('A own message status:', JSON.stringify(pend)); check(pend.every(t => !/发送中|Sending/.test(t)), 'own message delivered');
    await A.waitForTimeout(1500);
  } catch (e) { ok = false; log('FAILED', e.message); }
  for (const [k, p] of Object.entries(people)) await p.screenshot({ path: `${shotDir}/${k}.png` });
  await b.close();
  log(ok ? 'ALL CHECKS PASSED' : 'SOME CHECKS FAILED');
  process.exit(ok ? 0 : 1);
})();
