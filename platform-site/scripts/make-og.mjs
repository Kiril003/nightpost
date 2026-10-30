#!/usr/bin/env node
// Малює public/og.png — те єдине, що бачить людина, коли посилання
// прилітає їй у месенджер чи лист.
//
//   node scripts/make-og.mjs            (playwright шукається як у site-qa.mjs)
//
// Картинку тримаємо в коді, а не в редакторі: інакше вона лишається від
// попереднього вигляду сайту. Вигляд — бренд Nightpost (cloud/brand): полотно
// #0A0C10, локап зі знаком «Естафета» (шляхи беремо з src/components/Logo.tsx,
// щоб не було другої копії), два теплі прорізи, Manrope. Без градієнтів
// і сяйва; слова «Phantom» на картинці немає. Заголовок веде ширину — люди,
// мапа, гроші й помічник, — а не мережу (позиціонування 05.10).

import { readFileSync, existsSync } from 'node:fs';
import { createRequire } from 'node:module';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));

async function loadChromium() {
  const candidates = [
    // Інше дерево з playwright (наприклад, головний клон): PLAYWRIGHT_MJS=/…/playwright/index.mjs
    ...(process.env.PLAYWRIGHT_MJS ? [process.env.PLAYWRIGHT_MJS] : []),
    join(ROOT, 'node_modules', 'playwright', 'index.mjs'),
    join(ROOT, '..', 'phantom-os', 'src', 'frontend', 'node_modules', 'playwright', 'index.mjs'),
  ];
  for (const entry of candidates) if (existsSync(entry)) return import(pathToFileURL(entry).href);
  return import(pathToFileURL(createRequire(import.meta.url).resolve('playwright')).href);
}

const logo = readFileSync(join(ROOT, 'src/components/Logo.tsx'), 'utf8');
const grab = (name) => {
  const m = logo.match(new RegExp(`const ${name} =\\s*'([^']+)'`));
  if (!m) throw new Error(`у Logo.tsx немає ${name}`);
  return m[1];
};
const lockup = `<svg viewBox="${grab('LOCKUP_VIEWBOX')}" height="44"><path fill="#F2A93B" d="${grab('LOCKUP_MARK')}"/><path fill="#F4F1EC" d="${grab('LOCKUP_WORD')}"/></svg>`;

const { chromium } = await loadChromium();
const font = (f) => readFileSync(join(ROOT, 'node_modules/@fontsource-variable/manrope/files', f)).toString('base64');
const CYR = font('manrope-cyrillic-wght-normal.woff2');
const LAT = font('manrope-latin-wght-normal.woff2');

const html = `<!doctype html><meta charset="utf-8">
<style>
  @font-face { font-family: M; src: url(data:font/woff2;base64,${CYR}) format('woff2'); font-weight: 200 800; }
  @font-face { font-family: M; src: url(data:font/woff2;base64,${LAT}) format('woff2'); font-weight: 200 800; unicode-range: U+0000-024F, U+02BC, U+2019; }
  html, body { margin: 0; }
  body { width: 1200px; height: 630px; background: #0A0C10; color: #F4F1EC; font-family: M, sans-serif;
         display: flex; flex-direction: column; justify-content: space-between; padding: 72px 80px; box-sizing: border-box; position: relative; }
  .slits { position: absolute; right: 96px; top: 222px; display: flex; gap: 34px; }
  .slits i { display: block; width: 116px; height: 19px; border-radius: 10px; background: #F2A93B; }
  h1 { font-size: 64px; line-height: 1.08; letter-spacing: -0.025em; font-weight: 700; margin: 0; max-width: 720px; }
  p { font-size: 28px; line-height: 1.4; color: #B6BCC6; margin: 26px 0 0; max-width: 760px; }
  .foot { display: flex; justify-content: space-between; font-size: 24px; color: #939AA6; border-top: 1px solid #232831; padding-top: 28px; }
</style>
<div>${lockup}</div>
<div class="slits"><i></i><i></i></div>
<div>
  <h1>Your people, map, money<br>and assistant — in one app.</h1>
  <p>Message, find your way and pay right in the chat. Private, no phone number — even offline.</p>
</div>
<div class="foot"><span>Early version for Android</span><span>Nightpost · UA / EN</span></div>`;

const browser = await chromium.launch(
  process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {},
);
const page = await browser.newPage({ viewport: { width: 1200, height: 630 } });
await page.setContent(html, { waitUntil: 'load' });
await page.evaluate(() => document.fonts.ready);
await page.screenshot({ path: join(ROOT, 'public/og.png') });
await browser.close();
console.log('public/og.png готовий');
