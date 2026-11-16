'use strict';
/* Thin clients for the three sandbox providers. Each one is enabled only when
 * its keys are set; otherwise the server falls back to its built-in simulator.
 * Amount conventions: Airwallex and BTCPay take major units ("12.50"),
 * Stripe takes minor units (1250). Inside the app everything is minor units. */
const crypto = require('node:crypto');

class ProviderError extends Error {
  constructor(provider, status, code, message) {
    super(provider + '：' + (message || code || ('HTTP ' + status)));
    this.provider = provider; this.status = status; this.code = code;
  }
}

async function http(provider, url, opts) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), opts.timeout || 20000);
  let res, text;
  try {
    res = await fetch(url, { method: opts.method || 'GET', headers: opts.headers || {}, body: opts.body, signal: ctl.signal });
    text = await res.text();
  } catch (e) {
    throw new ProviderError(provider, 0, 'network', '连接失败（' + (e.name === 'AbortError' ? '超时' : e.message) + '）');
  } finally { clearTimeout(timer); }
  let json = null;
  try { json = text ? JSON.parse(text) : {}; } catch (e) { json = { raw: text }; }
  if (!res.ok) {
    const err = json && (json.error || json);
    const code = err && (err.code || err.type);
    const msg = err && (err.message || err.raw);
    throw new ProviderError(provider, res.status, code, msg);
  }
  return json;
}

const major = (minor) => (Math.round(minor) / 100).toFixed(2);
const minor = (amount) => Math.round(Number(amount) * 100);

// ---------------- Airwallex ----------------
class Airwallex {
  constructor(cfg) {
    this.base = (cfg.base || 'https://api.sandbox.airwallex.com').replace(/\/$/, '');
    this.clientId = cfg.clientId; this.apiKey = cfg.apiKey;
    this.reason = cfg.transferReason || 'family_support';
    this.token = null; this.tokenExp = 0;
  }
  async auth() {
    if (this.token && Date.now() < this.tokenExp - 60000) return this.token;
    const r = await http('Airwallex', this.base + '/api/v1/authentication/login', {
      method: 'POST', headers: { 'x-client-id': this.clientId, 'x-api-key': this.apiKey, 'Content-Type': 'application/json' }, body: '{}'
    });
    this.token = r.token;
    const exp = Date.parse(String(r.expires_at || '').replace(/([+-]\d\d)(\d\d)$/, '$1:$2'));
    this.tokenExp = Number.isFinite(exp) ? exp : Date.now() + 25 * 60000;
    return this.token;
  }
  async req(method, path, body) {
    const token = await this.auth();
    try {
      return await http('Airwallex', this.base + path, {
        method, headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' },
        body: body ? JSON.stringify(body) : undefined
      });
    } catch (e) {
      if (e.status === 401) { this.token = null; } // expired early: next call logs in again
      throw e;
    }
  }
  async globalAccounts() {
    const r = await this.req('GET', '/api/v1/global_accounts?page_size=100');
    return r.items || [];
  }
  async globalAccountFor(cur) {
    const list = await this.globalAccounts();
    return list.find((a) => a.currency === cur && a.status !== 'CLOSED') ||
      list.find((a) => (a.required_features || []).some((f) => f.currency === cur)) || null;
  }
  simulateDeposit(o) {
    return this.req('POST', '/api/v1/simulation/deposit/create', {
      amount: Number(major(o.amount)), global_account_id: o.globalAccountId, payer_name: o.payer,
      reference: o.reference, status: 'SETTLED'
    });
  }
  createPaymentLink(o) {
    return this.req('POST', '/api/v1/pa/payment_links/create', {
      amount: Number(major(o.amount)), currency: o.currency, title: o.title, description: o.description || undefined,
      reference: o.reference, reusable: false
    });
  }
  getPaymentLink(id) { return this.req('GET', '/api/v1/pa/payment_links/' + encodeURIComponent(id)); }
  quote(o) {
    return this.req('POST', '/api/v1/fx/quotes/create', {
      sell_currency: o.from, buy_currency: o.to, sell_amount: major(o.amount), validity: 'MIN_1'
    });
  }
  async convert(o) {
    const body = {
      request_id: o.requestId, sell_currency: o.from, buy_currency: o.to, sell_amount: major(o.amount),
      quote_id: o.quoteId || undefined, reason: 'Wallet conversion', term_agreement: true
    };
    try { return await this.req('POST', '/api/v1/fx/conversions/create', body); } catch (e) {
      if (e.status !== 404) throw e; // older API versions keep conversions at the old path
      return this.req('POST', '/api/v1/conversions/create', body);
    }
  }
  createTransfer(o) {
    return this.req('POST', '/api/v1/transfers/create', {
      request_id: o.requestId, beneficiary_id: o.beneficiaryId, source_currency: o.from, transfer_currency: o.to,
      source_amount: major(o.amount), reason: this.reason, reference: (o.reference || 'Crosspay').slice(0, 35),
      fee_paid_by: 'PAYER'
    });
  }
  getTransfer(id) { return this.req('GET', '/api/v1/transfers/' + encodeURIComponent(id)); }
  async balances() { const r = await this.req('GET', '/api/v1/balances/current'); return Array.isArray(r) ? r : (r.items || []); }
}

