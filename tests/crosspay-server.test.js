// 环球收款 phase 2 server tests. Fake Airwallex / Stripe / BTCPay APIs run locally,
// so every flow (API calls, signed webhooks, sync) is exercised without network.
// Run with: node --test tests/crosspay-server.test.js
const test = require('node:test');
const assert = require('node:assert');
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const { createServer, config } = require('../crosspay-server/src/server.js');
const C = require('../crosspay/src/main/assets/core.js');

const hmac = (k, d) => crypto.createHmac('sha256', k).update(d).digest('hex');
const SECRETS = { airwallex: 'awx_whsec', stripe: 'whsec_test', btcpay: 'btc_whsec' };

// ---------- fake providers ----------
function fakeProviders() {
  const f = { calls: [], transfers: {}, sessions: {}, btc: {}, links: {}, n: 0, convertOldPathOnly: false };
  f.server = http.createServer((req, res) => {
    let raw = '';
    req.on('data', (c) => { raw += c; });
    req.on('end', () => {
      const u = new URL(req.url, 'http://x'), p = u.pathname;
      f.calls.push({ method: req.method, path: p, headers: req.headers, raw });
      const json = (s, o) => { res.writeHead(s, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(o)); };
      const body = () => { try { return JSON.parse(raw); } catch (e) { return {}; } };
      // Airwallex
      if (p === '/awx/api/v1/authentication/login') {
        if (req.headers['x-client-id'] !== 'cid' || req.headers['x-api-key'] !== 'key') return json(401, { code: 'credentials_invalid', message: 'bad key' });
        return json(201, { token: 'tok123', expires_at: new Date(Date.now() + 1800000).toISOString().replace('Z', '+0000').replace(/\.\d+/, '') });
      }
      if (p.startsWith('/awx/')) {
        if (req.headers.authorization !== 'Bearer tok123') return json(401, { code: 'credentials_invalid' });
        if (p === '/awx/api/v1/global_accounts') return json(200, { items: [{ id: 'ga_usd', currency: 'USD', status: 'ACTIVE', account_name: 'Sandbox Co', institution_name: 'Test Bank', account_number: '1234567890', account_routing_type: 'aba', account_routing_value: '026073150', swift_code: 'TESTUS33' }] });
        if (p === '/awx/api/v1/simulation/deposit/create') { const b = body(); return json(201, { id: 'dep_' + (++f.n), amount: b.amount, currency: 'USD', fee_amount: 0.5, status: 'SETTLED', payer_name: b.payer_name, global_account_id: b.global_account_id }); }
        if (p === '/awx/api/v1/pa/payment_links/create') { const b = body(); const id = 'pl_' + (++f.n); f.links[id] = 'UNPAID'; return json(201, { id, url: 'https://checkout.sandbox/pl/' + id, status: 'UNPAID', amount: b.amount, currency: b.currency }); }
        let m = p.match(/^\/awx\/api\/v1\/pa\/payment_links\/(.+)$/);
        if (m) return json(200, { id: m[1], status: f.links[m[1]] });
        if (p === '/awx/api/v1/fx/quotes/create') { const b = body(); return json(201, { quote_id: 'q_' + (++f.n), client_rate: 4.2, sell_amount: Number(b.sell_amount), buy_amount: Math.round(Number(b.sell_amount) * 4.2 * 100) / 100, valid_to_at: new Date(Date.now() + 60000).toISOString() }); }
        if (p === '/awx/api/v1/fx/conversions/create') {
          if (f.convertOldPathOnly) return json(404, { code: 'not_found' });
          const b = body(); return json(201, { conversion_id: 'cv_' + (++f.n), buy_amount: Math.round(Number(b.sell_amount) * 4.2 * 100) / 100, client_rate: 4.2, quote_id: b.quote_id });
        }
        if (p === '/awx/api/v1/conversions/create') { const b = body(); return json(201, { conversion_id: 'cvold_' + (++f.n), buy_amount: Math.round(Number(b.sell_amount) * 4.2 * 100) / 100, client_rate: 4.2 }); }
        if (p === '/awx/api/v1/transfers/create') {
          const b = body();
          if (!b.beneficiary_id || !b.request_id || !b.reason || !b.reference) return json(400, { code: 'invalid_argument', message: 'missing field' });
          const id = 'tr_' + (++f.n);
          f.transfers[id] = 'PROCESSING';
          return json(201, { id, status: 'PROCESSING', fee_amount: 1.5, source_currency: b.source_currency, source_amount: Number(b.source_amount), transfer_currency: b.transfer_currency, amount_beneficiary_receives: Math.round(Number(b.source_amount) * 7.1 * 100) / 100, conversion: { rate: 7.1 } });
        }
        m = p.match(/^\/awx\/api\/v1\/transfers\/(.+)$/);
        if (m) return json(200, { id: m[1], status: f.transfers[m[1]] });
        if (p === '/awx/api/v1/balances/current') return json(200, [{ currency: 'USD', available_amount: 1234.5, pending_amount: 0 }]);
        return json(404, { code: 'not_found' });
      }
      // Stripe
      if (p.startsWith('/stripe/')) {
        if (req.headers.authorization !== 'Bearer sk_test_abc') return json(401, { error: { type: 'invalid_request_error', message: 'bad key' } });
        if (p === '/stripe/v1/checkout/sessions' && req.method === 'POST') {
          const id = 'cs_test_' + (++f.n);
          const params = new URLSearchParams(raw);
          f.sessions[id] = { payment_status: 'unpaid', params };
          return json(200, { id, url: 'https://checkout.stripe.test/' + id, payment_status: 'unpaid' });
        }
        const m = p.match(/^\/stripe\/v1\/checkout\/sessions\/(.+)$/);
        if (m) return json(200, { id: m[1], payment_status: f.sessions[m[1]].payment_status });
        return json(404, { error: { message: 'no' } });
      }
      // BTCPay
      if (p.startsWith('/btc/api/v1/stores/store1/invoices')) {
        if (req.headers.authorization !== 'token btckey') return json(401, { message: 'unauthenticated' });
        if (req.method === 'POST') { const b = body(); const id = 'inv_' + (++f.n); f.btc[id] = { status: 'New', amount: b.amount }; return json(200, { id, checkoutLink: 'https://testnet.btcpay/i/' + id, status: 'New', amount: b.amount }); }
        const id = p.split('/').pop();
        return json(200, { id, status: f.btc[id].status, amount: f.btc[id].amount });
      }
      json(404, {});
    });
  });
  return f;
}

