// 环球收款: ledger, fee and QR tests. Run with: node --test tests/crosspay-core.test.js
const test = require('node:test');
const assert = require('node:assert');
const C = require('../crosspay/src/main/assets/core.js');
const QR = require('../crosspay/src/main/assets/qr.js');

function allBalanced(state) {
  const per = {};
  state.txns.forEach(t => t.entries.forEach(e => { per[e.cur] = (per[e.cur] || 0) + e.amt; }));
  Object.keys(per).forEach(c => assert.strictEqual(per[c], 0, 'ledger out of balance in ' + c));
}

test('amount parsing', () => {
  assert.strictEqual(C.toMinor('1,234.5'), 123450);
  assert.strictEqual(C.toMinor('0.07'), 7);
  assert.strictEqual(C.toMinor('12'), 1200);
  assert.ok(Number.isNaN(C.toMinor('1.234')));
  assert.ok(Number.isNaN(C.toMinor('-5')));
  assert.ok(Number.isNaN(C.toMinor('abc')));
  assert.strictEqual(C.fmtNum(123456789), '1,234,567.89');
  assert.strictEqual(C.fmt('MYR', 5), 'RM 0.05');
});

test('collect takes the method fee and keeps the books balanced', () => {
  const s = C.newState(0);
  const t = C.collect(s, { method: 'link', cur: 'USD', amount: 100000, title: 'x' });
  assert.strictEqual(t.fee, 2800);
  assert.strictEqual(C.balance(s, 'wallet', 'USD'), 97200);
  assert.strictEqual(C.balance(s, 'fees', 'USD'), 2800);
  allBalanced(s);
});

test('convert moves value between currencies at the quoted rate', () => {
  const s = C.newState(0);
  C.collect(s, { method: 'account', cur: 'USD', amount: 100300, title: 'x' }); // net 100000.9 -> 99999
  const usd = C.balance(s, 'wallet', 'USD');
  const t = C.convert(s, { from: 'USD', to: 'MYR', amount: 50000, rate: 4.2 });
  assert.strictEqual(t.fee, 250);
  assert.strictEqual(t.receive, Math.round(49750 * 4.2));
  assert.strictEqual(C.balance(s, 'wallet', 'USD'), usd - 50000);
  assert.strictEqual(C.balance(s, 'wallet', 'MYR'), 208950);
  allBalanced(s);
});

test('cannot spend more than the available balance', () => {
  const s = C.newState(0);
  C.collect(s, { method: 'account', cur: 'MYR', amount: 10000, title: 'x' });
  assert.throws(() => C.convert(s, { from: 'MYR', to: 'USD', amount: 20000 }), /余额不足/);
  assert.throws(() => C.sendStart(s, { cur: 'USD', amount: 1, recipient: { name: 'a', channel: 'duitnow' } }), /余额不足/);
  assert.throws(() => C.convert(s, { from: 'MYR', to: 'MYR', amount: 100 }), /不同的币种/);
});

test('transfer: hold, then complete or refund', () => {
  const s = C.newState(0);
  C.collect(s, { method: 'store', cur: 'USD', amount: 200000, title: 'x' });
  const start = C.balance(s, 'wallet', 'USD');
  const rec = { name: '张伟', channel: 'cn_bank' };
  const q = C.quoteSend('USD', 'cn_bank', 100000, 7);
  assert.strictEqual(q.fixedFee, 300);
  assert.strictEqual(q.fxFee, 500);
  assert.strictEqual(q.receive, Math.round((100000 - 800) * 7));

  const t = C.sendStart(s, { cur: 'USD', amount: 100000, recipient: rec, rate: 7 });
  assert.strictEqual(t.status, 'pending');
  assert.strictEqual(C.balance(s, 'wallet', 'USD'), start - 100000);
  assert.strictEqual(C.balance(s, 'hold', 'USD'), 100000);
  C.sendComplete(s, t.id);
  assert.strictEqual(C.balance(s, 'hold', 'USD'), 0);
  assert.strictEqual(t.status, 'done');
  allBalanced(s);

  const t2 = C.sendStart(s, { cur: 'USD', amount: 50000, recipient: rec });
  C.sendFail(s, t2.id, '测试退回');
  assert.strictEqual(C.balance(s, 'wallet', 'USD'), start - 100000);
  assert.strictEqual(C.balance(s, 'hold', 'USD'), 0);
  assert.throws(() => C.sendComplete(s, t2.id), /不在处理中/);
  allBalanced(s);
});

test('same-currency payout has no FX fee and tiny amounts are refused', () => {
  const q = C.quoteSend('MYR', 'own_myr', 1000);
  assert.strictEqual(q.fee, 0);
  assert.strictEqual(q.receive, 1000);
  const s = C.newState(0);
  C.collect(s, { method: 'account', cur: 'SGD', amount: 100000, title: 'x' });
  assert.throws(() => C.sendStart(s, { cur: 'SGD', amount: 10, recipient: { name: 'a', channel: 'cn_bank' } }), /手续费/);
});

test('unbalanced entries are rejected', () => {
  assert.throws(() => C.checkBalanced([{ acct: 'wallet', cur: 'USD', amt: 5 }]), /不平衡/);
  assert.throws(() => C.checkBalanced([{ acct: 'wallet', cur: 'USD', amt: 0.5 }, { acct: 'world', cur: 'USD', amt: -0.5 }]), /整数/);
});

test('demo data is consistent and has no negative balances', () => {
  const s = C.seedDemo(C.newState(Date.now()), Date.now());
  allBalanced(s);
  const b = C.balances(s);
  C.ORDER.forEach(c => assert.ok(b[c] >= 0, c + ' negative'));
  assert.ok(C.total(s, 'MYR') > 0);
  const pending = s.txns.filter(t => t.status === 'pending');
  assert.strictEqual(pending.length, 0);
});

test('demo crypto addresses are never real chain addresses', () => {
  const a = C.demoAddress('TRC20', 42);
  assert.match(a, /^DEMO-TRC20-/);
});

test('QR encoder: structure of version 1 and growth with length', () => {
  const m = QR.matrix('A');
  assert.strictEqual(m.length, 21);
  // finder pattern corners are dark, separators light
  assert.strictEqual(m[0][0], true);
  assert.strictEqual(m[7][7], false);
  assert.strictEqual(m[0][20], true);
  assert.strictEqual(m[20][0], true);
  assert.strictEqual(QR.matrix('x'.repeat(200)).length > 41, true);
  assert.match(QR.svg('hello', 100), /^<svg/);
});

test('provider amounts override the demo fee schedule', () => {
  const s = C.newState(0);
  const t = C.collect(s, { method: 'link', cur: 'USD', amount: 50000, fee: 0, title: 'stripe', ref: 'stripe:cs_1' });
  assert.strictEqual(t.net, 50000);
  assert.strictEqual(C.findByRef(s, 'stripe:cs_1').id, t.id);
  assert.throws(() => C.collect(s, { method: 'link', cur: 'USD', amount: 100, fee: 200, title: 'x' }), /手续费/);
  const fx = C.convert(s, { from: 'USD', to: 'MYR', amount: 10000, receive: 42150, fee: 0, ref: 'awx:conv:1' });
  assert.strictEqual(fx.receive, 42150);
  assert.strictEqual(C.balance(s, 'wallet', 'MYR'), 42150);
  const tr = C.sendStart(s, { cur: 'USD', amount: 20000, recipient: { name: 'a', channel: 'cn_bank' }, fee: 150, receive: 141000, rate: 7.1 });
  assert.strictEqual(tr.fee, 150);
  C.sendComplete(s, tr.id);
  allBalanced(s);
});