// ---------------- Stripe ----------------
function form(obj, prefix, out) {
  out = out || [];
  Object.keys(obj).forEach((k) => {
    const v = obj[k]; if (v == null) return;
    const key = prefix ? prefix + '[' + k + ']' : k;
    if (typeof v === 'object') form(v, key, out);
    else out.push(encodeURIComponent(key) + '=' + encodeURIComponent(v));
  });
  return out.join('&');
}
class Stripe {
  constructor(cfg) { this.key = cfg.secretKey; this.base = (cfg.base || 'https://api.stripe.com').replace(/\/$/, ''); }
  req(method, path, params) {
    return http('Stripe', this.base + path, {
      method, headers: { Authorization: 'Bearer ' + this.key, 'Content-Type': 'application/x-www-form-urlencoded' },
      body: params ? form(params) : undefined
    });
  }
  createCheckout(o) {
    return this.req('POST', '/v1/checkout/sessions', {
      mode: 'payment', success_url: o.successUrl, cancel_url: o.successUrl, client_reference_id: o.invoiceId,
      metadata: { invoice_id: o.invoiceId },
      line_items: { 0: { quantity: 1, price_data: { currency: o.currency.toLowerCase(), unit_amount: o.amount, product_data: { name: o.title, description: o.description || undefined } } } }
    });
  }
  getCheckout(id) { return this.req('GET', '/v1/checkout/sessions/' + encodeURIComponent(id)); }
}

// ---------------- BTCPay Server ----------------
class BTCPay {
  constructor(cfg) { this.base = cfg.url.replace(/\/$/, ''); this.key = cfg.apiKey; this.store = cfg.storeId; }
  req(method, path, body) {
    return http('BTCPay', this.base + '/api/v1/stores/' + encodeURIComponent(this.store) + path, {
      method, headers: { Authorization: 'token ' + this.key, 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined
    });
  }
  createInvoice(o) {
    return this.req('POST', '/invoices', { amount: major(o.amount), currency: o.currency, metadata: { orderId: o.orderId, itemDesc: o.title } });
  }
  getInvoice(id) { return this.req('GET', '/invoices/' + encodeURIComponent(id)); }
}

// ---------------- webhook signatures ----------------
function safeEqual(a, b) {
  const x = Buffer.from(String(a || '')), y = Buffer.from(String(b || ''));
  return x.length === y.length && x.length > 0 && crypto.timingSafeEqual(x, y);
}
const hmac = (secret, data) => crypto.createHmac('sha256', secret).update(data).digest('hex');

// Airwallex: hex HMAC-SHA256 of x-timestamp + raw body.
function verifyAirwallex(secret, headers, raw, now) {
  const ts = headers['x-timestamp'];
  if (!ts || !safeEqual(hmac(secret, ts + raw), headers['x-signature'])) return false;
  return Math.abs((now || Date.now()) - Number(ts)) < 10 * 60000;
}
// Stripe: Stripe-Signature "t=…,v1=…", hex HMAC-SHA256 of `${t}.${raw}`.
function verifyStripe(secret, headers, raw, now) {
  const parts = {}; const v1 = [];
  String(headers['stripe-signature'] || '').split(',').forEach((p) => {
    const i = p.indexOf('='); if (i < 0) return;
    const k = p.slice(0, i).trim(), v = p.slice(i + 1).trim();
    if (k === 'v1') v1.push(v); else parts[k] = v;
  });
  if (!parts.t || !v1.length) return false;
  const expected = hmac(secret, parts.t + '.' + raw);
  if (!v1.some((s) => safeEqual(s, expected))) return false;
  return Math.abs((now || Date.now()) / 1000 - Number(parts.t)) < 300;
}
// BTCPay: BTCPay-Sig "sha256=<hex HMAC-SHA256 of raw body>".
function verifyBTCPay(secret, headers, raw) {
  return safeEqual('sha256=' + hmac(secret, raw), headers['btcpay-sig']);
}

module.exports = { Airwallex, Stripe, BTCPay, ProviderError, verifyAirwallex, verifyStripe, verifyBTCPay, hmac, major, minor };