function listen(srv) { return new Promise((r) => srv.listen(0, '127.0.0.1', () => r(srv.address().port))); }

async function boot(env) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'cp-'));
  const cfg = config(Object.assign({ DATA_DIR: dir, APP_TOKEN: 'apptok', SYNC_MS: '0', STEP_MS: '40' }, env));
  const srv = createServer(cfg);
  const port = await listen(srv);
  const base = 'http://127.0.0.1:' + port;
  const api = async (method, p, body, token) => {
    const r = await fetch(base + p, { method, headers: { Authorization: 'Bearer ' + (token || 'apptok'), 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined });
    return { status: r.status, body: await r.json() };
  };
  const hook = async (name, payload, headers) => {
    const r = await fetch(base + '/webhooks/' + name, { method: 'POST', headers: Object.assign({ 'Content-Type': 'application/json' }, headers), body: payload });
    return r.status;
  };
  return { srv, api, hook, base, dir, svc: srv.svc };
}

function balanced(state) {
  const per = {};
  state.txns.forEach((t) => t.entries.forEach((e) => { per[e.cur] = (per[e.cur] || 0) + e.amt; }));
  Object.keys(per).forEach((c) => assert.strictEqual(per[c], 0, 'unbalanced ' + c));
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------- tests ----------
test('config refuses live keys and production Airwallex', () => {
  assert.throws(() => config({ STRIPE_SECRET_KEY: 'sk_live_x' }), /测试/);
  assert.throws(() => config({ AIRWALLEX_CLIENT_ID: 'a', AIRWALLEX_API_KEY: 'b', AIRWALLEX_BASE: 'https://api.airwallex.com' }), /沙盒/);
  assert.strictEqual(config({}).generatedToken, true);
});

test('simulated mode (no keys): every flow works and the books stay balanced', async () => {
  const t = await boot({});
  try {
    assert.strictEqual((await t.api('GET', '/api/state', null, 'wrong')).status, 401);
    const h = await t.api('GET', '/api/health');
    assert.deepStrictEqual(h.body.providers, { airwallex: 'simulated', stripe: 'simulated', btcpay: 'simulated' });

    let r = await t.api('POST', '/api/incoming', { cur: 'USD', amount: 500000, payer: 'Acme' });
    assert.strictEqual(r.status, 200);
    assert.strictEqual(r.body.txn.fee, 1500); // demo schedule 0.3%
    r = await t.api('POST', '/api/incoming', { cur: 'USD', amount: -5 });
    assert.strictEqual(r.status, 400);

    r = await t.api('POST', '/api/invoices', { client: 'Bright', cur: 'EUR', amount: 10000, desc: 'web' });
    assert.strictEqual(r.body.invoice.provider, 'simulated');
    r = await t.api('POST', '/api/invoices/' + r.body.invoice.id + '/simulate-pay');
    assert.strictEqual(C.balances(r.body.state).EUR, 9720);

    r = await t.api('PATCH', '/api/settings', { autoConvert: true, autoTo: 'MYR' });
    r = await t.api('POST', '/api/crypto', { amount: 10000 });
    assert.strictEqual(r.body.item.status, 'settled');
    assert.ok(C.balances(r.body.state).MYR > 0, 'auto-converted');

    const q = (await t.api('POST', '/api/fx/quote', { from: 'USD', to: 'SGD', amount: 100000 })).body;
    assert.ok(q.id && q.receive > 0);
    r = await t.api('POST', '/api/fx/convert', { quoteId: q.id });
    assert.strictEqual(r.body.txn.receive, q.receive);
    assert.strictEqual((await t.api('POST', '/api/fx/convert', { quoteId: q.id })).status, 400, 'quote is single use');

    r = await t.api('POST', '/api/transfers', { recipientId: 'r1', cur: 'USD', amount: 10000 });
    assert.strictEqual(r.status, 403, 'KYC first');
    await t.api('POST', '/api/kyc');
    await sleep(60); await t.api('POST', '/api/sync');
    r = await t.api('POST', '/api/transfers', { recipientId: 'r1', cur: 'USD', amount: 10000 });
    assert.strictEqual(r.body.txn.status, 'pending');
    await sleep(150);
    r = await t.api('POST', '/api/sync');
    assert.strictEqual(r.body.txns.find((x) => x.id && x.type === 'send').status, 'done');
    balanced(r.body);

    r = await t.api('POST', '/api/recipients', { name: 'Ali', channel: 'duitnow', account: '0123456789' });
    assert.strictEqual(r.body.recipient.bank, '马来西亚 ****6789');
    assert.strictEqual((await t.api('DELETE', '/api/recipients/r6')).status, 400, 'own account stays');
    assert.strictEqual((await t.api('GET', '/api/accounts/USD')).body.source, 'simulated');
  } finally { t.srv.closeAllConnections(); t.srv.close(); }
});

test('state survives a restart', async () => {
  const t = await boot({});
  await t.api('POST', '/api/incoming', { cur: 'MYR', amount: 1000, payer: 'x' });
  await t.svc.queue; t.srv.closeAllConnections(); t.srv.close();
  const cfg = config({ DATA_DIR: t.dir, APP_TOKEN: 'apptok', SYNC_MS: '0' });
  const srv2 = createServer(cfg);
  assert.strictEqual(C.balances(srv2.svc.publicState()).MYR, 997);
});

test('sandbox providers: deposits, links, FX, transfers, crypto, webhooks and reconcile', async () => {
  const f = fakeProviders();
  const fp = await listen(f.server), fb = 'http://127.0.0.1:' + fp;
  const t = await boot({
    AIRWALLEX_CLIENT_ID: 'cid', AIRWALLEX_API_KEY: 'key', AIRWALLEX_BASE: fb + '/awx', AIRWALLEX_WEBHOOK_SECRET: SECRETS.airwallex,
    STRIPE_SECRET_KEY: 'sk_test_abc', STRIPE_BASE: fb + '/stripe', STRIPE_WEBHOOK_SECRET: SECRETS.stripe,
    BTCPAY_URL: fb + '/btc', BTCPAY_API_KEY: 'btckey', BTCPAY_STORE_ID: 'store1', BTCPAY_WEBHOOK_SECRET: SECRETS.btcpay,
    PUBLIC_URL: 'https://cp.example'
  });
  try {
    assert.deepStrictEqual((await t.api('GET', '/api/health')).body.providers, { airwallex: 'sandbox', stripe: 'sandbox', btcpay: 'testnet' });

    // Global account details come from Airwallex
    let r = await t.api('GET', '/api/accounts/USD');
    assert.strictEqual(r.body.source, 'airwallex');
    assert.ok(r.body.rows.some((x) => x[1] === '1234567890'));
    assert.strictEqual((await t.api('GET', '/api/accounts/EUR')).body.missing, true);

    // Simulated deposit: booked once with the provider fee, webhook replay is ignored
    r = await t.api('POST', '/api/incoming', { cur: 'USD', amount: 250000, payer: 'Northwind' });
    assert.strictEqual(r.status, 200, JSON.stringify(r.body));
    assert.strictEqual(r.body.txn.fee, 50);
    assert.strictEqual(r.body.txn.net, 249950);
    const login = f.calls.filter((c) => c.path.endsWith('/authentication/login')).length;
    assert.strictEqual(login, 1, 'token reused');
    const dep = { id: 'evt1', name: 'deposit.settled', data: { object: { id: 'dep_1', amount: 2500, currency: 'USD', fee_amount: 0.5, status: 'SETTLED' } } };
    const raw = JSON.stringify(dep), ts = String(Date.now());
    assert.strictEqual(await t.hook('airwallex', raw, { 'x-timestamp': ts, 'x-signature': 'bad' }), 401);
    assert.strictEqual(await t.hook('airwallex', raw, { 'x-timestamp': ts, 'x-signature': hmac(SECRETS.airwallex, ts + raw) }), 200);
    let s = (await t.api('GET', '/api/state')).body;
    assert.strictEqual(C.balances(s).USD, 249950, 'no double booking');
    // a deposit that only arrives by webhook is booked too
    const dep2 = JSON.stringify({ name: 'deposit.settled', data: { object: { id: 'dep_ext', amount: 100, currency: 'USD', status: 'SETTLED' } } });
    await t.hook('airwallex', dep2, { 'x-timestamp': ts, 'x-signature': hmac(SECRETS.airwallex, ts + dep2) });
    s = (await t.api('GET', '/api/state')).body;
    assert.strictEqual(C.balances(s).USD, 259950);

    // Payment link via Stripe Checkout, paid → webhook → booked once
    r = await t.api('POST', '/api/invoices', { client: 'Bright', cur: 'USD', amount: 88050, desc: 'design' });
    const inv = r.body.invoice;
    assert.strictEqual(inv.provider, 'stripe');
    assert.match(inv.url, /checkout\.stripe\.test/);
    const sess = f.sessions[inv.providerRef];
    assert.strictEqual(sess.params.get('line_items[0][price_data][unit_amount]'), '88050');
    assert.strictEqual(sess.params.get('line_items[0][price_data][currency]'), 'usd');
    assert.strictEqual(sess.params.get('metadata[invoice_id]'), inv.id);
    assert.strictEqual((await t.api('POST', '/api/invoices/' + inv.id + '/simulate-pay')).status, 400, 'real links cannot be faked');
    r = await t.api('POST', '/api/invoices/' + inv.id + '/refresh');
    assert.strictEqual(r.body.invoice.status, 'open');
    sess.payment_status = 'paid';
    const ev = JSON.stringify({ type: 'checkout.session.completed', data: { object: { id: inv.providerRef } } });
    const st = Math.floor(Date.now() / 1000);
    assert.strictEqual(await t.hook('stripe', ev, { 'stripe-signature': 't=' + st + ',v1=' + hmac('nope', st + '.' + ev) }), 401);
    assert.strictEqual(await t.hook('stripe', ev, { 'stripe-signature': 't=' + (st - 999) + ',v1=' + hmac(SECRETS.stripe, (st - 999) + '.' + ev) }), 401, 'old signature rejected');
    const goodSig = { 'stripe-signature': 't=' + st + ',v1=' + hmac(SECRETS.stripe, st + '.' + ev) };
    assert.strictEqual(await t.hook('stripe', ev, goodSig), 200);
    assert.strictEqual(await t.hook('stripe', ev, goodSig), 200);
    s = (await t.api('GET', '/api/state')).body;
    assert.strictEqual(s.invoices[0].status, 'paid');
    assert.strictEqual(C.balances(s).USD, 259950 + 88050);

    // FX through an Airwallex quote; falls back to the old conversions path
    let q = (await t.api('POST', '/api/fx/quote', { from: 'USD', to: 'MYR', amount: 10000 })).body;
    assert.strictEqual(q.provider, 'airwallex');
    r = await t.api('POST', '/api/fx/convert', { quoteId: q.id });
    assert.strictEqual(r.body.txn.receive, 42000);
    f.convertOldPathOnly = true;
    q = (await t.api('POST', '/api/fx/quote', { from: 'USD', to: 'MYR', amount: 10000 })).body;
    r = await t.api('POST', '/api/fx/convert', { quoteId: q.id });
    assert.match(r.body.txn.ref, /cvold_/);

    // Transfer: needs KYC and an Airwallex beneficiary id
    await t.api('POST', '/api/kyc'); await sleep(60); await t.api('POST', '/api/sync');
    r = await t.api('POST', '/api/transfers', { recipientId: 'r1', cur: 'USD', amount: 20000 });
    assert.strictEqual(r.status, 400);
    assert.match(r.body.error, /Airwallex 收款人 ID/);
    await t.api('PATCH', '/api/recipients/r1', { beneficiaryId: 'ben_1' });
    r = await t.api('POST', '/api/transfers', { recipientId: 'r1', cur: 'USD', amount: 20000, note: '货款' });
    assert.strictEqual(r.status, 200, JSON.stringify(r.body));
    const tr = r.body.txn;
    assert.strictEqual(tr.fee, 150);
    assert.strictEqual(tr.receive, 142000);
    assert.strictEqual(tr.status, 'pending');
    const trCall = f.calls.find((c) => c.path.endsWith('/transfers/create'));
    assert.strictEqual(JSON.parse(trCall.raw).source_amount, '200.00');
    const trId = tr.ref.replace('awx:transfer:', '');
    f.transfers[trId] = 'PAID';
    const tev = JSON.stringify({ name: 'payout.transfer.paid', data: { object: { id: trId, status: 'PAID' } } });
    const ts2 = String(Date.now());
    assert.strictEqual(await t.hook('airwallex', tev, { 'x-timestamp': ts2, 'x-signature': hmac(SECRETS.airwallex, ts2 + tev) }), 200);
    s = (await t.api('GET', '/api/state')).body;
    assert.strictEqual(s.txns.find((x) => x.id === tr.id).status, 'done');
    // a failed transfer is refunded by the background sync
    r = await t.api('POST', '/api/transfers', { recipientId: 'r1', cur: 'USD', amount: 5000 });
    f.transfers[r.body.txn.ref.replace('awx:transfer:', '')] = 'FAILED';
    const before = C.balance(s, 'wallet', 'USD');
    s = (await t.api('POST', '/api/sync')).body;
    assert.strictEqual(s.txns.find((x) => x.id === r.body.txn.id).status, 'failed');
    assert.strictEqual(C.balance(s, 'wallet', 'USD'), before);

    // Crypto via BTCPay testnet: the webhook triggers a re-read of the invoice
    r = await t.api('POST', '/api/crypto', { amount: 5000 });
    const item = r.body.item;
    assert.strictEqual(item.provider, 'btcpay');
    assert.match(item.url, /testnet\.btcpay/);
    f.btc[item.providerRef].status = 'Settled';
    const bev = JSON.stringify({ type: 'InvoiceSettled', invoiceId: item.providerRef, storeId: 'store1' });
    assert.strictEqual(await t.hook('btcpay', bev, { 'btcpay-sig': 'sha256=' + hmac('wrong', bev) }), 401);
    assert.strictEqual(await t.hook('btcpay', bev, { 'btcpay-sig': 'sha256=' + hmac(SECRETS.btcpay, bev) }), 200);
    assert.strictEqual(await t.hook('btcpay', bev, { 'btcpay-sig': 'sha256=' + hmac(SECRETS.btcpay, bev) }), 200);
    s = (await t.api('GET', '/api/state')).body;
    assert.strictEqual(s.txns.filter((x) => x.ref === 'btcpay:' + item.providerRef).length, 1);

    // Reconcile against Airwallex balances
    r = await t.api('GET', '/api/reconcile');
    assert.strictEqual(r.body.source, 'airwallex');
    assert.strictEqual(r.body.rows.find((x) => x.cur === 'USD').provider, 123450);
    balanced(s);
  } finally { t.srv.closeAllConnections(); t.srv.close(); f.server.closeAllConnections(); f.server.close(); }
});

test('Airwallex payment link is used when Stripe is not configured', async () => {
  const f = fakeProviders();
  const fb = 'http://127.0.0.1:' + await listen(f.server);
  const t = await boot({ AIRWALLEX_CLIENT_ID: 'cid', AIRWALLEX_API_KEY: 'key', AIRWALLEX_BASE: fb + '/awx' });
  try {
    let r = await t.api('POST', '/api/invoices', { client: 'K', cur: 'USD', amount: 1000 });
    assert.strictEqual(r.body.invoice.provider, 'airwallex');
    f.links[r.body.invoice.providerRef] = 'PAID';
    const s = (await t.api('POST', '/api/sync')).body;
    assert.strictEqual(s.invoices[0].status, 'paid');
    assert.strictEqual(C.balances(s).USD, 1000);
    // webhooks without a configured secret are refused
    assert.strictEqual(await t.hook('airwallex', '{}', {}), 404);
    // bad credentials surface as a provider error
    const t2 = await boot({ AIRWALLEX_CLIENT_ID: 'cid', AIRWALLEX_API_KEY: 'wrong', AIRWALLEX_BASE: fb + '/awx' });
    r = await t2.api('GET', '/api/accounts/USD');
    assert.strictEqual(r.status, 502);
    assert.match(r.body.error, /Airwallex/);
    t2.srv.closeAllConnections(); t2.srv.close();
  } finally { t.srv.closeAllConnections(); t.srv.close(); f.server.closeAllConnections(); f.server.close(); }
});
