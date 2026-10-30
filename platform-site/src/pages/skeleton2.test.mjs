import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { cabinetCopy } from '../i18n/cabinet.ts';
import { PAID_PLANS, tabShown } from '../components/account/tabs.ts';

const read = (p) => readFileSync(new URL(p, import.meta.url), 'utf8');

const account = read('./Account.tsx');
const accountI18n = read('../i18n/account.ts');
const accountHome = read('../components/account/AccountHome.tsx');
const signedOut = read('../components/account/SignedOut.tsx');
const buildPage = read('./Build.tsx');
const phoneFrame = read('../components/shell/PhoneFrame.tsx');

const BANNED_ROLES = [
  'rounded-stage',
  'text-d1',
  'text-d2',
  'text-d3',
  'text-h2',
  'text-h3',
  'text-lead',
  'text-meta',
  'text-callout',
  'text-cap',
  'rounded-full px-4',
];

const hasToken = (src, token) => new RegExp(`(?<![\\w-])${token}(?![\\w-])`).test(src);

test('Account.tsx and AccountHome.tsx use only skeleton-1 type roles and radii, and AccountHome has no PhoneFrame', () => {
  for (const [name, src] of [
    ['Account.tsx', account],
    ['AccountHome.tsx', accountHome],
  ]) {
    for (const token of BANNED_ROLES) {
      assert.equal(hasToken(src, token), false, `${name} contains banned token: ${token}`);
    }
  }
  assert.equal(accountHome.includes('PhoneFrame'), false, 'AccountHome must not mount PhoneFrame');
  assert.match(account, /text-page-h1/);
  assert.match(account, /border-b-2 border-accent/);
  assert.match(accountHome, /text-display/);
});

test('Tab labels in i18n/account.ts and i18n/cabinet.ts match You / Devices / Places / Messages / Security', () => {
  assert.equal(cabinetCopy.en.nav.overview, 'You');
  assert.equal(cabinetCopy.en.nav.devices, 'Devices');
  assert.match(accountI18n, /places: 'Places'/);
  assert.match(accountI18n, /threads: 'Messages'/);
  assert.match(accountI18n, /security: 'Security'/);

  assert.equal(cabinetCopy.uk.nav.overview, 'Я');
  assert.equal(cabinetCopy.uk.nav.devices, 'Пристрої');
  assert.match(accountI18n, /places: 'Місця'/);
  assert.match(accountI18n, /threads: 'Повідомлення'/);
  assert.match(accountI18n, /security: 'Безпека'/);

  assert.match(accountI18n, /hero: \{ eyebrow: 'You' \}/);
  assert.match(accountI18n, /hero: \{ eyebrow: 'Я' \}/);
});

test('tabShown("devices") is true with PAID_PLANS false', () => {
  assert.equal(PAID_PLANS, false);
  assert.equal(tabShown('devices'), true);
  assert.equal(tabShown('devices', false), true);
});

test('Build.tsx has no rounded-stage, places list view has no aside, and Create is primary', () => {
  for (const token of BANNED_ROLES) {
    assert.equal(hasToken(buildPage, token), false, `Build.tsx contains banned token: ${token}`);
  }
  const listStart = buildPage.indexOf('function VenueList');
  const listEnd = buildPage.indexOf('function Unavailable', listStart);
  assert.ok(listStart > 0 && listEnd > listStart, 'VenueList block exists');
  const listBlock = buildPage.slice(listStart, listEnd);
  assert.equal(listBlock.includes('<aside'), false, 'places list view must have no aside');
  assert.match(listBlock, /<Btn type="submit" tone="primary"/);
});

test('PhoneFrame.tsx has rounded-phone, 320px width, 9/19.5 aspect, no hex and no zoom', () => {
  assert.match(phoneFrame, /rounded-phone/);
  assert.match(phoneFrame, /w-\[320px\]/);
  assert.match(phoneFrame, /aspect-\[9\/19\.5\]/);
  assert.match(phoneFrame, /rounded-\[34px\]/);
  assert.match(phoneFrame, /p-\[10px\]/);
  assert.match(phoneFrame, /bg-raised/);
  assert.match(phoneFrame, /border-line/);
  assert.equal(phoneFrame.includes('zoom'), false, 'PhoneFrame must not use zoom');
  assert.doesNotMatch(phoneFrame, /#[0-9a-fA-F]{3,8}/, 'PhoneFrame must contain no hex colour');
});

test('SignedOut.tsx has a retry path for qr/start and no white box around the spinner', () => {
  for (const token of BANNED_ROLES) {
    assert.equal(hasToken(signedOut, token), false, `SignedOut.tsx contains banned token: ${token}`);
  }
  const qrIdx = signedOut.indexOf('qr/start');
  assert.ok(qrIdx >= 0, 'SignedOut.tsx references qr/start');
  const windowAround = signedOut.slice(Math.max(0, qrIdx - 600), qrIdx + 1600);
  assert.match(windowAround, /retry|Retry/);
  assert.doesNotMatch(signedOut, /np-frame[^"]*bg-white/);
});
