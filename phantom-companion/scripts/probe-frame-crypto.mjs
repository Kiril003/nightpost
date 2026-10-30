#!/usr/bin/env node
/**
 * Чи можна зашифрувати КАДР так, щоб сервер його не зрозумів.
 *
 * Сам прилад — у `scripts/probe-frame-crypto.html`. Цей файл лише подає ту
 * сторінку своєму Chromium без вікна й друкує вирок у консоль. Сторінка
 * окремим файлом навмисно: її треба вміти відкрити НА АПАРАТІ, де немає ні
 * node, ні сервера, ні мережі.
 *
 * ## Чому це питання вирішує долю SFU
 *
 * `docs/MEETS_SCALE.md` міряє: сітка коштує (N-1) ніг на апараті, SFU — одну.
 * При N=33 це 2 669 кбіт/с проти 83. Але звичайний SFU термінує SRTP, щоб
 * пересилати, тобто ЧУЄ розмову — а це суперечить тому, чим є цей продукт.
 *
 * Вихід один: шифрувати сам кадр ПОВЕРХ SRTP (Insertable Streams / Encoded
 * Transform, він же SFrame). Тоді SFU пересилає шифротекст, якого не розуміє,
 * і виграш у смузі дістається БЕЗ віддачі приватності.
 *
 * ## Чому наявність символа — не відповідь
 *
 * `'RTCRtpScriptTransform' in window` може бути правдою там, де трансформа не
 * вішається на живий `RTCPeerConnection`, або вішається й не викликається ані
 * разу, або викликається й кадр повертає без змін. Тому прилад доводить
 * БАЙТАМИ і доводить дві половини, з яких жодна не необов'язкова:
 *
 *   1. те, що побачив би СЕРВЕР, не дорівнює відкритому кадру;
 *   2. те, що дістав СПІВРОЗМОВНИК, дорівнює відкритому кадру БАЙТ У БАЙТ.
 *
 * Перша без другої — це зламаний дзвінок. Друга без першої — це відкритий
 * кадр, який просто проїхав крізь трансформу.
 *
 * ## ЧОГО ЦЕЙ ЗАПУСК НЕ ДОВОДИТЬ
 *
 * Дзвінок живе не тут, а в Android System WebView НА АПАРАТІ. WebView
 * оновлюється з Play окремо від системи, тож його версія заздалегідь
 * невідома й може бути іншою за цей Chromium. Вимір на ноуті відповідає на
 * питання «чи вміє це рушій такої версії», а не «чи вміє це телефон
 * власника». Друге міряється відкриванням тієї самої сторінки на апараті:
 *
 *   node scripts/probe-frame-crypto.mjs --emit /tmp/probe.html
 *   adb push /tmp/probe.html /sdcard/Download/probe.html
 *   adb shell am start -a android.intent.action.VIEW \
 *     -d file:///sdcard/Download/probe.html
 *
 * Сторінка друкує свій `navigator.userAgent` і малює вирок великими
 * літерами — знімок екрана і є доказ.
 */

import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync, readFileSync, existsSync, copyFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const PAGE_FILE = join(HERE, 'probe-frame-crypto.html');

const arg = (flag, fallback) => {
  const i = process.argv.indexOf(flag);
  return i < 0 || i + 1 >= process.argv.length ? fallback : process.argv[i + 1];
};
const EMIT = arg('--emit', null);
const HOLD_MS = Number(arg('--hold', '4000'));
const JSON_OUT = arg('--json', null);

if (!existsSync(PAGE_FILE)) {
  console.error(`немає сторінки приладу: ${PAGE_FILE}`);
  process.exit(2);
}

if (EMIT) {
  copyFileSync(PAGE_FILE, EMIT);
  console.log(`сторінку записано: ${EMIT}`);
  console.log('Це режим ДЛЯ АПАРАТА: відкрий її у WebView/браузері телефона.');
  console.log('Вирок намальований великими літерами — знімок екрана і є доказ.');
  process.exit(0);
}

