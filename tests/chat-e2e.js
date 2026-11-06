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
  const b = await chromium.launch();
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
        if (bub.querySelector('[data-tr]') && /fail|失败/i.test(bub.innerText)) return { failed: true, shown: txt };
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
        ['googleAlt ja>zh', () => trGoogleAlt('よろしくお願いします！', 'zh-CN')]]) {
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
