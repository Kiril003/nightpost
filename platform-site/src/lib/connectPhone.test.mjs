import { test } from 'node:test';
import assert from 'node:assert/strict';
import { linkPhone } from './connectPhone.ts';

const START = { challenge_id: 'c', poll_token: 'p', qr_payload: 'phantom-auth:v1:x', expires_at: Math.floor(Date.now() / 1000) + 120 };
const run = (opts) =>
  new Promise((resolve) => {
    const seen = [];
    const h = linkPhone({
      everyMs: 1,
      ...opts,
      onChange: (s) => {
        seen.push(s.status);
        if (['linked', 'expired', 'unreachable'].includes(s.status)) {
          h.stop();
          resolve({ seen, last: s });
        }
      },
    });
  });

test('a phone that confirms the link ends in «linked», polling the same challenge', async () => {
  const polls = [];
  let n = 0;
  const r = await run({ start: async () => START, poll: async (id, tok) => (polls.push([id, tok]), { status: ++n < 3 ? 'pending' : 'linked' }) });
  assert.deepEqual(r.seen.slice(0, 2), ['loading', 'pending']);
  assert.equal(r.last.status, 'linked');
  assert.deepEqual(polls[0], ['c', 'p']);
});

test('a gone challenge is «expired»; a start that fails is «unreachable», never a fake QR', async () => {
  const gone = await run({ start: async () => START, poll: async () => { throw Object.assign(new Error('x'), { status: 410 }); } });
  assert.equal(gone.last.status, 'expired');
  const down = await run({ start: async () => { throw new Error('net'); }, poll: async () => ({ status: 'pending' }) });
  assert.equal(down.last.status, 'unreachable');
  assert.equal(down.last.qr, null);
});
