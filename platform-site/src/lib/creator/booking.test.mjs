import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { depositDue, depositOf, pageStaff } from './booking.ts';
import { slotDays } from './slots.ts';

const page = (extra = []) => ({
  blocks: [
    { id: 'svc-1', kind: 'book.services', data: { token: 'USDC', items: [{ id: 'one', name: 'Стрижка', price: '30', duration: 60 }] } },
    { id: 'book-1', kind: 'book.slots', data: { services: 'svc-1' } },
    ...extra,
  ],
});
const staff = (hidden = false) => ({ id: 'staff-1', kind: 'book.staff', hidden, data: { items: [{ id: 'olia', name: 'Оля', role: 'Майстер' }, { id: 'ira', name: 'Іра' }, { name: 'без id' }] } });
const policy = (deposit, hidden = false) => ({ id: 'policy-1', kind: 'book.policy', hidden, data: { cancel: 24, deposit } });

test('the deposit is the same half-up math the server uses', () => {
  assert.equal(depositOf('30', 20, 6), '6');
  assert.equal(depositOf('33.33', 15, 6), '4.9995');
  assert.equal(depositOf('0.000001', 50, 6), '0.000001');
  assert.equal(depositOf('0.000001', 49, 6), null);
  assert.equal(depositOf('450', 0, 6), null);
  assert.equal(depositOf('', 20, 6), null);
  assert.equal(depositOf('12.5', 100, 6), '12.5');
  assert.equal(depositOf('1', 33, 9), '0.33');
});

test('deposit due comes from a visible policy and the service price, never invented', () => {
  const svc = { id: 'one', price: '30' };
  assert.deepEqual(depositDue(page([policy(20)]), svc, 'USDC'), { amount: '6', token: 'USDC' });
  assert.equal(depositDue(page([policy(20, true)]), svc, 'USDC'), null, 'hidden policy');
  assert.equal(depositDue(page([policy(0)]), svc, 'USDC'), null);
  assert.equal(depositDue(page(), svc, 'USDC'), null, 'no policy block');
  assert.equal(depositDue(page([policy(20)]), { id: 'one' }, 'USDC'), null, 'no price');
  assert.equal(depositDue(page([policy(20)]), svc, 'XYZ'), null, 'unknown token');
});

test('the masters are the visible book.staff items that have an id', () => {
  assert.deepEqual(pageStaff(page([staff()])).map((s) => s.id), ['olia', 'ira']);
  assert.deepEqual(pageStaff(page([staff(true)])), []);
  assert.deepEqual(pageStaff(page()), []);
});

test('with masters a time stays open until each master is taken; a chosen master sees only their own time', () => {
  const now = new Date(2026, 9, 27, 8, 0);
  const rows = [{ days: ['tue'], hours: { open: '10:00', close: '11:00' } }];
  const at = new Date(2026, 9, 27, 10, 0).getTime();
  const base = { book: { slot: 60, lead: 0, horizon: 1 }, service: { id: 'one', duration: 60 }, rows, now };
  const taken = [{ service: 'one', staff: 'olia', at_ms: at, n: 1 }];
  const left = (o) => slotDays({ ...base, taken, ...o })[0].times[0].left;
  assert.equal(left({ staff: ['olia', 'ira'] }), 1, 'anyone: Іра is still free');
  assert.equal(left({ staff: ['olia', 'ira'], master: 'olia' }), 0);
  assert.equal(left({ staff: ['olia', 'ira'], master: 'ira' }), 1);
  assert.equal(left({}), 0, 'no staff block: one lane');
  const both = [...taken, { service: 'one', staff: null, at_ms: at, n: 1 }];
  assert.equal(slotDays({ ...base, taken: both, staff: ['olia', 'ira'], master: 'ira' })[0].times[0].left, 0, 'anyone took the last lane');
});

test('the page wires the master picker, the deposit on the CTA and the honest chat note', () => {
  const src = readFileSync(new URL('../../components/creator/CreatorPage.tsx', import.meta.url), 'utf8');
  assert.match(src, /pageStaff\(page\)/);
  assert.match(src, /depositDue\(page,/);
  assert.match(src, /body.staff = a.staff.id/);
  const fmt = readFileSync(new URL('./format.ts', import.meta.url), 'utf8');
  assert.match(fmt, /Передоплата — у чаті після підтвердження/);
  assert.match(fmt, /Будь-хто/);
  const api = readFileSync(new URL('../venuesApi.ts', import.meta.url), 'utf8');
  assert.match(api, /staff\?: string;/);
});

test('amounts follow the locale: uk writes a decimal comma', async () => {
  const { money } = await import('./format.ts');
  assert.equal(money('uk', '3.6'), '3,6');
  assert.equal(money('en', '3.6'), '3.6');
  assert.equal(money('uk', '450'), '450');
  const src = readFileSync(new URL('../../components/creator/CreatorPage.tsx', import.meta.url), 'utf8');
  assert.match(src, /\{money\(lang, amount\)\} <span className="cx-tok">/, 'Price localises');
  assert.match(src, /c\.deposit\(money\(lang, /);
  assert.doesNotMatch(src, /`\$\{c\.message\} \$\{/, 'no inflected «Написати Студія»');
});
