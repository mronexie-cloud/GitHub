'use strict';
/* Business logic: every money movement is mirrored in the double-entry ledger
 * (the app's core.js), and each provider object is booked at most once,
 * keyed by its provider reference, whether it arrives by API response,
 * webhook or the background sync. */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const C = require('./core.js');
const P = require('./providers.js');

const STEP_MS = 2500; // simulator speed for transfers and KYC

function rid() { return crypto.randomUUID(); }
function fail(msg, status) { const e = new Error(msg); e.status = status || 400; return e; }

class Service {
  constructor(cfg) {
    this.cfg = cfg;
    this.file = path.join(cfg.dataDir, 'state.json');
    this.awx = cfg.airwallex ? new P.Airwallex(cfg.airwallex) : null;
    this.stripe = cfg.stripe ? new P.Stripe(cfg.stripe) : null;
    this.btcpay = cfg.btcpay ? new P.BTCPay(cfg.btcpay) : null;
    this.quotes = new Map();
    this.queue = Promise.resolve();
    this.load();
  }

  // ---------- storage ----------
  load() {
    try {
      this.S = JSON.parse(fs.readFileSync(this.file, 'utf8'));
    } catch (e) {
      this.S = C.newState(Date.now());
      this.S.recipients = C.seedDemo(C.newState(Date.now()), Date.now()).recipients;
      this.S.stores = {};
      this.S.autoConvert = false; this.S.autoTo = 'USD';
      this.S.crypto = [];
      this.S.mode = 'server';
      this.save();
    }
  }
  save() {
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    const tmp = this.file + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(this.S));
    fs.renameSync(tmp, this.file);
  }
  // One operation at a time, so a webhook and a request never book the same thing twice.
  run(fn) {
    const p = this.queue.then(() => fn());
    this.queue = p.then(() => this.save(), () => this.save()).catch(() => {});
    return p;
  }

  providers() {
    return {
      airwallex: this.awx ? 'sandbox' : 'simulated',
      stripe: this.stripe ? 'sandbox' : 'simulated',
      btcpay: this.btcpay ? 'testnet' : 'simulated'
    };
  }
  publicState() {
    const S = this.S;
    return {
      v: 1, seq: S.seq, created: S.created, kyc: S.kyc, kycTs: S.kycTs, display: S.display, txns: S.txns, invoices: S.invoices,
      stores: S.stores, recipients: S.recipients, autoConvert: S.autoConvert, autoTo: S.autoTo, crypto: S.crypto || [],
      providers: this.providers()
    };
  }

  // ---------- settings / recipients / KYC ----------
  settings(o) {
    return this.run(() => {
      if (o.display && C.CURRENCIES[o.display]) this.S.display = o.display;
      if (typeof o.autoConvert === 'boolean') this.S.autoConvert = o.autoConvert;
      if (o.autoTo && C.CURRENCIES[o.autoTo]) this.S.autoTo = o.autoTo;
      if (o.stores && typeof o.stores === 'object') {
        Object.keys(o.stores).forEach((k) => { if (C.STORES.some((s) => s.id === k)) this.S.stores[k] = !!o.stores[k]; });
      }
    });
  }
  addRecipient(o) {
    return this.run(() => {
      const name = String(o.name || '').trim().slice(0, 60), acct = String(o.account || '').trim();
      if (!name || acct.length < 4) throw fail('请填写姓名和账号');
      if (!C.CHANNELS[o.channel] || o.channel === 'own_myr') throw fail('收款方式不正确');
      const r = {
        id: 'r' + Date.now().toString(36), name, channel: o.channel,
        bank: C.CHANNELS[o.channel].label.split(' ')[0] + ' ****' + acct.slice(-4),
        beneficiaryId: String(o.beneficiaryId || '').trim() || undefined
      };
      this.S.recipients.push(r);
      return r;
    });
  }
  updateRecipient(id, o) {
    return this.run(() => {
      const r = this.S.recipients.find((x) => x.id === id);
      if (!r) throw fail('找不到这个收款人', 404);
      if ('beneficiaryId' in o) r.beneficiaryId = String(o.beneficiaryId || '').trim() || undefined;
      return r;
    });
  }
  deleteRecipient(id) {
    return this.run(() => {
      const r = this.S.recipients.find((x) => x.id === id);
      if (!r) throw fail('找不到这个收款人', 404);
      if (r.self) throw fail('本人账户不能删除');
      this.S.recipients = this.S.recipients.filter((x) => x.id !== id);
    });
  }
  // KYC is not part of the sandbox scope: it stays simulated (submitted, approved a few seconds later).
  kyc() {
    return this.run(() => { if (this.S.kyc !== 'verified') { this.S.kyc = 'pending'; this.S.kycTs = Date.now(); } });
  }

  // ---------- money in ----------
  async accountDetails(cur) {
    if (!C.CURRENCIES[cur] || cur === 'USDT') throw fail('不支持的币种');
    if (!this.awx) return { source: 'simulated', rows: C.fakeAccount(cur, cur.charCodeAt(0) * 7 + cur.charCodeAt(2)) };
    const a = await this.awx.globalAccountFor(cur);
    if (!a) return { source: 'airwallex', missing: true, rows: [] };
    const rows = [['账户名', a.account_name], ['银行', a.institution_name], ['账号', a.account_number]];
    if (a.account_routing_type) rows.push([String(a.account_routing_type).toUpperCase(), a.account_routing_value]);
    (a.alternate_account_identifiers ? [].concat(a.alternate_account_identifiers) : []).forEach((x) => { if (x && x.type) rows.push([x.type, x.value]); });
    if (a.iban) rows.push(['IBAN', a.iban]);
    if (a.swift_code) rows.push(['SWIFT', a.swift_code]);
    return { source: 'airwallex', id: a.id, rows: rows.filter((r) => r[1]) };
  }

  // A deposit from a payer (or a store payout). Sandbox: Airwallex simulation API into the
  // currency's Global Account; the settled deposit is booked by its deposit id.
  simulateIncoming(o) {
    const amount = Math.round(o.amount);
    if (!C.CURRENCIES[o.cur] || o.cur === 'USDT') throw fail('不支持的币种');
    if (!(amount > 0) || amount > 1e11) throw fail('请输入正确的金额');
    const kind = o.kind === 'store' ? 'store' : 'account';
    const payer = String(o.payer || '海外付款方').slice(0, 60);
    const title = kind === 'store' ? payer + '回款' : payer + ' 付款';
    if (!this.awx) {
      return this.run(() => C.collect(this.S, { method: kind, cur: o.cur, amount, title, sub: kind === 'store' ? '电商平台回款' : o.cur + ' 收款账户入账' }));
    }
    return (async () => {
      const ga = await this.awx.globalAccountFor(o.cur);
      if (!ga) throw fail('Airwallex 沙盒里还没有 ' + o.cur + ' 全球账户，请先在 Airwallex 后台开一个');
      const dep = await this.awx.simulateDeposit({ globalAccountId: ga.id, amount, payer, reference: 'CP' + Date.now().toString(36) });
      return this.run(() => this.bookDeposit(dep, { kind, title, cur: o.cur, amount }));
    })();
  }
  bookDeposit(dep, hint) {
    hint = hint || {};
    const status = String(dep.status || 'SETTLED').toUpperCase();
    if (status !== 'SETTLED') return null;
    const ref = 'awx:deposit:' + dep.id;
    const seen = C.findByRef(this.S, ref);
    if (seen) return seen;
    const cur = dep.currency || hint.cur;
    const amount = dep.amount != null ? P.minor(dep.amount) : hint.amount;
    const fee = dep.fee_amount != null ? Math.min(amount, P.minor(dep.fee_amount)) : 0;
    if (!C.CURRENCIES[cur] || !(amount > 0)) return null;
    return C.collect(this.S, {
      method: hint.kind === 'store' ? 'store' : 'account', cur, amount, fee, ref,
      title: hint.title || ((dep.payer_name || '海外付款方') + ' 付款'), sub: cur + ' 全球账户入账（Airwallex 沙盒）'
    });
  }

  createInvoice(o) {
    const amount = Math.round(o.amount), client = String(o.client || '').trim().slice(0, 80);
    if (!client) throw fail('请填写客户名称');
    if (!(amount > 0) || amount > 1e10) throw fail('请输入正确的金额');
    if (!C.CURRENCIES[o.cur] || o.cur === 'USDT') throw fail('不支持的币种');
    const desc = String(o.desc || '').trim().slice(0, 120);
    const id = 'INV-' + (1001 + this.S.invoices.length) + '-' + crypto.randomBytes(2).toString('hex').toUpperCase();
    const inv = { id, client, cur: o.cur, amount, desc, status: 'open', ts: Date.now(), provider: 'simulated' };
    return (async () => {
      const title = client + (desc ? ' · ' + desc : '');
      if (this.stripe) {
        const cs = await this.stripe.createCheckout({ invoiceId: id, amount, currency: o.cur, title, description: desc, successUrl: this.cfg.publicUrl + '/paid' });
        Object.assign(inv, { provider: 'stripe', providerRef: cs.id, url: cs.url });
      } else if (this.awx) {
        const pl = await this.awx.createPaymentLink({ amount, currency: o.cur, title, description: desc, reference: id });
        Object.assign(inv, { provider: 'airwallex', providerRef: pl.id, url: pl.url });
      }
      return this.run(() => { this.S.invoices.push(inv); return inv; });
    })();
  }
  // Ask the provider whether the invoice was paid, and book it once.
  async refreshInvoice(id) {
    const inv = this.S.invoices.find((x) => x.id === id);
    if (!inv) throw fail('找不到这张账单', 404);
    if (inv.status === 'paid' || inv.provider === 'simulated') return inv;
    let paid = false;
    if (inv.provider === 'stripe' && this.stripe) paid = (await this.stripe.getCheckout(inv.providerRef)).payment_status === 'paid';
    if (inv.provider === 'airwallex' && this.awx) paid = String((await this.awx.getPaymentLink(inv.providerRef)).status).toUpperCase() === 'PAID';
    if (paid) await this.run(() => this.markInvoicePaid(inv));
    return inv;
  }
  markInvoicePaid(inv, fee) {
    if (inv.status === 'paid') return null;
    const t = C.collect(this.S, {
      method: 'link', cur: inv.cur, amount: inv.amount, fee: inv.provider === 'simulated' ? undefined : (fee || 0),
      title: inv.client + ' · ' + (inv.desc || inv.id), ref: 'inv:' + inv.id,
      sub: inv.provider === 'stripe' ? '收款链接（Stripe 测试）' : inv.provider === 'airwallex' ? '收款链接（Airwallex 沙盒）' : '收款链接（银行卡）'
    });
    inv.status = 'paid'; inv.paidTs = t.ts; inv.net = t.net;
    return t;
  }
  simulatePayInvoice(id) {
    return this.run(() => {
      const inv = this.S.invoices.find((x) => x.id === id);
      if (!inv) throw fail('找不到这张账单', 404);
      if (inv.provider !== 'simulated') throw fail('这是真实的测试付款链接，请在付款页用测试卡付款');
      return this.markInvoicePaid(inv) || inv;
    });
  }

  // USDT / crypto: a BTCPay invoice priced in USD. Settled invoices are booked in the invoice currency.
  createCryptoInvoice(o) {
    const amount = Math.round(o.amount);
    if (!(amount > 0) || amount > 1e9) throw fail('请输入正确的金额');
    const id = 'CR-' + Date.now().toString(36).toUpperCase();
    if (!this.btcpay) {
      return this.run(() => {
        const t = C.collect(this.S, { method: 'crypto', cur: 'USDT', amount, title: 'USDT 到账 · ' + (o.network || 'TRC20'), ref: 'crypto:' + id });
        const item = { id, amount, cur: 'USDT', status: 'settled', provider: 'simulated', ts: Date.now() };
        this.S.crypto.push(item);
        this.autoConvertCrypto(t);
        return item;
      });
    }
    return (async () => {
      const inv = await this.btcpay.createInvoice({ amount, currency: 'USD', orderId: id, title: '环球收款 ' + id });
      return this.run(() => {
        const item = { id, amount, cur: 'USD', status: 'new', provider: 'btcpay', providerRef: inv.id, url: inv.checkoutLink, ts: Date.now() };
        this.S.crypto.push(item);
        return item;
      });
    })();
  }
  async refreshCrypto(id) {
    const item = (this.S.crypto || []).find((x) => x.id === id || x.providerRef === id);
    if (!item) throw fail('找不到这笔加密收款', 404);
    if (item.provider !== 'btcpay' || item.status === 'settled' || item.status === 'expired') return item;
    const inv = await this.btcpay.getInvoice(item.providerRef); // never trust a webhook body alone
    await this.run(() => {
      const st = String(inv.status || '').toLowerCase();
      if (st === 'settled' || st === 'complete' || st === 'confirmed') {
        if (!C.findByRef(this.S, 'btcpay:' + item.providerRef)) {
          const amount = inv.amount != null ? P.minor(inv.amount) : item.amount;
          C.collect(this.S, { method: 'crypto', cur: item.cur, amount, fee: 0, title: '加密货币收款 · BTCPay 测试网', ref: 'btcpay:' + item.providerRef, sub: 'BTCPay Server 结算' });
        }
        item.status = 'settled';
      } else if (st === 'expired' || st === 'invalid') item.status = 'expired';
      else if (st === 'processing') item.status = 'processing';
    });
    return item;
  }
  autoConvertCrypto(t) {
    if (!this.S.autoConvert || this.S.autoTo === t.cur || this.awx) return; // with a real provider this would need a real conversion
    try { C.convert(this.S, { from: t.cur, to: this.S.autoTo, amount: t.net }); } catch (e) { /* not enough after fees */ }
  }

  // ---------- FX ----------
  async quote(o) {
    const amount = Math.round(o.amount);
    if (!C.CURRENCIES[o.from] || !C.CURRENCIES[o.to] || o.from === o.to) throw fail('请选择两种不同的币种');
    if (!(amount > 0)) throw fail('请输入金额');
    if (C.balance(this.S, 'wallet', o.from) < amount) throw fail('余额不足');
    let q;
    if (this.awx && o.from !== 'USDT' && o.to !== 'USDT') {
      const r = await this.awx.quote({ from: o.from, to: o.to, amount });
      q = { id: r.quote_id, provider: 'airwallex', from: o.from, to: o.to, amount, fee: 0, receive: P.minor(r.buy_amount), rate: Number(r.client_rate),
        expires: Date.parse(String(r.valid_to_at || '').replace(/([+-]\d\d)(\d\d)$/, '$1:$2')) || Date.now() + 60000 };
    } else {
      const rate = C.liveRate(o.from, o.to), qq = C.quoteConvert(o.from, o.to, amount, rate);
      q = { id: 'sim-' + rid(), provider: 'simulated', from: o.from, to: o.to, amount, fee: qq.fee, receive: qq.receive, rate, expires: Date.now() + 30000 };
    }
    this.quotes.set(q.id, q);
    return q;
  }
  convert(quoteId) {
    const q = this.quotes.get(quoteId);
    if (!q) throw fail('报价不存在，请重新预览');
    if (Date.now() > q.expires) { this.quotes.delete(quoteId); throw fail('汇率锁定已过期，请重新预览'); }
    this.quotes.delete(quoteId);
    if (q.provider === 'simulated') {
      return this.run(() => C.convert(this.S, { from: q.from, to: q.to, amount: q.amount, rate: q.rate }));
    }
    return (async () => {
      if (C.balance(this.S, 'wallet', q.from) < q.amount) throw fail('余额不足');
      const c = await this.awx.convert({ requestId: rid(), from: q.from, to: q.to, amount: q.amount, quoteId: q.id });
      return this.run(() => C.convert(this.S, {
        from: q.from, to: q.to, amount: q.amount, fee: 0, ref: 'awx:conversion:' + (c.conversion_id || c.id),
        receive: c.buy_amount != null ? P.minor(c.buy_amount) : q.receive, rate: Number(c.client_rate || q.rate)
      }));
    })();
  }

  // ---------- money out ----------
  send(o) {
    const amount = Math.round(o.amount);
    const rec = this.S.recipients.find((r) => r.id === o.recipientId);
    if (!rec) throw fail('找不到这个收款人', 404);
    if (this.S.kyc !== 'verified') throw fail('请先完成实名认证', 403);
    if (!C.CURRENCIES[o.cur]) throw fail('不支持的币种');
    const ch = C.CHANNELS[rec.channel];
    const note = String(o.note || '').slice(0, 80);
    if (!this.awx) {
      return this.run(() => C.sendStart(this.S, { cur: o.cur, amount, recipient: rec, rate: C.liveRate(o.cur, ch.cur), note }));
    }
    if (!rec.beneficiaryId) throw fail('这个收款人还没绑定 Airwallex 收款人 ID（在 Airwallex 沙盒后台新增收款人后填进来）');
    if (o.cur === 'USDT') throw fail('USDT 不能直接汇款，请先换成法币');
    return (async () => {
      if (C.balance(this.S, 'wallet', o.cur) < amount) throw fail('余额不足');
      const tr = await this.awx.createTransfer({ requestId: rid(), beneficiaryId: rec.beneficiaryId, from: o.cur, to: ch.cur, amount, reference: note || 'Crosspay' });
      return this.run(() => {
        const t = C.sendStart(this.S, {
          cur: o.cur, amount, recipient: rec, note, ref: 'awx:transfer:' + tr.id,
          fee: tr.fee_amount != null ? P.minor(tr.fee_amount) : 0,
          receive: P.minor(tr.amount_beneficiary_receives != null ? tr.amount_beneficiary_receives : tr.transfer_amount),
          rate: tr.conversion && tr.conversion.rate ? Number(tr.conversion.rate) : undefined
        });
        t.providerStatus = tr.status;
        this.applyTransferStatus(t, tr.status);
        return t;
      });
    })();
  }
  applyTransferStatus(t, status) {
    if (!t || t.status !== 'pending') return;
    const s = String(status || '').toUpperCase();
    t.providerStatus = s;
    if (s === 'PAID' || s === 'SENT' || s === 'SUCCEEDED' || s === 'COMPLETED') C.sendComplete(this.S, t.id);
    else if (['FAILED', 'CANCELLED', 'REJECTED', 'RETURNED'].includes(s)) C.sendFail(this.S, t.id, 'Airwallex：' + s);
    else if (['SCHEDULED', 'PROCESSING', 'DISPATCHED', 'IN_REVIEW', 'READY_FOR_DISPATCH'].includes(s)) t.step = s === 'IN_REVIEW' || s === 'SCHEDULED' ? 2 : 3;
  }

  // ---------- reconciliation ----------
  async reconcile() {
    const ledger = C.balances(this.S, 'wallet'), hold = C.balances(this.S, 'hold');
    if (!this.awx) return { source: 'simulated', rows: C.ORDER.map((c) => ({ cur: c, ledger: ledger[c], hold: hold[c], provider: null })) };
    const bal = await this.awx.balances();
    const map = {}; bal.forEach((b) => { map[b.currency] = b; });
    const rows = C.ORDER.filter((c) => c !== 'USDT').map((c) => {
      const b = map[c];
      return { cur: c, ledger: ledger[c], hold: hold[c], provider: b ? P.minor(b.available_amount) : 0, pending: b && b.pending_amount ? P.minor(b.pending_amount) : 0 };
    });
    return { source: 'airwallex', rows };
  }

  // ---------- webhooks ----------
  async onAirwallex(ev) {
    const name = String(ev.name || ''), obj = (ev.data && ev.data.object) || ev.data || {};
    if (name.startsWith('deposit.') || (name.includes('global_account') && name.includes('deposit'))) {
      await this.run(() => this.bookDeposit(obj));
    } else if (name.startsWith('payout.transfer.') || name.startsWith('transfer.')) {
      // re-read the transfer instead of trusting the event body
      const id = obj.id || obj.transfer_id;
      if (id) {
        const tr = await this.awx.getTransfer(id);
        await this.run(() => this.applyTransferStatus(C.findByRef(this.S, 'awx:transfer:' + id), tr.status));
      }
    } else if (name.startsWith('payment_intent.') || name.startsWith('payment_link.')) {
      await this.refreshOpenInvoices('airwallex');
    }
  }
  async onStripe(ev) {
    if (ev.type !== 'checkout.session.completed' && ev.type !== 'checkout.session.async_payment_succeeded') return;
    const cs = ev.data && ev.data.object;
    const inv = cs && this.S.invoices.find((x) => x.provider === 'stripe' && x.providerRef === cs.id);
    if (inv) await this.refreshInvoice(inv.id);
  }
  async onBTCPay(ev) {
    if (ev.invoiceId) {
      const item = (this.S.crypto || []).find((x) => x.providerRef === ev.invoiceId);
      if (item) await this.refreshCrypto(item.id);
    }
  }

  // ---------- background sync (fallback when a webhook is missed) ----------
  async refreshOpenInvoices(provider) {
    for (const inv of this.S.invoices.filter((x) => x.status === 'open' && x.provider === provider)) {
      try { await this.refreshInvoice(inv.id); } catch (e) { /* keep going */ }
    }
  }
  async sync() {
    const now = Date.now();
    await this.run(() => {
      if (this.S.kyc === 'pending' && now - (this.S.kycTs || 0) > (this.cfg.stepMs || STEP_MS)) this.S.kyc = 'verified';
      if (!this.awx) {
        this.S.txns.forEach((t) => {
          if (t.type !== 'send' || t.status !== 'pending') return;
          const ms = this.cfg.stepMs || STEP_MS;
          const step = Math.min(4, 1 + Math.floor((now - t.ts) / ms));
          if (step >= 4) C.sendComplete(this.S, t.id, t.ts + 3 * ms); else t.step = step;
        });
      }
    });
    if (this.stripe) await this.refreshOpenInvoices('stripe');
    if (this.awx) {
      await this.refreshOpenInvoices('airwallex');
      for (const t of this.S.txns.filter((x) => x.type === 'send' && x.status === 'pending' && x.ref)) {
        try {
          const tr = await this.awx.getTransfer(t.ref.replace('awx:transfer:', ''));
          await this.run(() => this.applyTransferStatus(t, tr.status));
        } catch (e) { /* retry next round */ }
      }
    }
    if (this.btcpay) {
      for (const c of (this.S.crypto || []).filter((x) => x.provider === 'btcpay' && (x.status === 'new' || x.status === 'processing'))) {
        try { await this.refreshCrypto(c.id); } catch (e) { /* retry */ }
      }
    }
  }
}

module.exports = { Service };
