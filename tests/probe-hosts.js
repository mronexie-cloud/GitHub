// Which free file hosts accept an upload from a web page and let a page download it back (CORS)?
const { chromium } = require('playwright');
const HOSTS = {
  catbox: { url: 'https://catbox.moe/user/api.php', field: 'fileToUpload', extra: { reqtype: 'fileupload' }, parse: 'text' },
  litterbox72h: { url: 'https://litterbox.catbox.moe/resources/internals/api.php', field: 'fileToUpload', extra: { reqtype: 'fileupload', time: '72h' }, parse: 'text' },
  uguu: { url: 'https://uguu.se/upload', field: 'files[]', extra: {}, parse: 'pomf' },
  quax: { url: 'https://qu.ax/upload.php', field: 'files[]', extra: {}, parse: 'pomf' },
  tmpfiles: { url: 'https://tmpfiles.org/api/v1/upload', field: 'file', extra: {}, parse: 'tmpfiles' },
  x0st: { url: 'https://0x0.st', field: 'file', extra: {}, parse: 'text' },
  envs: { url: 'https://envs.sh', field: 'file', extra: {}, parse: 'text' },
  x0at: { url: 'https://x0.at', field: 'file', extra: {}, parse: 'text' },
};
async function probe(page, label) {
  return page.evaluate(async (HOSTS) => {
    const out = {};
    const bytes = new Uint8Array(200 * 1024); crypto.getRandomValues(bytes.subarray(0, 65536)); for (let i = 65536; i < bytes.length; i++) bytes[i] = i & 255;
    const sum = a => { let h = 0; for (let i = 0; i < a.length; i += 97) h = (h * 31 + a[i]) >>> 0; return h + ':' + a.length; };
    for (const [name, h] of Object.entries(HOSTS)) {
      const r = { };
      const t0 = Date.now();
      try {
        const fd = new FormData();
        for (const [k, v] of Object.entries(h.extra)) fd.append(k, v);
        fd.append(h.field, new Blob([bytes], { type: 'application/octet-stream' }), 'probe.bin');
        const res = await fetch(h.url, { method: 'POST', body: fd });
        const txt = (await res.text()).trim();
        r.upStatus = res.status;
        let link = null;
        if (h.parse === 'text') link = /^https?:\/\/\S+$/.test(txt) ? txt : null;
        else if (h.parse === 'pomf') { try { const j = JSON.parse(txt); link = j.files && j.files[0] && j.files[0].url; } catch (e) {} }
        else if (h.parse === 'tmpfiles') { try { const j = JSON.parse(txt); link = j.data && j.data.url && j.data.url.replace('tmpfiles.org/', 'tmpfiles.org/dl/'); } catch (e) {} }
        r.link = link || ('NO LINK: ' + txt.slice(0, 120));
        r.upMs = Date.now() - t0;
        if (link) {
          const d = await fetch(link); const buf = new Uint8Array(await d.arrayBuffer());
          r.downStatus = d.status; r.same = sum(buf) === sum(bytes);
          r.acao = d.headers.get('access-control-allow-origin');
        }
      } catch (e) { r.error = String(e.message || e); }
      out[name] = r;
    }
    return out;
  }, HOSTS).then(o => { for (const [k, v] of Object.entries(o)) console.log(label, k.padEnd(13), JSON.stringify(v)); });
}
(async () => {
  const b = await chromium.launch();
  const p = await (await b.newContext()).newPage();
  await p.goto('file://' + process.cwd() + '/README.md');
  await probe(p, '[file://]');
  const q = await (await b.newContext()).newPage();
  await q.goto('https://mronexie-cloud.github.io/GitHub/');
  await probe(q, '[github.io]');
  await b.close();
})();
