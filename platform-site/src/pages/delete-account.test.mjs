import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { deleteAccountCopy } from '../i18n/deleteAccount.ts';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const srcDir = path.resolve(__dirname, '..');

function parseBlockValues(block) {
  const entries = new Map();
  const re = /^\s*([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(['"])((?:\\.|(?!\2).)*)\2/gm;
  let m;
  while ((m = re.exec(block)) !== null) {
    entries.set(m[1], m[3]);
  }
  return entries;
}

test('routes, copy parity and endpoint', () => {
  const appSrc = fs.readFileSync(path.join(srcDir, 'App.tsx'), 'utf8');
  assert.match(appSrc, /'\/delete-account': \(\) => import\('\.\/pages\/DeleteAccount'\)/);
  assert.match(appSrc, /'\/privacy': \(\) => import\('\.\/pages\/Privacy'\)/);
  assert.match(appSrc, /path:\s*'\/delete-account'/);
  assert.match(appSrc, /path:\s*'\/privacy'/);

  for (const lang of ['en', 'uk']) {
    assert.deepEqual(Object.keys(deleteAccountCopy[lang]).sort(), Object.keys(deleteAccountCopy.en).sort(), lang);
    for (const [k, v] of Object.entries(deleteAccountCopy[lang])) {
      for (const s of [v].flat()) {
        assert.doesNotMatch(s, /Circles|Phantom|offline|light|mesh/i, `forbidden word in ${lang}.${k}: ${s}`);
      }
    }
  }
  assert.ok(Object.keys(deleteAccountCopy.en).length >= 10);

  const delPageSrc = fs.readFileSync(path.join(srcDir, 'pages', 'DeleteAccount.tsx'), 'utf8');
  assert.match(delPageSrc, /eraseMe\(\)/);
  assert.match(delPageSrc, /startEraseQr/);
  const apiSrc = fs.readFileSync(path.join(srcDir, 'lib', 'account-api.ts'), 'utf8');
  assert.match(apiSrc, /post<[^>]+>\('\/api\/me\/erase'\)/);
  assert.match(apiSrc, /purpose: 'erase'/);

  const privPageSrc = fs.readFileSync(path.join(srcDir, 'pages', 'Privacy.tsx'), 'utf8');
  assert.match(privPageSrc, /legalCopy/);
  const stringLiterals = [...privPageSrc.matchAll(/(['"`])((?:\\.|(?!\1).)*)\1/g)].map((m) => m[2]);
  for (const lit of stringLiterals) {
    if (lit.includes('flex') || lit.includes('grid') || lit.includes('text-') || lit.includes('mt-') || /\bp[xytb]-\d/.test(lit)) {
      continue;
    }
    assert.ok(lit.length <= 40, `string literal longer than 40 chars in Privacy.tsx: ${lit}`);
  }
});

const eraseSrc = fs.readFileSync(path.join(srcDir, '..', 'server', 'erase.py'), 'utf8');
const tables = (name) => {
  const block = eraseSrc.slice(eraseSrc.indexOf(`${name}: dict`), eraseSrc.indexOf('\n}\n', eraseSrc.indexOf(`${name}: dict`)));
  return new Set([...block.matchAll(/\("[a-z]+", "([a-z_]+)"\)/g)].map((m) => m[1]));
};

const DATA_LIST = {
  en: {
    deleted: [
      'Account, sign-ins and devices',
      'Name in the directory',
      'Support messages',
      'Your places and their ratings',
      'Map reports',
      'Licence activations',
    ],
    kept: [
      'Purchase record, without email or account',
      'Points history and linked wallet',
      'Message envelopes until collected, 7 days at most',
      'Solana payments: public on-chain, cannot be removed',
    ],
  },
  uk: {
    deleted: [
      'Обліковий запис, входи й пристрої',
      'Імʼя в довіднику',
      'Листування з підтримкою',
      'Ваші місця та їхні оцінки',
      'Свідчення на мапі',
      'Активації ліцензії',
    ],
    kept: [
      'Запис покупки без пошти й облікового запису',
      'Історія балів і привʼязаний гаманець',
      'Конверти листів до забору, щонайбільше 7 днів',
      'Платежі Solana: публічні в мережі, видалити не можна',
    ],
  },
};

test('every listed item is backed by what erase.py really deletes or keeps', () => {
  const erased = tables('ERASE_MAP');
  const kept = tables('KEPT');
  for (const t of ['accounts', 'sessions', 'brain_devices', 'directory_handles', 'threads', 'messages', 'venues', 'venue_ratings', 'truth_obs', 'activations']) {
    assert.ok(erased.has(t), `erase.py no longer deletes ${t}`);
  }
  for (const t of ['events', 'wallets', 'relay_blobs']) assert.ok(kept.has(t), `erase.py no longer keeps ${t}`);
  assert.ok(erased.has('licenses'), 'purchase row is anonymised, not kept whole');
});

test('the page lists what is deleted, what is kept and the steps, as the server does', () => {
  for (const lang of ['en', 'uk']) {
    const c = deleteAccountCopy[lang];
    assert.deepEqual(c.deleted, DATA_LIST[lang].deleted, `${lang}: deleted items`);
    assert.deepEqual(c.kept, DATA_LIST[lang].kept, `${lang}: kept items`);
    assert.equal(c.steps.length, deleteAccountCopy.en.steps.length, lang);
    assert.ok(c.kept.some((s) => /7/.test(s)), `${lang}: envelopes keep their 7-day limit`);
    assert.ok(c.kept.some((s) => /Solana/.test(s)), `${lang}: on-chain payments are named`);
    assert.ok(c.steps.some((s) => /7/.test(s)), `${lang}: the 7-day wait is a step`);
    assert.doesNotMatch(JSON.stringify(c), /12 months|12 місяц/, `${lang}: inactive purge is off on the server`);
  }
  const page = fs.readFileSync(path.join(srcDir, 'pages', 'DeleteAccount.tsx'), 'utf8');
  assert.match(page, /copy\.deleted\.map/);
  assert.match(page, /copy\.kept\.map/);
  assert.match(page, /copy\.steps\.map/);
  assert.doesNotMatch(page, /inactiveRemoved12Months/);
});

test('no "no account" claims: site sign-in by phone code creates one', () => {
  for (const f of ['security.ts', 'company.ts', 'en.ts', 'uk.ts']) {
    const src = fs.readFileSync(path.join(srcDir, 'i18n', f), 'utf8');
    assert.doesNotMatch(src, /'No account|There is no account|title: 'Без акаунта|'Без акаунта'|Акаунта й номера телефона немає|'Без акаунта й номера/, f);
  }
});
