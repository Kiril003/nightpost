import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const root = new URL('../../', import.meta.url).pathname;

const ALLOWED = [
  /phantom-os\.dev/gi,
  /phantom-license(?:-key)?(?:\.fly\.dev)?/gi,
  /phantom-solana/gi,
  /phantom-(?:lang|session)/gi,
  /phantom\.build\.draft/gi,
  /local\.phantom\.companion/gi,
  /phantom-field-/gi,
  /'phantom'|'phantom-debug'/g,
  /\/phantom-os\b/g,
];

const walk = (dir, out = []) => {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else out.push(p);
  }
  return out;
};

const shown = [
  join(root, 'index.html'),
  join(root, 'public/.well-known/security.txt'),
  ...walk(join(root, 'public/i')),
  ...walk(join(root, 'src')).filter(
    (f) => /\.(tsx?|html)$/.test(f) && !/\.test\.|\.d\.ts$/.test(f),
  ),
];

const stripComments = (s) => s.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');

const DEVNET_OK = [
  /cluster=devnet/g,
  /api\.devnet\.solana\.com/g,
  /'devnet'/g,
  /\b(?:use|proofs|webhook)Devnet\b/g,
  /\bDEVNET_[A-Z_]+\b/g,
  /\bdevnet\??:/g,
  /\.devnet\b/g,
];

test('rendered site copy and meta never use the old brand', () => {
  const hits = [];
  for (const file of shown) {
    let text = stripComments(readFileSync(file, 'utf8'));
    for (const ok of ALLOWED) text = text.replace(ok, '');
    text.split('\n').forEach((line, i) => {
      if (/phantom/i.test(line)) hits.push(`${file.slice(root.length)}:${i + 1} ${line.trim().slice(0, 80)}`);
    });
  }
  assert.deepEqual(hits, []);
});

test('rendered copy says test payments, never devnet', () => {
  const hits = [];
  for (const file of shown) {
    let text = stripComments(readFileSync(file, 'utf8'));
    for (const ok of DEVNET_OK) text = text.replace(ok, '');
    text.split('\n').forEach((line, i) => {
      if (/devnet/i.test(line)) hits.push(`${file.slice(root.length)}:${i + 1} ${line.trim().slice(0, 80)}`);
    });
  }
  assert.deepEqual(hits, []);
});
