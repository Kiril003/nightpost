import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { homeCopy } from '../../i18n/accountHome.ts';

const read = (p) => readFileSync(new URL(p, import.meta.url), 'utf8');

const account = read('../../pages/Account.tsx');
const accountHome = read('./AccountHome.tsx');
const css = read('../../styles/global.css');
const tokensCss = read('../../styles/tokens.css');
const tailwind = read('../../../tailwind.config.ts');
const footer = read('../Footer.tsx');

test('Account.tsx mounts AccountHome for the overview tab', () => {
  assert.match(account, /case 'overview':\s*\n\s*return <AccountHome/);
});

test('AccountHome.tsx has hero, places, devices sections, real API calls, and no banned styling/terms', () => {
  for (const sec of ['hero', 'places', 'devices']) {
    assert.match(accountHome, new RegExp(`data-home-section="${sec}"`), sec);
  }
  assert.match(accountHome, /myVenues\(\)/);
  assert.match(accountHome, /revokeSession\(/);
  assert.match(accountHome, /\/api\/download\/link/);
  for (const banned of ['dark:', 'gradient', 'blur', 'backdrop', 'Points', 'devnet']) {
    assert.equal(accountHome.includes(banned), false, `banned token in AccountHome: ${banned}`);
  }
});

test('tokens.css defines night and day blocks and global.css applies them', () => {
  const nightEnd = tokensCss.indexOf('.scheme-day {');
  const nightBlock = tokensCss.slice(0, nightEnd);
  const dayBlock = tokensCss.slice(nightEnd);
  assert.match(nightBlock, /--ph-canvas:\s*17 18 20;/);
  assert.match(nightBlock, /--ph-accent:\s*226 160 72;/);
  assert.match(dayBlock, /--ph-canvas:\s*246 241 231;/);
  assert.match(css, /\.scheme-night,\s*\.scheme-day\s*\{/);
});

test('tailwind.config.ts defines Direction A shell width, radii, and display typography', () => {
  assert.match(tailwind, /shell:\s*'90rem'/);
  assert.match(tailwind, /control:\s*'12px'/);
  assert.match(tailwind, /card:\s*'20px'/);
  assert.match(tailwind, /d1:/);
});

test('Footer.tsx sits on the shared .t grid and drops prose', () => {
  assert.match(footer, /<div className="wrap foot-in">/);
  assert.equal(footer.includes('A person answers'), false);
});

test('home.ts en and uk key sets match and contain no banned vocabulary', () => {
  const enKeys = Object.keys(homeCopy.en).sort();
  const ukKeys = Object.keys(homeCopy.uk).sort();
  assert.deepEqual(enKeys, ukKeys);
  assert.deepEqual(Object.keys(homeCopy.en.via).sort(), Object.keys(homeCopy.uk.via).sort());

  const collectStrings = (obj) => {
    const out = [];
    for (const val of Object.values(obj)) {
      if (typeof val === 'string') out.push(val);
      else if (typeof val === 'function') out.push(val('2026'));
      else if (val && typeof val === 'object') out.push(...collectStrings(val));
    }
    return out;
  };

  for (const str of [...collectStrings(homeCopy.en), ...collectStrings(homeCopy.uk)]) {
    assert.doesNotMatch(str, /Circles|Кола|Phantom|offline|light|mesh/i);
  }
});
