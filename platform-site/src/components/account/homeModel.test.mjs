import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  displayName,
  initials,
  memberSince,
  androidBuild,
  canDownload,
  shaGroups,
  sizeLabel,
  previewVenue,
} from './homeModel.ts';

const baseAccount = {
  id: 'acc_1',
  email: 'mia@x.io',
  display_name: 'Alex Rivers',
  pub_key: '1234567890abcdef1234567890abcdef',
  locale: 'en',
  role: 'user',
  created_at: '2026-09-03T10:00:00Z',
  unread: 0,
};

test('displayName precedence: display_name, then email, then truncated pub_key, then empty', () => {
  assert.equal(displayName(baseAccount), 'Alex Rivers');
  assert.equal(displayName({ ...baseAccount, display_name: '' }), 'mia@x.io');
  assert.equal(
    displayName({ ...baseAccount, display_name: '', email: '' }),
    '12345678…abcdef',
  );
  assert.equal(
    displayName({ ...baseAccount, display_name: '', email: '', pub_key: '' }),
    '',
  );
});

test('initials extracts first two words or first two characters before @', () => {
  assert.equal(initials('Alex Rivers'), 'AR');
  assert.equal(initials('mia@x.io'), 'MI');
  assert.equal(initials(''), '');
});

test('memberSince formats en as Month YYYY and uk as MM.YYYY', () => {
  assert.equal(memberSince('2026-09-03T10:00:00Z', 'en'), 'September 2026');
  assert.equal(memberSince('2026-09-03T10:00:00Z', 'uk'), '09.2026');
});

test('androidBuild picks android from build list and returns null for empty', () => {
  const win = { platform: 'windows', filename: 'np.exe', size: 100 };
  const andr = { platform: 'android', filename: 'np.apk', size: 200 };
  assert.deepEqual(androidBuild([win, andr]), andr);
  assert.equal(androidBuild([]), null);
});

test('canDownload checks policy, non-revoked licences, and staff flag', () => {
  const revokedLic = {
    id: 'l1',
    tier: 'personal',
    email: 'mia@x.io',
    updates_until: null,
    revoked: true,
    max_activations: 3,
    created_at: '2026-01-01T00:00:00Z',
    devices: [],
  };
  const liveLic = { ...revokedLic, id: 'l2', revoked: false };
  const baseMe = {
    account: baseAccount,
    licenses: [revokedLic],
    staff_unread: 0,
    downloads: [],
    download_policy: 'licence',
  };

  assert.equal(canDownload({ ...baseMe, download_policy: 'license' }, false), false);
  assert.equal(canDownload({ ...baseMe, licenses: [revokedLic, liveLic] }, false), true);
  assert.equal(canDownload(baseMe, true), true);
  assert.equal(canDownload({ ...baseMe, download_policy: 'open' }, false), true);
  assert.equal(canDownload({ ...baseMe, download_policy: 'account' }, false), true);
});

test('shaGroups splits 64-char hex into 8 chunks of 8', () => {
  const hex = 'a'.repeat(64);
  const groups = shaGroups(hex);
  assert.equal(groups.length, 8);
  assert.ok(groups.every((g) => g === 'aaaaaaaa'));
});

test('sizeLabel formats MB to one decimal in en and uk, null when missing', () => {
  assert.equal(sizeLabel(78223360, 'en'), '74.6 MB');
  assert.equal(sizeLabel(78223360, 'uk'), '74,6 МБ');
  assert.equal(sizeLabel(null, 'en'), null);
  assert.equal(sizeLabel(undefined, 'uk'), null);
});

test('previewVenue prefers published venue with page over earlier draft, null when all pages null', () => {
  const page = { v: 1, blocks: [] };
  const draft = { id: 'v1', name: 'Draft', published: false, rev: 1, page, rating: null };
  const pub = { id: 'v2', name: 'Pub', published: true, rev: 2, page, rating: null };
  const empty = { id: 'v3', name: 'Empty', published: true, rev: 0, page: null, rating: null };

  assert.deepEqual(previewVenue([draft, pub]), pub);
  assert.deepEqual(previewVenue([empty, draft]), draft);
  assert.equal(previewVenue([empty]), null);
  assert.equal(previewVenue([]), null);
});

test('a venues route the server does not serve reads as not open yet, any other failure as failed', async () => {
  const { placesFailure } = await import('./homeModel.ts');
  const { homeCopy } = await import('../../i18n/accountHome.ts');
  class Unavailable extends Error {}
  assert.equal(placesFailure(new Unavailable(), Unavailable), 'notOpen');
  assert.equal(placesFailure(new Error('500'), Unavailable), 'failed');
  assert.equal(placesFailure('x', Unavailable), 'failed');
  assert.equal(homeCopy.en.stageNotOpen, 'Not open yet');
  assert.equal(homeCopy.uk.stageNotOpen, 'Ще не відкрито');
});
