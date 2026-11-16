'use strict';
/* 环球收款 phase 2 server. No dependencies: Node 20+ only.
 * The app talks to /api/* with a bearer token; providers call /webhooks/*
 * with signed requests. Sandbox and test keys only: live keys are refused. */
const http = require('node:http');
const crypto = require('node:crypto');
const { Service } = require('./service.js');
const P = require('./providers.js');

function config(env) {
  env = env || process.env;
  const port = Number(env.PORT || 8080);
  const cfg = {
    port,
    dataDir: env.DATA_DIR || './data',
    appToken: env.APP_TOKEN || '',
    publicUrl: (env.PUBLIC_URL || env.RENDER_EXTERNAL_URL || ('http://localhost:' + port)).replace(/\/$/, ''),
    syncMs: Number(env.SYNC_MS || 20000),
    stepMs: Number(env.STEP_MS || 2500),
    webhookSecrets: { airwallex: env.AIRWALLEX_WEBHOOK_SECRET, stripe: env.STRIPE_WEBHOOK_SECRET, btcpay: env.BTCPAY_WEBHOOK_SECRET }
  };
  if (env.AIRWALLEX_CLIENT_ID && env.AIRWALLEX_API_KEY) {
    const base = env.AIRWALLEX_BASE || 'https://api.sandbox.airwallex.com';
    if (/\/\/api\.airwallex\.com/.test(base)) throw new Error('第 2 期只接沙盒：AIRWALLEX_BASE 不能是正式环境 api.airwallex.com');
    cfg.airwallex = { base, clientId: env.AIRWALLEX_CLIENT_ID, apiKey: env.AIRWALLEX_API_KEY, transferReason: env.AIRWALLEX_TRANSFER_REASON };
  }
  if (env.STRIPE_SECRET_KEY) {
    if (!/^(sk|rk)_test_/.test(env.STRIPE_SECRET_KEY)) throw new Error('第 2 期只接测试环境：STRIPE_SECRET_KEY 必须是 sk_test_ 开头的测试密钥');
    cfg.stripe = { secretKey: env.STRIPE_SECRET_KEY, base: env.STRIPE_BASE };
  }
  if (env.BTCPAY_URL && env.BTCPAY_API_KEY && env.BTCPAY_STORE_ID) {
    cfg.btcpay = { url: env.BTCPAY_URL, apiKey: env.BTCPAY_API_KEY, storeId: env.BTCPAY_STORE_ID };
  }
  if (!cfg.appToken) {
    cfg.appToken = crypto.randomBytes(18).toString('base64url');
    cfg.generatedToken = true;
  }
  return cfg;
}

const MAX_BODY = 256 * 1024;
function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = []; let size = 0;
    req.on('data', (c) => { size += c.length; if (size > MAX_BODY) { reject(Object.assign(new Error('请求太大'), { status: 413 })); req.destroy(); } else chunks.push(c); });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}
