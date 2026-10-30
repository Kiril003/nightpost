import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { licenceSectionVisible } from './homeModel.ts';
import { homeCopy } from '../../i18n/accountHome.ts';

const read = (p) => readFileSync(new URL(p, import.meta.url), 'utf8');

const signedOut = read('./SignedOut.tsx');
const accountHome = read('./AccountHome.tsx');
const accountPage = read('../../pages/Account.tsx');
const panels = read('./Panels.tsx');
const accountI18n = read('../../i18n/account.ts');

const doorsBlocks = [...accountI18n.matchAll(/\n  doors: \{([\s\S]*?)\n  login: \{/g)].map((m) => m[1]).filter((b) => b.includes("lead: '"));
const field = (block, name) => {
  const m = block.match(new RegExp(`\\n    ${name}: '((?:[^'\\\\]|\\\\.)*)'`));
  return m ? m[1] : null;
};
const answersOf = (block) => {
  const m = block.match(/\n    answers: \[([\s\S]*?)\n    \],/);
  return m ? m[1] : '';
};

test('SignedOut.tsx no longer references keyDoorTitle, lostDoor, door=key or DoorCard', () => {
  for (const banned of ['keyDoorTitle', 'lostDoor', 'door=key', 'DoorCard']) {
    assert.equal(
      signedOut.includes(banned),
      false,
      `SignedOut.tsx must not reference ${banned}`,
    );
  }
});

test('en and uk doors.lead and doors.answers in i18n/account.ts contain no licen / ліценз', () => {
  assert.equal(doorsBlocks.length, 2, 'uk and en doors blocks found');
  const leads = doorsBlocks.map((b) => field(b, 'lead'));
  assert.ok(leads.includes('Sign in with your phone: scan the code in Nightpost, or get a code by email. No password.'));
  assert.ok(leads.includes('Вхід телефоном: відскануйте код у Nightpost або отримайте код поштою. Без пароля.'));
  for (const block of doorsBlocks) {
    assert.doesNotMatch(field(block, 'lead'), /licen|ліценз/i);
    const answers = answersOf(block);
    assert.ok(answers.length > 0, 'answers found');
    assert.doesNotMatch(answers, /licen|ліценз/i);
  }
});

test('SignedOut.tsx: inside QrDoor, Android branch renders openApp link before QR svg and has Show QR code toggle', () => {
  const qrDoorStart = signedOut.indexOf('function QrDoor');
  const emailDoorStart = signedOut.indexOf('function EmailDoor');
  assert.ok(qrDoorStart > 0 && emailDoorStart > qrDoorStart, 'QrDoor block exists');
  const qrCard = signedOut.slice(qrDoorStart, emailDoorStart);

  const openAppIdx = qrCard.indexOf('labels.openApp');
  const qrSvgIdx = qrCard.indexOf('shownQr.path');
  assert.ok(openAppIdx > 0, 'QrDoor renders labels.openApp');
  assert.ok(qrSvgIdx > 0, 'QrDoor renders QR svg');
  assert.ok(openAppIdx < qrSvgIdx, 'Android openApp link comes before the QR svg');

  assert.match(qrCard, /Show QR code|showQr/);
  assert.match(accountI18n, /showQr: 'Show QR code'/);
  assert.match(accountI18n, /showQr: 'Показати QR-код'/);
});

test('licenceSectionVisible returns false for empty licenses and true when a licence exists; AccountHome and Account use it', () => {
  assert.equal(licenceSectionVisible({ licenses: [] }), false);
  assert.equal(
    licenceSectionVisible({
      licenses: [
        {
          id: 'lic-1',
          tier: 'personal',
          max_activations: 3,
          updates_until: '2027-01-01T00:00:00Z',
          created_at: '2026-01-01T00:00:00Z',
          revoked: false,
          devices: [],
        },
      ],
    }),
    true,
  );

  assert.match(accountHome, /licenceSectionVisible\(me\)/);
  assert.match(accountHome, /text-title-2[^>]*>\{h\.licence\}/);
  assert.match(accountHome, /\{h\.needLicence\}/);
  assert.equal(homeCopy.en.needLicence, 'This build needs a licence.');
  assert.equal(homeCopy.uk.needLicence, 'Ця збірка потребує ліцензії.');

  assert.match(accountPage, /licenceSectionVisible/);
  assert.match(
    accountPage,
    /item\.id !== 'licenses'/,
    'Account.tsx hides the licenses tab when licenceSectionVisible(me) is false',
  );

  assert.match(panels, /\/delete-account/, 'Security tab includes link to /delete-account');
  assert.match(accountI18n, /needLicense: 'This build needs a licence\.'/);
  assert.match(accountI18n, /needLicense: 'Ця збірка потребує ліцензії\.'/);
});

test('email door: labels only, no prose about spam folders, code length or expiry', () => {
  const loginBlocks = [...accountI18n.matchAll(/\n  login: \{(\n    title: '[\s\S]*?)\n  \},/g)].map((m) => m[1]);
  assert.equal(loginBlocks.length, 2);
  for (const block of loginBlocks) {
    assert.equal(field(block, 'lead'), '');
    assert.doesNotMatch(block, /Spam|Спам|six-digit|шести цифр|creates it|створиться|Licence key|ключ ліцензії/);
  }
  assert.doesNotMatch(signedOut, /stage === 'email' \? c\.lead/);
});

test('what the server keeps names both sign-in ways, email code and QR', () => {
  const cabinet = read('../../i18n/cabinet.ts');
  assert.doesNotMatch(cabinet, /emailed code only|лише кодом на пошту/);
  assert.match(cabinet, /'A password: sign-in is an emailed code or a QR from the app\.'/);
  assert.match(cabinet, /'Пароль: вхід кодом із пошти або QR із застосунку\.'/);
});
