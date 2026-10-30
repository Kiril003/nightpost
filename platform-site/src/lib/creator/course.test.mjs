import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { commonTime, courseState, localToday, sessionsOf, weekdaysOf } from './course.ts';

const data = {
  title: 'Photo basics', token: 'USDC', price: '240', seats: 12,
  items: [{ id: 'm1', name: 'Manual camera settings' }],
  sessions: [
    { id: 's3', name: 'Mini project', date: '2026-11-05', time: '19:00' },
    { id: 's1', name: 'Camera and optics', date: '2026-10-27', time: '19:00' },
    { id: 's2', name: 'Light and exposure', date: '2026-10-29', time: '19:00' },
  ],
};

test('sessions run in date order: the past is done, the first one ahead is next', () => {
  const s = sessionsOf(data, '2026-10-29');
  assert.deepEqual(s.map((x) => [x.n, x.id, x.state]), [[1, 's1', 'done'], [2, 's2', 'next'], [3, 's3', 'later']]);
  assert.deepEqual(sessionsOf({ sessions: [{ id: 'x', name: 'Bad', date: 'soon', time: '19:00' }] }, '2026-10-29'), [], 'a broken date is not shown as a session');
});

test('a course takes people only with seats and a date ahead, the same rule the server keeps', () => {
  assert.equal(courseState(data, '2026-10-29'), 'open');
  assert.equal(courseState(data, '2026-11-06'), 'over');
  assert.equal(courseState({ ...data, seats: undefined }, '2026-10-29'), 'off');
  assert.equal(courseState({ ...data, sessions: undefined }, '2026-10-29'), 'off');
});

test('the schedule line is the real weekdays, and one time only when every session shares it', () => {
  const s = sessionsOf(data, '2026-10-29');
  assert.deepEqual(weekdaysOf(s), ['tue', 'thu']);
  assert.equal(commonTime(s), '19:00');
  assert.equal(commonTime([...s, { id: 's4', name: 'x', date: '2026-11-10', time: '18:30', n: 4, state: 'later' }]), null);
  assert.equal(localToday(new Date(2026, 9, 3, 23, 30)), '2026-10-03', 'the visitor’s own calendar day');
});

test('the course on the page reads seats from the server and joins through the request door', () => {
  const src = readFileSync(new URL('../../components/creator/BlocksMore.tsx', import.meta.url), 'utf8');
  assert.match(src, /seatsLeft\(venueId, block\.id\)/);
  assert.match(src, /ask\(\{ block, course: /);
  const api = readFileSync(new URL('../venuesApi.ts', import.meta.url), 'utf8');
  assert.match(api, /export function seatsLeft[\s\S]{0,200}\/requests\/stock\?block=/);
});

test('the seats tile reads «4 з 12» under «Вільні місця», and a closed course shows the total only', async () => {
  const { creatorCopy } = await import('./format.ts');
  const uk = creatorCopy('uk');
  const en = creatorCopy('en');
  assert.equal(uk.seatsFree, 'Вільні місця');
  assert.equal(uk.seatsOf(12, 4), '4 з 12');
  assert.equal(en.seatsOf(12, 4), '4 of 12');
  assert.equal(uk.seatsOf(12, null), '12');
  const src = readFileSync(new URL('../../components/creator/BlocksMore.tsx', import.meta.url), 'utf8');
  assert.match(src, /free === null \? c\.seats : c\.seatsFree/, 'the label follows whether the free count is known');
  const css = readFileSync(new URL('../../styles/creator-more.css', import.meta.url), 'utf8');
  assert.match(css, /\.cm-facts \{[^}]*grid-auto-flow: column;[^}]*grid-auto-columns: minmax\(min-content, 1fr\)/, 'three tiles share one row and one height, each as wide as its value');
  assert.match(css, /\.cm-fact b \{[^}]*white-space: nowrap/, 'a tile value stays on one line');
});

test('the solo message card fits its content and names hours or an address only when the page has them', () => {
  const src = readFileSync(new URL('../../components/creator/CreatorPage.tsx', import.meta.url), 'utf8');
  assert.match(src, /'loc\.message': \(\{ block \}\) => <MessageSolo block=\{block\} \/>/);
  assert.match(src, /const addr = page\.blocks\.find\(\(b\) => b\.kind === 'loc\.address' && !b\.hidden && onSite\(b\) && b\.data\.text\)/);
  assert.match(src, /\{d\.hours && \(/);
  assert.match(src, /\{addr && \(/);
  const css = readFileSync(new URL('../../styles/creator.css', import.meta.url), 'utf8');
  assert.match(css, /\.cx-msg-solo \{[^}]*\}/);
  assert.doesNotMatch(css.match(/\.cx-msg-solo \{[^}]*\}/)[0], /min-height/);
});
