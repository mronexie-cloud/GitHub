/* 环球收款 — demo core.
 * A small double-entry ledger (every transaction's entries sum to zero per
 * currency, the same model as the Blnk ledger planned for phase 2), demo FX
 * rates, fee quotes and the money flows the app shows. No real money moves:
 * the counterparties are the ledger accounts "world" (outside the app),
 * "fees" (the app's revenue) and "hold" (money in flight on a transfer).
 * Amounts are integers in minor units (cents). */
(function (root) {
  'use strict';

  var CURRENCIES = {
    USD: { name: '美元', flag: '🇺🇸', sym: '$' },
    EUR: { name: '欧元', flag: '🇪🇺', sym: '€' },
    GBP: { name: '英镑', flag: '🇬🇧', sym: '£' },
    CNY: { name: '人民币', flag: '🇨🇳', sym: '¥' },
    MYR: { name: '马币', flag: '🇲🇾', sym: 'RM' },
    SGD: { name: '新币', flag: '🇸🇬', sym: 'S$' },
    THB: { name: '泰铢', flag: '🇹🇭', sym: '฿' },
    USDT: { name: '泰达币', flag: '₮', sym: '₮' }
  };
  var ORDER = ['USD', 'EUR', 'GBP', 'CNY', 'MYR', 'SGD', 'THB', 'USDT'];

  // Demo mid-market rates: units of each currency per 1 USD.
  var USD_RATES = { USD: 1, EUR: 0.92, GBP: 0.78, CNY: 7.12, MYR: 4.22, SGD: 1.30, THB: 33.2, USDT: 1.0 };

  // Collection fees by method (fraction of the amount received).
  var COLLECT_FEES = {
    account: { rate: 0.003, label: '收款账户入账' },
    link: { rate: 0.028, label: '收款链接（银行卡）' },
    store: { rate: 0.005, label: '电商平台回款' },
    crypto: { rate: 0.008, label: 'USDT 链上收款' }
  };
  var FX_FEE = 0.005;

  // Payout channels: fixed fee in US cents, shown arrival time.
  var CHANNELS = {
    cn_bank: { label: '中国大陆银行卡', cur: 'CNY', fixedUsdCents: 300, eta: '1–2 个工作日' },
    alipay: { label: '支付宝', cur: 'CNY', fixedUsdCents: 200, eta: '数分钟' },
    duitnow: { label: '马来西亚 DuitNow / 银行', cur: 'MYR', fixedUsdCents: 50, eta: '实时到账' },
    paynow: { label: '新加坡 PayNow', cur: 'SGD', fixedUsdCents: 50, eta: '实时到账' },
    promptpay: { label: '泰国 PromptPay', cur: 'THB', fixedUsdCents: 50, eta: '实时到账' },
    own_myr: { label: '提现到本人马来西亚账户', cur: 'MYR', fixedUsdCents: 0, eta: '实时到账' }
  };

  var STORES = [
    { id: 'shopify', name: 'Shopify', cur: 'USD', range: [300, 2500] },
    { id: 'amazon_us', name: 'Amazon 美国站', cur: 'USD', range: [800, 6000] },
    { id: 'amazon_eu', name: 'Amazon 欧洲站', cur: 'EUR', range: [500, 4000] },
    { id: 'amazon_uk', name: 'Amazon 英国站', cur: 'GBP', range: [400, 3000] },
    { id: 'shopee', name: 'Shopee 马来西亚', cur: 'MYR', range: [500, 8000] },
    { id: 'lazada', name: 'Lazada 新加坡', cur: 'SGD', range: [200, 3000] },
    { id: 'tiktok', name: 'TikTok Shop 泰国', cur: 'THB', range: [5000, 60000] }
  ];

  var NETWORKS = {
    TRC20: { label: 'TRON (TRC20)', confirmations: 19 },
    ERC20: { label: 'Ethereum (ERC20)', confirmations: 12 },
    POLYGON: { label: 'Polygon', confirmations: 30 }
  };

  // ---------- money helpers ----------
  function toMinor(text) {
    var s = String(text == null ? '' : text).replace(/[,\s]/g, '');
    if (!/^\d+(\.\d{0,2})?$/.test(s)) return NaN;
    var p = s.split('.');
    return parseInt(p[0], 10) * 100 + (p[1] ? parseInt((p[1] + '0').slice(0, 2), 10) : 0);
  }
  function fmtNum(minor) {
    var neg = minor < 0, v = Math.abs(Math.round(minor));
    var whole = Math.floor(v / 100).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    return (neg ? '-' : '') + whole + '.' + ('0' + (v % 100)).slice(-2);
  }
  function fmt(cur, minor) { return CURRENCIES[cur].sym + ' ' + fmtNum(minor); }

  function baseRate(from, to) { return USD_RATES[to] / USD_RATES[from]; }
  // A gently moving "live" rate for the demo screens (±0.15%).
  function liveRate(from, to, t) {
    if (from === to) return 1;
    var seed = (from.charCodeAt(0) * 31 + to.charCodeAt(1) * 17) % 97;
    var wiggle = 0.0015 * Math.sin((t || Date.now()) / 9000 + seed);
    return baseRate(from, to) * (1 + wiggle);
  }
  function convertMinor(amount, from, to, rate) {
    return Math.round(amount * (rate == null ? baseRate(from, to) : rate));
  }

  // ---------- quotes ----------
  function quoteCollect(method, amount) {
    var f = COLLECT_FEES[method];
    if (!f) throw new Error('未知收款方式');
    var fee = Math.round(amount * f.rate);
    return { gross: amount, fee: fee, net: amount - fee };
  }
  function quoteConvert(from, to, amount, rate) {
    var fee = Math.round(amount * FX_FEE);
    var net = amount - fee;
    var r = rate == null ? baseRate(from, to) : rate;
    return { amount: amount, fee: fee, net: net, rate: r, receive: Math.round(net * r) };
  }
  function quoteSend(fromCur, channelId, amount, rate) {
    var ch = CHANNELS[channelId];
    if (!ch) throw new Error('未知收款渠道');
    var fixed = convertMinor(ch.fixedUsdCents, 'USD', fromCur);
    var cross = fromCur !== ch.cur;
    var fxFee = cross ? Math.round(amount * FX_FEE) : 0;
    var r = cross ? (rate == null ? baseRate(fromCur, ch.cur) : rate) : 1;
    var left = amount - fixed - fxFee;
    return {
      amount: amount, fixedFee: fixed, fxFee: fxFee, fee: fixed + fxFee, rate: r,
      receive: left > 0 ? Math.round(left * r) : 0, receiveCur: ch.cur, eta: ch.eta, ok: left > 0
    };
  }

  // ---------- ledger ----------
  function newState(now) {
    return {
      v: 1, seq: 0, created: now || Date.now(), kyc: 'none', display: 'MYR',
      txns: [], invoices: [], stores: {}, recipients: [], deposits: []
    };
  }
  function nextId(state, prefix) {
    state.seq += 1;
    return prefix + (100000 + state.seq).toString(36).toUpperCase();
  }
  function balance(state, acct, cur) {
    var sum = 0;
    state.txns.forEach(function (t) {
      t.entries.forEach(function (e) { if (e.acct === acct && e.cur === cur) sum += e.amt; });
    });
    return sum;
  }
  function balances(state, acct) {
    var out = {};
    ORDER.forEach(function (c) { out[c] = 0; });
    state.txns.forEach(function (t) {
      t.entries.forEach(function (e) { if (e.acct === (acct || 'wallet')) out[e.cur] += e.amt; });
    });
    return out;
  }
  function total(state, displayCur, acct) {
    var b = balances(state, acct), sum = 0;
    ORDER.forEach(function (c) { sum += convertMinor(b[c], c, displayCur); });
    return sum;
  }
  function checkBalanced(entries) {
    var per = {};
    entries.forEach(function (e) {
      if (!CURRENCIES[e.cur]) throw new Error('未知币种 ' + e.cur);
      if (e.amt !== Math.round(e.amt)) throw new Error('金额必须是整数分');
      per[e.cur] = (per[e.cur] || 0) + e.amt;
    });
    Object.keys(per).forEach(function (c) { if (per[c] !== 0) throw new Error('分录不平衡：' + c); });
  }
  function post(state, txn) {
    checkBalanced(txn.entries);
    state.txns.push(txn);
    return txn;
  }
  function need(state, cur, amount) {
    if (!(amount > 0)) throw new Error('请输入金额');
    if (balance(state, 'wallet', cur) < amount) throw new Error('余额不足');
  }

  // Money in: from a global account, a payment link, a store payout or a USDT deposit.
  function collect(state, o) {
    var q = quoteCollect(o.method, o.amount);
    if (!(o.amount > 0)) throw new Error('请输入金额');
    return post(state, {
      id: o.id || nextId(state, 'TX'), type: 'collect', method: o.method, status: 'done',
      ts: o.ts || Date.now(), title: o.title, sub: o.sub || COLLECT_FEES[o.method].label,
      cur: o.cur, amount: o.amount, fee: q.fee, net: q.net, meta: o.meta || {},
      entries: [
        { acct: 'world', cur: o.cur, amt: -o.amount },
        { acct: 'wallet', cur: o.cur, amt: q.net },
        { acct: 'fees', cur: o.cur, amt: q.fee }
      ]
    });
  }

  function convert(state, o) {
    if (o.from === o.to) throw new Error('请选择两种不同的币种');
    need(state, o.from, o.amount);
    var q = quoteConvert(o.from, o.to, o.amount, o.rate);
    return post(state, {
      id: nextId(state, 'FX'), type: 'convert', status: 'done', ts: o.ts || Date.now(),
      title: o.from + ' → ' + o.to, sub: '换汇 · 汇率 ' + q.rate.toFixed(4),
      cur: o.from, amount: o.amount, fee: q.fee, toCur: o.to, receive: q.receive, rate: q.rate,
      entries: [
        { acct: 'wallet', cur: o.from, amt: -o.amount },
        { acct: 'fees', cur: o.from, amt: q.fee },
        { acct: 'world', cur: o.from, amt: q.net },
        { acct: 'world', cur: o.to, amt: -q.receive },
        { acct: 'wallet', cur: o.to, amt: q.receive }
      ]
    });
  }

  // Money out, step 1: put the amount on hold (it leaves the available balance).
  function sendStart(state, o) {
    var rec = o.recipient;
    need(state, o.cur, o.amount);
    var q = quoteSend(o.cur, rec.channel, o.amount, o.rate);
    if (!q.ok) throw new Error('金额太小，不够支付手续费');
    return post(state, {
      id: nextId(state, 'TR'), type: 'send', status: 'pending', step: 1, ts: o.ts || Date.now(),
      title: (rec.self ? '提现到 ' : '汇款给 ') + rec.name, sub: CHANNELS[rec.channel].label,
      cur: o.cur, amount: o.amount, fee: q.fee, toCur: q.receiveCur, receive: q.receive,
      rate: q.rate, eta: q.eta, recipient: rec, note: o.note || '',
      entries: [
        { acct: 'wallet', cur: o.cur, amt: -o.amount },
        { acct: 'hold', cur: o.cur, amt: o.amount }
      ]
    });
  }
  function findTxn(state, id) {
    for (var i = 0; i < state.txns.length; i++) if (state.txns[i].id === id) return state.txns[i];
    return null;
  }
  // Step 2a: delivered. The hold is released to the outside world and the fee is taken.
  function sendComplete(state, id, ts) {
    var t = findTxn(state, id);
    if (!t || t.status !== 'pending') throw new Error('这笔汇款不在处理中');
    var add = [
      { acct: 'hold', cur: t.cur, amt: -t.amount },
      { acct: 'fees', cur: t.cur, amt: t.fee },
      { acct: 'world', cur: t.cur, amt: t.amount - t.fee }
    ];
    checkBalanced(add);
    t.entries = t.entries.concat(add);
    t.status = 'done'; t.step = 4; t.doneTs = ts || Date.now();
    return t;
  }
  // Step 2b: rejected. The hold goes back to the wallet in full.
  function sendFail(state, id, reason) {
    var t = findTxn(state, id);
    if (!t || t.status !== 'pending') throw new Error('这笔汇款不在处理中');
    t.entries = t.entries.concat([
      { acct: 'hold', cur: t.cur, amt: -t.amount },
      { acct: 'wallet', cur: t.cur, amt: t.amount }
    ]);
    t.status = 'failed'; t.reason = reason || '已退回';
    return t;
  }

  // ---------- demo helpers ----------
  function rnd(seed) {
    var x = Math.sin(seed) * 10000;
    return x - Math.floor(x);
  }
  function fakeAccount(cur, seed) {
    var d = function (n, s) { var o = ''; for (var i = 0; i < n; i++) o += Math.floor(rnd(s + i) * 10); return o; };
    var holder = '演示用户 Demo User';
    switch (cur) {
      case 'USD': return [['账户名', holder], ['银行', 'Demo Bank N.A.（演示）'], ['账号', '8' + d(11, seed)], ['ACH 路由号', '0' + d(8, seed + 20)], ['SWIFT', 'DEMOUS33']];
      case 'EUR': return [['账户名', holder], ['银行', 'Demo Bank Europe（演示）'], ['IBAN', 'XX00 DEMO ' + d(4, seed) + ' ' + d(4, seed + 5) + ' ' + d(4, seed + 9)], ['BIC', 'DEMOXX22']];
      case 'GBP': return [['账户名', holder], ['银行', 'Demo Bank UK（演示）'], ['账号', d(8, seed)], ['Sort code', '00-' + d(2, seed + 3) + '-' + d(2, seed + 7)]];
      case 'CNY': return [['账户名', holder], ['用途', '离岸人民币 CNH 收款（演示）'], ['账号', '6' + d(15, seed)], ['SWIFT', 'DEMOHKHH']];
      case 'MYR': return [['账户名', holder], ['银行', 'Demo Bank Malaysia（演示）'], ['账号', d(12, seed)], ['DuitNow ID', '01' + d(8, seed + 11)]];
      case 'SGD': return [['账户名', holder], ['银行', 'Demo Bank Singapore（演示）'], ['账号', d(10, seed)], ['银行代码', '9' + d(3, seed + 2)]];
      case 'THB': return [['账户名', holder], ['银行', 'Demo Bank Thailand（演示）'], ['账号', d(10, seed)], ['PromptPay', '0' + d(9, seed + 4)]];
      default: return [['说明', '请在"收款 → USDT"里查看链上地址']];
    }
  }
  // Deliberately not a valid chain address, so nobody can send real funds to it.
  function demoAddress(network, seed) {
    var abc = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz', s = '';
    for (var i = 0; i < 30; i++) s += abc[Math.floor(rnd(seed + i * 7) * abc.length)];
    return 'DEMO-' + network + '-' + s;
  }

  function seedDemo(state, now) {
    now = now || Date.now();
    var day = 86400000;
    state.recipients = [
      { id: 'r1', name: '张伟', bank: '中国大陆银行卡 ****6218', channel: 'cn_bank' },
      { id: 'r2', name: '李娜', bank: '支付宝 138****2046', channel: 'alipay' },
      { id: 'r3', name: 'Lim Ah Kow', bank: 'DuitNow 012-****881', channel: 'duitnow' },
      { id: 'r4', name: 'Tan Mei Ling', bank: 'PayNow +65 9***4512', channel: 'paynow' },
      { id: 'r5', name: 'Somchai K.', bank: 'PromptPay 08*-***-4471', channel: 'promptpay' },
      { id: 'r6', name: '本人账户', bank: '马来西亚银行 ****3390', channel: 'own_myr', self: true }
    ];
    state.stores = { shopify: true, amazon_us: true, shopee: true };
    collect(state, { method: 'store', cur: 'USD', amount: 324050, title: 'Amazon 美国站回款', ts: now - 26 * day });
    collect(state, { method: 'store', cur: 'EUR', amount: 118000, title: 'Amazon 欧洲站回款', ts: now - 21 * day });
    collect(state, { method: 'account', cur: 'USD', amount: 150000, title: 'Northwind LLC 付款', sub: 'USD 收款账户入账', ts: now - 15 * day });
    collect(state, { method: 'store', cur: 'MYR', amount: 486000, title: 'Shopee 马来西亚回款', ts: now - 9 * day });
    convert(state, { from: 'USD', to: 'MYR', amount: 100000, ts: now - 8 * day });
    collect(state, { method: 'link', cur: 'USD', amount: 80000, title: 'Logo 设计尾款 · Sarah M.', sub: '收款链接（银行卡）', ts: now - 5 * day });
    collect(state, { method: 'crypto', cur: 'USDT', amount: 50000, title: 'USDT 到账 · TRC20', ts: now - 3 * day });
    var tr = sendStart(state, { cur: 'MYR', amount: 300000, recipient: state.recipients[0], ts: now - 2 * day });
    sendComplete(state, tr.id, now - 2 * day + 3600000);
    collect(state, { method: 'store', cur: 'USD', amount: 96520, title: 'Shopify 回款', ts: now - day });
    state.invoices.push({ id: 'INV-1001', client: 'Bright Studio GmbH', cur: 'EUR', amount: 65000, desc: '网站开发 · 第一期', status: 'open', ts: now - 2 * day });
    state.txns.sort(function (a, b) { return a.ts - b.ts; });
    return state;
  }

  var api = {
    CURRENCIES: CURRENCIES, ORDER: ORDER, USD_RATES: USD_RATES, COLLECT_FEES: COLLECT_FEES, FX_FEE: FX_FEE,
    CHANNELS: CHANNELS, STORES: STORES, NETWORKS: NETWORKS,
    toMinor: toMinor, fmtNum: fmtNum, fmt: fmt, baseRate: baseRate, liveRate: liveRate, convertMinor: convertMinor,
    quoteCollect: quoteCollect, quoteConvert: quoteConvert, quoteSend: quoteSend,
    newState: newState, nextId: nextId, balance: balance, balances: balances, total: total, checkBalanced: checkBalanced,
    collect: collect, convert: convert, sendStart: sendStart, sendComplete: sendComplete, sendFail: sendFail,
    findTxn: findTxn, fakeAccount: fakeAccount, demoAddress: demoAddress, seedDemo: seedDemo, rnd: rnd
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.Core = api;
})(this);
