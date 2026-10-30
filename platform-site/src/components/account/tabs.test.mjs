import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { PAID_PLANS, PAID_TABS, tabShown } from './tabs.ts';

const account = readFileSync(new URL('../../pages/Account.tsx', import.meta.url), 'utf8');
const overview = readFileSync(new URL('./Overview.tsx', import.meta.url), 'utf8');

const tabs = (() => {
  const at = account.indexOf('const TABS');
  const block = account.slice(at, account.indexOf('\n];', at));
  return [...block.matchAll(/\{ id: '(\w+)'([^}]*)\}/g)].map((m) => ({ id: m[1], staff: /needs:/.test(m[2]) }));
})();

const sidebar = (paid) => tabs.filter((t) => !t.staff && tabShown(t.id, paid)).map((t) => t.id);

test('the tab list is read from the cabinet source', () => {
  assert.deepEqual(
    tabs.map((t) => t.id),
    ['overview', 'devices', 'licenses', 'purchases', 'places', 'threads', 'security', 'staff', 'queue', 'people', 'allLicenses', 'journal', 'moderation', 'erasures', 'money', 'assistant', 'system'],
  );
});

test('free build: the visible tabs are You, Devices, Places, Messages, Security', () => {
  assert.equal(PAID_PLANS, false);
  assert.deepEqual(sidebar(PAID_PLANS), ['overview', 'devices', 'places', 'threads', 'security']);
});

test('with plans the same constant brings back Licences and Purchases in their old places', () => {
  assert.deepEqual([...PAID_TABS].sort(), ['licenses', 'purchases']);
  assert.deepEqual(sidebar(true), ['overview', 'devices', 'licenses', 'purchases', 'places', 'threads', 'security']);
});

test('Account filters the sidebar through the constant; hash links still reach every panel', () => {
  assert.match(account, /import \{ tabShown \} from '\.\.\/components\/account\/tabs';/);
  assert.match(account, /\(!item\.needs \|\| atLeast\(role, item\.needs\)\) && tabShown\(item\.id\)/);
  assert.match(account, /const TAB_IDS = new Set<string>\(TABS\.filter\(\(t\) => !t\.to\)\.map\(\(t\) => t\.id\)\);/);
  for (const id of PAID_TABS) assert.match(account, new RegExp(`case '${id}':`), `${id} panel is still rendered`);
});

test('Overview: surviving tiles exclude paid-only tabs', () => {
  const tileTabs = [...overview.matchAll(/^\s+tab: '(\w+)',$/gm)].map((m) => m[1]);
  assert.deepEqual(tileTabs, ['licenses', 'devices', 'threads', 'security', 'downloads', 'purchases']);
  const surviving = tileTabs.filter((id) => tabShown(id));
  assert.deepEqual(surviving, ['devices', 'threads', 'security', 'downloads']);
  for (const id of surviving.filter((t) => t !== 'downloads')) {
    assert.ok(sidebar(PAID_PLANS).includes(id), `${id} has a sidebar tab`);
  }
});

test('Overview filters its tiles and hides the licence hint card through the same constant', () => {
  assert.match(overview, /import \{ PAID_PLANS, tabShown \} from '\.\/tabs';/);
  assert.match(overview, /tiles\.filter\(\(tile\) => tabShown\(tile\.tab\)\)/);
  assert.match(overview, /\{PAID_PLANS && \(\s*<Card className="mt-6">\s*<h3 className="text-headline text-ink">\{t\.nextTitle\}/);
  assert.match(overview, /shown\.length > 3 \? 'sm:grid-cols-2 xl:grid-cols-3' : 'sm:grid-cols-3'/, 'three tiles fill the row instead of leaving a hole');
});
