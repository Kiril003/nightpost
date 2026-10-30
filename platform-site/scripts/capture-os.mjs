#!/usr/bin/env node
// Знімає СПРАВЖНІ екрани PHANTOM OS у роздільній здатності 2×.
//
//   node scripts/capture-os.mjs [http://127.0.0.1:5201] [--out public/assets/os]
//
// Сайт довго показував саморобні копії інтерфейсу в маленьких рамочках.
// Копія завжди бреше в дрібницях; знімок — ні. Тому джерело правди для
// показу продукту — сам продукт, знятий у його рідних 1024×600.

import { chromium } from '/home/kyrylo/phantom_ai/PHANTOM_OS_BLUEPRINT/phantom-os/src/frontend/node_modules/playwright/index.mjs';
import { mkdirSync, readFileSync, unlinkSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';

const BASE = process.argv[2] || 'http://127.0.0.1:5201';
const arg = (f, d) => {
  const i = process.argv.indexOf(f);
  return i > -1 ? process.argv[i + 1] : d;
};
const OUT = arg('--out', '/home/kyrylo/phantom_ai/PHANTOM_OS_BLUEPRINT/platform-site/public/assets/os');
const CHROME = '/home/kyrylo/.cache/ms-playwright/chromium-1228/chrome-linux64/chrome';
const PIN_FILE = '/home/kyrylo/phantom_ai/PHANTOM_OS_BLUEPRINT/phantom-os/.phantom-data/identity/bootstrap_pin';

// Рідний екран пристрою — 1024×600. Знімаємо саме його, лише вдвічі щільніше.
const VW = 1024;
const VH = 600;

const SHOTS = [
  { route: '/chat', name: 'screen-chat', wait: 5200 },
  { route: '/map', name: 'screen-map', wait: 11000, tilt: true },
  { route: '/operator', name: 'screen-operator', wait: 5200 },
  { route: '/', name: 'screen-overview', wait: 5200 },
  { route: '/sentinel', name: 'screen-sentinel', wait: 5200 },
  { route: '/system', name: 'screen-system', wait: 5200 },
];

mkdirSync(OUT, { recursive: true });

const pin = (() => {
  try {
    return readFileSync(PIN_FILE, 'utf8').trim();
  } catch {
    return null;
  }
})();

const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });
const page = await browser.newPage({
  viewport: { width: VW, height: VH },
  deviceScaleFactor: 2,
});

const noise = [];
page.on('pageerror', (e) => noise.push(`JS ${e.message.slice(0, 90)}`));
page.on('console', (m) => m.type() === 'error' && noise.push(`CON ${m.text().slice(0, 90)}`));

await page.goto(`${BASE}/`, { waitUntil: 'networkidle', timeout: 60000 });
await page.waitForTimeout(4000);

if (pin && (await page.getByRole('button', { name: /Увійти/i }).count())) {
  const fields = await page.locator('input').all();
  await fields[0].fill('phantom');
  await fields[1].fill(pin);
  await page.getByRole('button', { name: /Увійти/i }).click();
  await page.waitForTimeout(5000);
  console.log('увійшов');
} else if (!pin) {
  console.log('ПОПЕРЕДЖЕННЯ: PIN не знайдено — знімки будуть з екрана входу');
}

for (const shot of SHOTS) {
  noise.length = 0;
  await page.goto(`${BASE}${shot.route}`, { waitUntil: 'networkidle', timeout: 60000 }).catch(() => {});
  await page.waitForTimeout(shot.wait);

  if (shot.tilt) {
    // Об'ємне місто — найсильніше, що є на мапі. Плаский знімок його ховає.
    // Мапа не виставлена у window, тож нахил вмикаємо тією ж кнопкою, що й
    // людина: це заразом доводить, що кнопка справді працює.
    const tilt = page.getByRole('button', { name: /Об’єм|Об'єм/ });
    if (await tilt.count()) {
      await tilt.first().click();
      await page.waitForTimeout(5000);
    } else {
      console.log('   кнопку об’єму не знайдено — знімок лишиться пласким');
    }
  }

  const path = `${OUT}/${shot.name}.png`;
  // Живий інтерфейс не має спокійного кадру: PHANTOM весь час дихає, і
  // Playwright чекав стабільності до таймауту. `animations: 'disabled'`
  // морозить CSS-анімації на останньому кадрі — саме те, що треба знімку.
  //
  // Мапа не завмирає й від цього: полотно MapLibre перемальовується щокадру,
  // і звичайний знімок чекає спокою, якого не буде. Тому там ідемо через CDP —
  // він знімає те, що зараз на екрані, і нічого не чекає.
  try {
    await page.screenshot({ path, animations: 'disabled', caret: 'hide', timeout: 20000 });
  } catch {
    const cdp = await page.context().newCDPSession(page);
    const { data } = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
    writeFileSync(path, Buffer.from(data, 'base64'));
    await cdp.detach();
    console.log('   знімок через CDP — полотно не завмирає');
  }
  console.log(
    `${shot.route.padEnd(11)} → ${shot.name}.png` + (noise.length ? `   ШУМ: ${[...new Set(noise)].join(' | ')}` : ''),
  );
}

await browser.close();

// PNG зі знімка важить у 5-6 разів більше за webp тієї ж якості, а сторінка
// віддає його кожному відвідувачеві. Переганяємо одразу і прибираємо вихідний.
for (const shot of SHOTS) {
  const png = `${OUT}/${shot.name}.png`;
  try {
    execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-i', png, '-c:v', 'libwebp',
                            '-quality', '82', '-compression_level', '6', `${OUT}/${shot.name}.webp`]);
    unlinkSync(png);
  } catch {
    console.log(`   ${shot.name}: ffmpeg не впорався, лишаю PNG`);
  }
}

console.log(`\nзнімки: ${OUT}`);
