import { test, expect } from '@playwright/test';
import { ALLOWED, FORBIDDEN } from '../src/__tests__/bundlePatterns';

/**
 * Перший тест phone-проєкту: доводить, що поданий бандл — саме телефонний.
 *
 * До цього `vite preview` подавав `dist/` усім, і телефонні спеки міряли
 * десктопну збірку — ту, якої в APK немає: з мапами й 57 показовими
 * модалками. Зелене там нічого не казало про APK.
 *
 * Сторож дивиться на те, що прийшло дротом, і розрізняє бандли за чотирма
 * виміряними ознаками (числа зняті 03.09 на обох збірках):
 *
 *   ознака                                    dist/     dist-phone/
 *   sourceMappingURL у index-*.js                 1               0
 *   sourceMappingURL у MessengerLayout-*.js       1               0
 *   посилання Modal-<хеш>.js у MessengerLayout   57               0
 *   тіло <js>.map                        {"version":3   заглушка SPA
 *
 * Третя ознака — головна: показові чанки лінькі, тобто мережа їх не просить,
 * доки людина не відкриє модалку. А ось ІМЕНА всіх 57 лежать у мапі
 * прелоаду всередині `MessengerLayout-*.js` — там їх видно завжди.
 *
 * Червоніти вміє: підставте `dist/` у phone-проєкт — падає на першій же
 * ознаці й називає файл.
 *
 * Друга половина сторожа — ВМІСТ поданих `.js` за взірцями з
 * `bundlePatterns.ts`: локальний фолбек при відмові вузла, вписаний у код
 * ідентифікатор розмови, засіяна дошка рішень, імʼя живої людини літералом,
 * дев-збірка з абсолютними шляхами. Перевірка за іменами файлів цього не
 * бачила: вміст переїхав у два головні чанки, і сторож лишався зеленим.
 */

const BASE = process.env.PHANTOM_WEB_BASE ?? '/';

/** Чанк показової модалки: імʼя + хеш vite. */
const MODAL_CHUNK = /Modal-[A-Za-z0-9_-]{8}\.js/;

/**
 * Готовий воркер maplibre несе власний коментар
 * `//# sourceMappingURL=maplibre-gl-csp-worker.js.map`, а самої мапи в
 * бандлі немає — це висяче посилання вендора, не наш вихідний текст.
 */
const VENDOR_MAP_REF = /maplibre-gl-csp-worker-/;

const short = (url: string) => url.replace(/^https?:\/\/[^/]+/, '');

test('поданий бандл — телефонний: без мап, без вихідного тексту, без показових чанків', async ({
  page,
  request,
}) => {
  const loaded = new Set<string>();
  const modalRequests: string[] = [];
  page.on('request', (r) => {
    const url = r.url().split('?')[0];
    if (url.endsWith('.js')) loaded.add(url);
    if (MODAL_CHUNK.test(url)) modalRequests.push(short(url));
  });

  await page.goto(BASE, { waitUntil: 'load' });
  await page.waitForTimeout(2500);

  const js = [...loaded];
  // Спершу — що ми взагалі щось міряли: інакше «нуль порушень» означало б
  // «нуль відповідей», і сторож був би зеленим на порожньому сервері.
  expect(js.length, 'жодного .js не завантажено — це не наш застосунок').toBeGreaterThan(1);
  expect(
    js.some((u) => /MessengerLayout-[A-Za-z0-9_-]+\.js$/.test(u)),
    'не бачу MessengerLayout — подано не месенджер',
  ).toBe(true);

  // 1. Мережа не просила показового чанка.
  expect(modalRequests, 'мережа тягнула показову модалку').toEqual([]);

  // 1b. До першого кадру месенджера — жодного важкого вендора. 01.10 index.html
  //     статично тягнув vendor-sandpack (955 КБ) через помічник динамічного
  //     імпорту, який rollup доклеїв до пісочниці (див. vite.config.ts).
  //     Пісочниця, мапа й діаграми мусять качатись лише за власним дотиком.
  const heavy = js.map(short).filter((u) => /vendor-(sandpack|maplibre|charts|three)-/.test(u));
  expect(heavy, 'на старті месенджера мережа тягнула важкий вендор').toEqual([]);

  const sins: string[] = [];
  for (const url of js) {
    const body = await (await request.get(url)).text();
    if (body.includes('sourcesContent')) sins.push(`${short(url)}: вихідний текст (sourcesContent)`);
    if (body.includes('sourceMappingURL') && !VENDOR_MAP_REF.test(url)) {
      sins.push(`${short(url)}: посилання на мапу (sourceMappingURL)`);
    }
    // 2. Імена показових чанків у мапі прелоаду — видно без відкривання модалки.
    const names = body.match(new RegExp(MODAL_CHUNK, 'g'));
    if (names) {
      sins.push(`${short(url)}: посилання на ${new Set(names).size} показових чанків`);
    }
    // 3. Мапа поруч. `.ok()` тут нічого не значить: `vite preview` віддає на
    //    невідомий шлях заглушку SPA з кодом 200, тож питаємо не статус, а вміст.
    const map = await request.get(`${url}.map`);
    const head = (await map.text()).slice(0, 400);
    if (head.includes('"mappings"') || head.includes('"sourcesContent"')) {
      sins.push(`${short(url)}.map: справжня мапа лежить поруч`);
    }
  }

  expect(sins, 'схоже, у phone-проєкт подали десктопний dist/').toEqual([]);

  // ── Коріння адрес: усе під /webmsgr/ ───────────────────────────────────
  // Перехоплювач телефона пускає лише https://phantom.local/webmsgr/**;
  // усе інше дістає blocked() із text/plain, і WebView відмовляє модулю.
  const html = await (await request.get(new URL(BASE, page.url()).toString())).text();
  const refs = [...html.matchAll(/(?:src|href)="([^"]+)"/g)].map((m) => m[1]);
  expect(refs.length, 'index.html без жодного src/href — це не сторінка').toBeGreaterThan(2);
  expect(
    refs.filter((r) => r.startsWith('/') && !r.startsWith('/webmsgr/')),
    'шлях від кореня: у APK він упреться в blocked() і сторінка буде порожня',
  ).toEqual([]);

  // ── Ціль існує на дроті ────────────────────────────────────────────────
  // Форма правильна — ще не файл. На невідомий шлях vite preview, як і
  // перехоплювач APK, віддає index.html із кодом 200, тож питаємо не статус,
  // а чи прийшов НЕ index.html. Так було з іконкою вкладки: посилання є,
  // файла немає — і хостовий сторож діставав сторінку замість svg.
  const ghosts: string[] = [];
  for (const r of refs.filter((x) => x.startsWith('/'))) {
    const body = await (await request.get(new URL(r, page.url()).toString())).text();
    if (/^\s*<!doctype html/i.test(body)) ghosts.push(r);
  }
  expect(ghosts, 'посилання є, файла немає — замість нього прийшов index.html').toEqual([]);

  // ── Вміст: взірці, спільні з `phoneBundle.test.ts` ──────────────────────
  const seeded: string[] = [];
  for (const url of js) {
    const body = await (await request.get(url)).text();
    for (const rule of FORBIDDEN) {
      const hits = body.match(new RegExp(rule.re.source, 'g'));
      if (hits) seeded.push(`${short(url)}: ${rule.id} ×${hits.length} — ${rule.why}`);
    }
  }
  expect(seeded, 'у поданому бандлі лишилась сівба або фолбек').toEqual([]);

  // Білий список мусить лишатись поясненим, а не порожньою обіцянкою.
  expect(ALLOWED.every((a) => a.why.length > 40)).toBe(true);
});
