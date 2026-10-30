import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';

const here = new URL('./', import.meta.url);
const read = (p) => readFileSync(new URL(p, here), 'utf8');

function metaTitles(src) {
  const out = [];
  for (const m of src.matchAll(/meta:\s*\{\s*title:\s*(['"])(.*?)\1/gs)) out.push(m[2]);
  return out;
}

test('every page title names Nightpost and never Phantom', () => {
  const titles = readdirSync(here)
    .filter((f) => f.endsWith('.ts'))
    .flatMap((f) => metaTitles(read(f)).map((t) => [f, t]));
  assert.ok(titles.length >= 16, `found ${titles.length}`);
  for (const [file, title] of titles) {
    assert.match(title, /Nightpost/, `${file}: ${title}`);
    assert.doesNotMatch(title, /phantom/i, `${file}: ${title}`);
  }
});

test('index.html and the manifest name Nightpost and never Phantom', () => {
  const html = read('../../index.html');
  const head = html.slice(0, html.indexOf('</head>')).replace(/<!--[\s\S]*?-->/g, '');
  const named = [
    head.match(/<title>([^<]*)<\/title>/)?.[1],
    head.match(/property="og:title" content="([^"]*)"/)?.[1],
    head.match(/property="og:site_name" content="([^"]*)"/)?.[1],
    JSON.parse(read('../../public/site.webmanifest')).name,
  ];
  for (const value of named) assert.match(value ?? '', /Nightpost/);
  assert.doesNotMatch(head.replace(/phantom-os\.dev/g, ''), /phantom/i);
});
