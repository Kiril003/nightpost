#!/usr/bin/env node
// Візуальний контроль сайту: обходить кожну сторінку справжнім браузером,
// знімає її цілком і збирає ВСІ помилки — консоль, мережу, JS.
//
//   node scripts/site-qa.mjs http://127.0.0.1:5301 [--out DIR] [--view 1440x900]
//
// Порожній розділ «ПОМИЛКИ» — єдиний доказ, що сторінка ціла.

import { mkdirSync, existsSync, readdirSync } from 'node:fs';
import { createRequire } from 'node:module';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = fileURLToPath(new URL('.', import.meta.url));

// Playwright не встановлений у цьому пакеті — його тягне фронт phantom-os.
// Раніше тут стояв абсолютний шлях у чужий node_modules: варто було перенести
// теку чи оновити залежність — і єдина перевірка сайту мовчки зникала.
function loadChromium() {
  const require_ = createRequire(import.meta.url);
  const candidates = [
    join(HERE, '..', 'node_modules', 'playwright'),
    join(HERE, '..', '..', 'phantom-os', 'src', 'frontend', 'node_modules', 'playwright'),
  ];
  for (const dir of candidates) {
    const entry = join(dir, 'index.mjs');
    if (existsSync(entry)) return import(pathToFileURL(entry).href);
  }
  try {
    return import(pathToFileURL(require_.resolve('playwright')).href);
  } catch {
    console.error(
      'playwright не знайдено. Встанови його тут (npm i -D playwright) або\n' +
      'лиши доступним у phantom-os/src/frontend/node_modules.'
    );
    process.exit(2);
  }
}

const { chromium } = await loadChromium();

const BASE = process.argv[2] || 'http://127.0.0.1:5301';
const arg = (f, d) => {
  const i = process.argv.indexOf(f);
  return i > -1 ? process.argv[i + 1] : d;
};
const OUT = arg('--out', '/tmp/phantom-site-qa');
const [VW, VH] = arg('--view', '1440x900').split('x').map(Number);

// Номер збірки Chromium змінюється з кожним оновленням playwright, тож
// прибитий цвяхами `chromium-1228` ламався б на першому ж апдейті. Беремо
// найновішу наявну збірку, а якщо жодної — хай playwright шукає сам.
function findChrome() {
  const root = process.env.PLAYWRIGHT_BROWSERS_PATH || join(homedir(), '.cache', 'ms-playwright');
  if (!existsSync(root)) return undefined;
  const builds = readdirSync(root)
    .filter(d => /^chromium-\d+$/.test(d))
    .sort((a, b) => Number(b.split('-')[1]) - Number(a.split('-')[1]));
  for (const b of builds) {
    const exe = join(root, b, 'chrome-linux64', 'chrome');
    if (existsSync(exe)) return exe;
  }
  return undefined;
}

const CHROME = findChrome();

const ROUTES = (arg('--routes', '') || '/,/phantom-os,/companion,/how,/pricing,/download,/packs,/security,/company,/account,/world,/legal')
  .split(',')
  .filter(Boolean);

mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch({
  ...(CHROME ? { executablePath: CHROME } : {}),
  args: ['--no-sandbox'],
});
// Знімок на всю сторінку не гортає її — IntersectionObserver нижче згину не
// спрацьовує, і половина сайту лишається на opacity:0. Це артефакт зйомки, а
// не дефект. Тому знімаємо з reduced-motion: сайт тоді малює все статично.
const page = await browser.newPage({
  viewport: { width: VW, height: VH },
  reducedMotion: arg('--motion', 'off') === 'on' ? 'no-preference' : 'reduce',
});

const bag = [];
page.on('pageerror', (e) => bag.push(`JS  ${e.message.slice(0, 120)}`));
page.on('console', (m) => {
  if (m.type() === 'error') bag.push(`CON ${m.text().slice(0, 120)}`);
});
page.on('response', (r) => {
  if (r.status() >= 400) bag.push(`NET ${r.status()} ${r.url().replace(BASE, '')}`);
});
page.on('requestfailed', (r) => {
  const why = r.failure()?.errorText ?? '';
  if (why.includes('ABORTED')) return;
  bag.push(`REQ ${why} ${r.url().replace(BASE, '').slice(0, 70)}`);
});

console.log(`PHANTOM site · ${VW}×${VH} · ${BASE}\n`);

for (const route of ROUTES) {
  bag.length = 0;
  const slug = route === '/' ? 'home' : route.replace(/\//g, '-').replace(/^-/, '');
  await page.goto(`${BASE}${route}`, { waitUntil: 'networkidle', timeout: 60000 }).catch((e) => {
    bag.push(`NAV ${e.message.slice(0, 90)}`);
  });
  // Скрол до низу і назад — інакше все, що з'являється по появі у в'юпорті,
  // лишиться невидимим на знімку і в помилках.
  await page.evaluate(async () => {
    const step = window.innerHeight * 0.9;
    for (let y = 0; y < document.body.scrollHeight; y += step) {
      window.scrollTo(0, y);
      await new Promise((r) => setTimeout(r, 90));
    }
    window.scrollTo(0, 0);
  });
  await page.waitForTimeout(1200);

  // Смуги з content-visibility браузер не малює поза кадром, і на знімку всієї
  // сторінки вони виходять порожніми. Для зйомки обмеження знімаємо.
  await page.addStyleTag({ content: '.cvs { content-visibility: visible !important }' });
  await page.waitForTimeout(400);
  await page.screenshot({ path: `${OUT}/${slug}.png`, fullPage: true });

  // Горизонтальний скрол тіла — завжди дефект.
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  if (overflow > 2) bag.push(`OVF сторінка їде вбік на ${overflow}px`);

  // Дрібні цілі дотику.
  const small = await page.evaluate(() => {
    const out = [];
    for (const el of document.querySelectorAll('a,button,[role="button"],input,select')) {
      const r = el.getBoundingClientRect();
      if (r.width === 0 || r.height === 0) continue;
      if (r.width < 44 || r.height < 44) {
        out.push(`${el.tagName.toLowerCase()} ${Math.round(r.width)}×${Math.round(r.height)} «${(el.textContent || '').trim().slice(0, 24)}»`);
      }
    }
    return out.slice(0, 6);
  });

  const h = await page.evaluate(() => document.body.scrollHeight);
  console.log(`── ${route}  (${h}px)`);
  if (bag.length) {
    console.log('   ПОМИЛКИ:');
    for (const b of [...new Set(bag)]) console.log(`     ${b}`);
  } else {
    console.log('   ПОМИЛОК НЕМАЄ');
  }
  if (small.length && VW < 900) console.log(`   дрібні цілі: ${small.join(' · ')}`);
}

await browser.close();
console.log(`\nзнімки: ${OUT}`);
