import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const FILES = ['en.ts', 'uk.ts', 'security.ts'];
const WORDS = /\b(?:six|шість|шести)\b[\s-]*(?:digits?|цифр)/gi;
const COUNT = /(\d+)[\s-]*(?:digits?|цифр)/gi;

test('every mention of the safety-number length says 48 (24 per key), never six', () => {
  for (const f of FILES) {
    const src = readFileSync(new URL(`./${f}`, import.meta.url), 'utf8');
    assert.equal((src.match(WORDS) ?? []).length, 0, `${f} still says six digits`);
    for (const m of src.matchAll(COUNT)) {
      assert.ok(['48', '24'].includes(m[1]), `${f}: ${m[0]}`);
    }
  }
});
