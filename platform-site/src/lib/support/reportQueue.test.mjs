import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { actionsFor, decisionBody, nextOffset, whoLine } from './reportQueue.ts';

const panel = readFileSync(new URL('../../components/account/StaffSystem.tsx', import.meta.url), 'utf8');

test('an open report is decided once, an appeal once, a closed one never', () => {
  assert.deepEqual(actionsFor({ status: 'sent', appeal: null }), ['uphold', 'dismiss']);
  assert.deepEqual(actionsFor({ status: 'reviewing', appeal: null }), ['uphold', 'dismiss']);
  assert.deepEqual(actionsFor({ status: 'dismissed', appeal: null }), []);
  const appeal = { reason: 'mistake', text: 'x', at: '', status: 'submitted' };
  assert.deepEqual(actionsFor({ status: 'dismissed', appeal }), ['keep', 'overturn']);
  assert.deepEqual(actionsFor({ status: 'action', appeal: { ...appeal, status: 'overturned' } }), []);
});

test('every decision carries a reason the server will accept', () => {
  assert.equal(decisionBody('uphold', '   '), null);
  assert.deepEqual(decisionBody('uphold', ' spam '), { status: 'action', reason: 'spam' });
  assert.deepEqual(decisionBody('dismiss', 'ok'), { status: 'dismissed', reason: 'ok' });
  assert.deepEqual(decisionBody('keep', 'stands'), { appeal_status: 'upheld', reason: 'stands' });
  assert.deepEqual(decisionBody('overturn', 'wrong'), { appeal_status: 'overturned', reason: 'wrong' });
  assert.equal(decisionBody('dismiss', 'x'.repeat(400)).reason.length, 300);
});

test('the moderator sees whom the report is about', () => {
  const base = { target_id: 'm1', sender_key: 'key-9', chat_id: 'c1' };
  assert.equal(whoLine({ ...base, target_kind: 'message' }), 'message · key-9 · c1');
  assert.equal(whoLine({ ...base, target_kind: 'person', chat_id: null }), 'person · m1');
});

test('the queue pages until the last open report', () => {
  assert.equal(nextOffset({ rows: [1, 2], total: 5, offset: 0 }), 2);
  assert.equal(nextOffset({ rows: [1], total: 5, offset: 4 }), null);
  assert.equal(nextOffset({ rows: [], total: 5, offset: 6 }), null);
});

test('the moderation panel reads the phone report queue and posts decisions', () => {
  assert.match(panel, /\/api\/staff\/support\/reports/);
  assert.match(panel, /\/decision`/);
  assert.match(panel, /actionsFor\(/);
  assert.match(panel, /decisionBody\(/);
  assert.match(panel, /whoLine\(/);
  assert.match(panel, /nextOffset\(/);
});