const BROWSERS = [
  '/usr/bin/chromium', '/usr/bin/chromium-browser',
  '/usr/bin/google-chrome', '/usr/bin/google-chrome-stable',
];
const browser = BROWSERS.find((p) => existsSync(p));
if (!browser) {
  console.error('немає Chromium: шукав ' + BROWSERS.join(', '));
  process.exit(2);
}

const page = readFileSync(PAGE_FILE, 'utf8');

const result = await new Promise((resolve) => {
  const server = createServer((req, res) => {
    if (req.method === 'POST' && req.url === '/result') {
      let body = '';
      req.on('data', (c) => { body += c; });
      req.on('end', () => {
        res.writeHead(204).end();
        server.close();
        resolve(JSON.parse(body));
      });
      return;
    }
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' }).end(page);
  });
  server.listen(0, '127.0.0.1', () => {
    const port = server.address().port;
    const profile = mkdtempSync(join(tmpdir(), 'frame-crypto-'));
    const child = spawn(browser, [
      '--headless=new', '--no-sandbox', '--disable-gpu',
      // Ті самі три прапорці, що й у measure-mesh-legs.mjs: без них Chromium
      // душить таймери й getStats у вкладці, якої не видно, і прилад міряє нулі.
      '--disable-background-timer-throttling',
      '--disable-renderer-backgrounding',
      '--disable-backgrounding-occluded-windows',
      '--autoplay-policy=no-user-gesture-required',
      '--use-fake-device-for-media-stream',
      // ЦЕЙ ПРАПОРЕЦЬ — НЕ ПРО ІНТЕРФЕЙС. Без дозволу на медіа Chromium
      // приховує локальну адресу за mDNS-кандидатом (`…​.local`), а в режимі
      // без вікна таке ім'я нікому розв'язувати — кандидати виглядають
      // справними (тип host, приймаються без відмов), але жодна пара не
      // проходить, і ICE назавжди лишається «new». Прилад без цього рядка
      // доповідав «НЕ ВМІЄ» про цілком справний рушій.
      '--use-fake-ui-for-media-stream',
      `--user-data-dir=${profile}`,
      `http://127.0.0.1:${port}/?hold=${HOLD_MS}`,
    ], { stdio: 'ignore' });
    const stop = () => {
      try { child.kill('SIGKILL'); } catch { /* уже мертвий */ }
      rmSync(profile, { recursive: true, force: true });
    };
    process.on('exit', stop);
    setTimeout(() => {
      stop();
      try { server.close(); } catch { /* уже закритий */ }
      resolve({ ua: null, results: [], timedOut: true });
    }, HOLD_MS * 2 + 45000).unref();
  });
});

if (JSON_OUT) writeFileSync(JSON_OUT, JSON.stringify(result, null, 2));

console.log(`рушій: ${result.ua ?? '(не доповів)'}\n`);

let verdict = 'НЕ ВМІЄ';
for (const r of result.results) {
  console.log(`— ${r.path}`);
  if (!r.available) { console.log(`    недоступний: ${r.reason}`); continue; }
  if (!r.worked) { console.log(`    є, але не працює: ${r.reason}`); continue; }
  console.log(`    кадрів: відправник ${r.framesSent}, отримувач ${r.framesRecv}, пар ${r.matched}`);
  console.log(`    сервер бачить шифротекст: ${r.hidden ? 'ТАК' : 'НІ'} (${r.changed}/${r.framesSent})`);
  console.log(`    кадр доїхав цілим:        ${r.intact ? 'ТАК' : 'НІ'} (${r.restored}/${r.matched})`);
  if (r.hidden && r.intact) verdict = 'ВМІЄ';
  else if (verdict !== 'ВМІЄ' && (r.hidden || r.intact)) verdict = 'ВМІЄ НАПОЛОВИНУ';
}

console.log(`\nВИРОК (для ЦЬОГО рушія, не для апарата): ${verdict}`);
if (result.timedOut) {
  console.error('ПРИЛАД НЕ ДОБІГ — вирок вище неповний');
  process.exit(1);
}