function send(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Authorization, Content-Type', 'Access-Control-Allow-Methods': 'GET, POST, PATCH, DELETE, OPTIONS',
    'Cache-Control': 'no-store'
  });
  res.end(body);
}
function tokenOk(cfg, req) {
  const h = String(req.headers.authorization || '');
  const given = h.startsWith('Bearer ') ? h.slice(7) : '';
  const a = Buffer.from(given), b = Buffer.from(cfg.appToken);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function createServer(cfg, svc) {
  svc = svc || new Service(cfg);
  const routes = [
    ['GET', /^\/api\/health$/, () => ({ ok: true, providers: svc.providers(), version: '2.0.0' })],
    ['GET', /^\/api\/state$/, () => svc.publicState()],
    ['PATCH', /^\/api\/settings$/, async (b) => { await svc.settings(b); return svc.publicState(); }],
    ['POST', /^\/api\/kyc$/, async () => { await svc.kyc(); return svc.publicState(); }],
    ['POST', /^\/api\/recipients$/, async (b) => ({ recipient: await svc.addRecipient(b), state: svc.publicState() })],
    ['PATCH', /^\/api\/recipients\/([\w-]+)$/, async (b, m) => ({ recipient: await svc.updateRecipient(m[1], b), state: svc.publicState() })],
    ['DELETE', /^\/api\/recipients\/([\w-]+)$/, async (b, m) => { await svc.deleteRecipient(m[1]); return svc.publicState(); }],
    ['GET', /^\/api\/accounts\/([A-Z]{3,4})$/, (b, m) => svc.accountDetails(m[1])],
    ['POST', /^\/api\/incoming$/, async (b) => ({ txn: await svc.simulateIncoming(b), state: svc.publicState() })],
    ['POST', /^\/api\/invoices$/, async (b) => ({ invoice: await svc.createInvoice(b), state: svc.publicState() })],
    ['POST', /^\/api\/invoices\/([\w-]+)\/refresh$/, async (b, m) => ({ invoice: await svc.refreshInvoice(m[1]), state: svc.publicState() })],
    ['POST', /^\/api\/invoices\/([\w-]+)\/simulate-pay$/, async (b, m) => { await svc.simulatePayInvoice(m[1]); return { state: svc.publicState() }; }],
    ['POST', /^\/api\/crypto$/, async (b) => ({ item: await svc.createCryptoInvoice(b), state: svc.publicState() })],
    ['POST', /^\/api\/crypto\/([\w-]+)\/refresh$/, async (b, m) => ({ item: await svc.refreshCrypto(m[1]), state: svc.publicState() })],
    ['POST', /^\/api\/fx\/quote$/, (b) => svc.quote(b)],
    ['POST', /^\/api\/fx\/convert$/, async (b) => ({ txn: await svc.convert(b.quoteId), state: svc.publicState() })],
    ['POST', /^\/api\/transfers$/, async (b) => ({ txn: await svc.send(b), state: svc.publicState() })],
    ['GET', /^\/api\/reconcile$/, () => svc.reconcile()],
    ['POST', /^\/api\/sync$/, async () => { await svc.sync(); return svc.publicState(); }]
  ];
  const hooks = {
    airwallex: { verify: P.verifyAirwallex, handle: (ev) => svc.onAirwallex(ev), enabled: () => !!svc.awx },
    stripe: { verify: P.verifyStripe, handle: (ev) => svc.onStripe(ev), enabled: () => !!svc.stripe },
    btcpay: { verify: P.verifyBTCPay, handle: (ev) => svc.onBTCPay(ev), enabled: () => !!svc.btcpay }
  };

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://x');
    const p = url.pathname;
    try {
      if (req.method === 'OPTIONS') return send(res, 204, {});
      if (req.method === 'GET' && (p === '/' || p === '/paid')) {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        return res.end('<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>环球收款</title>' +
          '<body style="font-family:system-ui;text-align:center;padding:60px 20px">' +
          (p === '/paid' ? '<h2>付款已提交</h2><p>回到环球收款 App 刷新即可看到到账。</p>' : '<h2>环球收款服务器</h2><p>运行中（沙盒 / 测试网）。</p>') + '</body>');
      }
      const hm = p.match(/^\/webhooks\/(airwallex|stripe|btcpay)$/);
      if (hm && req.method === 'POST') {
        const h = hooks[hm[1]], secret = cfg.webhookSecrets[hm[1]];
        const raw = await readBody(req);
        if (!secret || !h.enabled()) return send(res, 404, { error: '这个通道没有启用' });
        if (!h.verify(secret, req.headers, raw)) return send(res, 401, { error: 'invalid signature' });
        let ev; try { ev = JSON.parse(raw); } catch (e) { return send(res, 400, { error: 'bad json' }); }
        await h.handle(ev);
        return send(res, 200, { received: true });
      }
      if (!p.startsWith('/api/')) return send(res, 404, { error: 'not found' });
      if (!tokenOk(cfg, req)) return send(res, 401, { error: '访问令牌不对' });
      const route = routes.find((r) => r[0] === req.method && r[1].test(p));
      if (!route) return send(res, 404, { error: 'not found' });
      const raw = await readBody(req);
      let body = {};
      if (raw) { try { body = JSON.parse(raw); } catch (e) { return send(res, 400, { error: '请求格式不对' }); } }
      const out = await route[2](body, p.match(route[1]));
      return send(res, 200, out);
    } catch (e) {
      // a provider's own 401/404 must not look like the app's token being wrong
      const status = e instanceof P.ProviderError ? 502 : (e.status && e.status < 500 ? e.status : 500);
      if (status >= 500) console.error(new Date().toISOString(), req.method, p, e.message);
      return send(res, status, { error: status === 500 ? '服务器出错了' : e.message });
    }
  });
  let timer = null;
  server.on('listening', () => {
    if (cfg.syncMs > 0) timer = setInterval(() => svc.sync().catch((e) => console.error('sync', e.message)), cfg.syncMs);
  });
  server.on('close', () => clearInterval(timer));
  server.svc = svc;
  return server;
}

if (require.main === module) {
  const cfg = config();
  const server = createServer(cfg);
  server.listen(cfg.port, () => {
    const pv = server.svc.providers();
    console.log('环球收款服务器已启动 :' + cfg.port);
    console.log('通道：Airwallex=' + pv.airwallex + '  Stripe=' + pv.stripe + '  BTCPay=' + pv.btcpay);
    if (cfg.generatedToken) console.log('没有设置 APP_TOKEN，本次随机生成：' + cfg.appToken + '（重启会变，正式部署请设置 APP_TOKEN）');
  });
}

module.exports = { createServer, config };
