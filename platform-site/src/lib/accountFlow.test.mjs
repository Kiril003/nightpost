import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  cancelEraseStep,
  codeWasSent,
  keyIsDead,
  qrPollStops,
  recoverConfirmStep,
  recoverRequestStep,
  revokeAllSignsOut,
  signInLeadsTo,
  statusOf,
} from './accountFlow.ts';

const read = (p) => readFileSync(new URL(p, import.meta.url), 'utf8');

test('QR polling stops on ip mismatch and disabled account, not only on expiry', () => {
  for (const s of [401, 403, 404, 409, 410]) assert.equal(qrPollStops(s), true, String(s));
  for (const s of [0, 429, 500]) assert.equal(qrPollStops(s), false, String(s));
  assert.match(read('../components/account/SignedOut.tsx'), /qrPollStops\(statusOf\(err\)\)/);
});

test('signing in during the deletion grace goes to the cancel step, not the cabinet', () => {
  assert.equal(signInLeadsTo({ scope: 'erase_cancel', due_at: 1 }), 'pendingErase');
  assert.equal(signInLeadsTo({}), 'cabinet');
  assert.equal(signInLeadsTo(undefined), 'cabinet');
  const account = read('../pages/Account.tsx');
  assert.match(account, /signInLeadsTo\(verified\) === 'pendingErase'/);
  assert.match(account, /navigate\('\/delete-account', \{ state: \{ pendingDueAt/);
  const del = read('../pages/DeleteAccount.tsx');
  assert.match(del, /pendingDueAt/);
  assert.match(del, /signInLeadsTo\(polled\) === 'pendingErase'/);
  assert.match(read('../components/account/SignedOut.tsx'), /writeToken\(res\.token\);\s*await onDone\(res\.token, res\);/);
});

test('email door stays on the address step when the server could not send the code', () => {
  assert.equal(codeWasSent({ ok: true, sent: false }), false);
  assert.equal(codeWasSent({ ok: true, sent: true }), true);
  assert.equal(codeWasSent({ ok: true }), true);
  assert.match(read('../components/account/SignedOut.tsx'), /if \(codeWasSent\(res\)\) setStage\('code'\);\s*else setError\('offline'\);/);
});

test('key recovery: a refused request never shows "code sent"; only 400 blames the code', () => {
  assert.equal(recoverRequestStep(200), 'sent');
  for (const s of [400, 422, 429, 500, 503]) assert.equal(recoverRequestStep(s), 'offline', String(s));
  assert.equal(recoverConfirmStep(200), 'done');
  assert.equal(recoverConfirmStep(400), 'wrongCode');
  for (const s of [429, 500, 502]) assert.equal(recoverConfirmStep(s), 'offline', String(s));
  const kc = read('../components/account/KeyCheck.tsx');
  assert.match(kc, /recoverRequestStep\(res\.status\) === 'sent'/);
  assert.match(kc, /recoverConfirmStep\(res\.status\)/);
});

test('a key is remembered only after the server accepted it', () => {
  assert.equal(keyIsDead(404), true);
  assert.equal(keyIsDead(400), true);
  assert.equal(keyIsDead(0), false);
  const kc = read('../components/account/KeyCheck.tsx');
  assert.match(kc, /const status = await load\(value\);/);
  assert.match(kc, /remember && status >= 200 && status < 300\) localStorage\.setItem/);
});

test('"sign out everywhere" keeps the user signed in when the server did not revoke', () => {
  assert.equal(revokeAllSignsOut(null), true);
  assert.equal(revokeAllSignsOut(401), true);
  for (const s of [0, 429, 500]) assert.equal(revokeAllSignsOut(s), false, String(s));
  const panels = read('../components/account/Panels.tsx');
  assert.match(panels, /if \(revokeAllSignsOut\(status\)\) onLoggedOut\(\);\s*else setRevokeFailed\(true\);/);
  assert.doesNotMatch(panels, /finally \{\s*setBusy\(false\);\s*onLoggedOut\(\);/);
});

test('cancelling deletion shows an error on network or server failure', () => {
  assert.equal(cancelEraseStep(401), 'stale');
  assert.equal(cancelEraseStep(404), 'nothing');
  for (const s of [0, 429, 500]) assert.equal(cancelEraseStep(s), 'failed', String(s));
  assert.equal(statusOf({ status: 500 }), 500);
  assert.equal(statusOf(new Error('x')), -1);
  const del = read('../pages/DeleteAccount.tsx');
  assert.match(del, /else setFailed\(true\);/);
  assert.match(del, /statusOf\(err\) === 401\) \{\s*setReady\(false\);/);
  assert.match(del, /<Link to="\/account"/);
});
