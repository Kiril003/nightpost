import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { chainStatus } from './chainStatus.ts';

test('an answer without a finalized status is neutral, not a failure', () => {
  assert.equal(chainStatus({ confirmationStatus: 'finalized', err: null }), 'finalized');
  assert.equal(chainStatus(null), 'unconfirmed');
  assert.equal(chainStatus({ confirmationStatus: 'confirmed', err: null }), 'unconfirmed');
  assert.equal(chainStatus({ confirmationStatus: 'finalized', err: { InstructionError: [0, 'x'] } }), 'unconfirmed');
});

test('"did not answer" is shown only for a failed or timed-out request', () => {
  const page = readFileSync(new URL('../pages/HomeV7.tsx', import.meta.url), 'utf8');
  assert.match(page, /status: chainStatus\(r\.value\[0\]\)/);
  assert.match(page, /chain\.status === 'unknown' \? <p>\{r\.live\.unknown\}/);
  assert.match(page, /chain\.status === 'unconfirmed'/);
  assert.match(page, /window\.setTimeout\(\(\) => \{\s*timedOut = true;/);
});
