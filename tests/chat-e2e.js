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
