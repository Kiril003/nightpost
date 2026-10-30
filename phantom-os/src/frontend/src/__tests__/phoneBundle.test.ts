/**
 * Що саме їде в APK. Не «має їхати» — а що лежить у `dist-phone/`.
 *
 * Виміряно 03.09 на бандлі 55d946b4, який стояв в APK: 322 файли, 17,8 МБ.
 * З них 75 файлів `*.js.map` — 8,8 МБ, тобто 49 % ваги (при тому, що «.js.map
 * геть» ухвалено ще 29.08), і 57 чанків `*Modal-*.js` — 497 КБ показових
 * хабів: Академія зі студентами «Кирило»×25 і `autograderScore: 95/100`,
 * корпоративний HR, комерція, машина часу. Жодного з них міст не везе.
 *
 * Мапи — не лише вага. Усі 75 несли `sourcesContent`: 99 файлів `.tsx`
 * читабельним вихідним текстом усередині застосунку про безпеку.
 *
 * Сторож дивиться на артефакт трьома незалежними очима:
 *   1. назви: жодного `*.js.map`;
 *   2. назви: жодного `*Modal-*.js` поза білим списком;
 *   3. ВМІСТ: ні `sourcesContent`, ні `sourceMappingURL` — бо мапу можна
 *      привезти й під іншим імʼям, і вшитою в сам `.js`;
 *   4. ВМІСТ: жодного взірця з `bundlePatterns.ts` — локального фолбека при
 *      відмові вузла, вписаного в код ідентифікатора розмови, засіяної дошки
 *      рішень, імені живої людини літералом.
 *
 * Четверте око зʼявилось після виміру APK a1cecdb8: перші три були зелені,
 * бо стерегли ІМЕНА файлів, а вміст переїхав у два головні чанки. Сторож,
 * який дивиться лише на назви, ловить переїзд файлів, а не переїзд коду.
 *
 * Третє око зʼявилось після виміру: `sourcemap: 'inline'` не створює жодного
 * `.js.map` і не лишає рядка `sourcesContent` — мапа їде в `.js` рядком
 * base64. Перше око її не бачить узагалі. Тому шукаємо ще й посилання на
 * мапу: без нього мапа непридатна, тож воно є завжди — і при зовнішній, і
 * при вшитій.
 *
 * Червоніти вміє: поверніть `sourcemap: true` у phone-гілці vite.config.ts —
 * падають перше й третє; поверніть будь-який лінивий `import()` модалки з
 * імʼям `*Modal` у граф телефона — падає друге. (Підміни `phoneModalHost`
 * більше немає: 24.09 показові модалки видалено, а не підмінено.)
 */
import { describe, it, expect } from 'vitest';
import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { ALLOWED, FORBIDDEN } from './bundlePatterns';

const ROOT = process.cwd();
const OUT = join(ROOT, 'dist-phone');
const SRC = join(ROOT, 'src');

/**
 * Білий список чанків `*Modal-*.js`, яким дозволено бути в бандлі телефона.
 *
 * Порожній, і це вимір, а не лінощі: усі 57 чанків народжував один
 * `ModalHost`, і жодну з тих модалок міст телефона не обслуговує — ні даних,
 * ні дії. 24.09 `ModalHost` разом із модалками видалено з дерева взагалі. Модалки, які телефону справді потрібні (розмови, простори, топіки,
 * гілки, файли, довідник, вузол/профіль, налаштування), статичні: вони лежать
 * усередині `MessengerLayout-*.js` і окремим чанком не народжуються — саме
 * тому їх тут і немає.
 *
 * Додавати сюди можна лише з причиною в тому ж рядку — і причина мусить
 * називати, що саме з моста ця модалка показує.
 */
const ALLOWED_MODAL_CHUNKS: Array<{ file: RegExp; why: string }> = [];

/**
 * Файли, яким дозволено нести посилання на мапу. Причина — на кожному.
 */
const ALLOWED_SOURCEMAP_REFS: Array<{ file: RegExp; why: string }> = [
  {
    file: /^assets[/\\]maplibre-gl-csp-worker-[^/\\]*\.js$/,
    why:
      'готовий воркер вендора несе власний коментар //# sourceMappingURL=maplibre-gl-csp-worker.js.map; ' +
      'самої мапи в бандлі немає — це висяче посилання maplibre, а не наш вихідний текст',
  },
];

const walk = (dir: string): string[] => {
  const out: string[] = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) out.push(...walk(p));
    else out.push(p);
  }
  return out;
};

const newestMtime = (dir: string): number =>
  walk(dir).reduce((max, f) => Math.max(max, statSync(f).mtimeMs), 0);

/**
 * Свіжий артефакт або жодного.
 *
 * Сторож, що читає вчорашній `dist-phone/`, зелений за побудовою: він
 * розповідає про збірку, якої вже немає. Тож або каталог новіший за `src/`
 * і за сам конфіг, або збираємо тут же.
 */
const ensureFreshBuild = (): void => {
  const config = statSync(join(ROOT, 'vite.config.ts')).mtimeMs;
  const fresh =
    existsSync(OUT) && newestMtime(OUT) > Math.max(newestMtime(SRC), config);
  if (fresh) return;
  // Рівно та сама команда, що й у людини: одне визначення того, як
  // збирається бандл телефона, — і жодного другого.
  //
  // Тут стояв прямий `npx vite build --mode phone`, і він успадковував
  // NODE_ENV='test' від vitest. Vite тоді не вважав збірку продакшеном:
  // React ставив jsxDEV з абсолютним шляхом до кожного вихідного файла —
  // 4602 шляхи з машини власника. Сторож перевіряв артефакт, якого ніхто
  // не везе, і був зелений про чужу збірку.
  execFileSync('npm', ['run', 'build:phone'], {
    cwd: ROOT,
    stdio: 'pipe',
    timeout: 280_000,
    env: { ...process.env, NODE_ENV: 'production' },
  });
};

describe('бандл телефона: що саме лежить у dist-phone/', () => {
  it('ні мап, ні показових модалок, ні вихідного тексту всередині', () => {
    ensureFreshBuild();
    const files = walk(OUT).map((f) => relative(OUT, f));

    // Каталог справді прочитано, і це справді збірка месенджера — інакше
    // «нуль порушень» означало б «нуль файлів».
    // Файлів мало відтоді, як шрифтів 14 замість 226 (один Manrope) — тож
    // доказ «це месенджер» — сам його шматок, а не кількість.
    expect(files.length).toBeGreaterThan(20);
    expect(files.some((f) => /MessengerLayout-[\w-]+\.js$/.test(f))).toBe(true);
    expect(files.some((f) => /MessengerLayout-.*\.js$/.test(f))).toBe(true);
    expect(files.some((f) => f === 'index.html')).toBe(true);

    // 1. Мапи за назвою.
    expect(files.filter((f) => f.endsWith('.js.map'))).toEqual([]);

    // 2. Показові модалки за назвою.
    const modals = files
      .filter((f) => /Modal-[^/\\]*\.js$/.test(f))
      .filter((f) => !ALLOWED_MODAL_CHUNKS.some((a) => a.file.test(f)));
    expect(modals).toEqual([]);

    // 3. Вихідний текст за вмістом — окремо від назв.
    const bytes = walk(OUT).map((f) => ({ name: relative(OUT, f), buf: readFileSync(f) }));

    // 3a. Сам текст: мапа під будь-яким імʼям або сира.
    expect(bytes.filter((f) => f.buf.includes('sourcesContent')).map((f) => f.name)).toEqual([]);

    // 3b. Посилання на мапу: ловить і вшиту base64-мапу, якої око 1 не бачить.
    const refs = bytes
      .filter((f) => f.buf.includes('sourceMappingURL'))
      .map((f) => f.name)
      .filter((n) => !ALLOWED_SOURCEMAP_REFS.some((a) => a.file.test(n)));
    expect(refs).toEqual([]);

    // 4. Коріння адрес: усе під /webmsgr/, бо перехоплювач телефона інше
    //    не пускає. Порожня кремова сторінка на A52 була саме про це.
    const html = readFileSync(join(OUT, 'index.html'), 'utf8');
    const htmlRefs = [...html.matchAll(/(?:src|href)="([^"]+)"/g)].map((m) => m[1]);
    expect(htmlRefs.length, 'index.html без жодного src/href — це не сторінка').toBeGreaterThan(2);
    expect(htmlRefs.filter((r) => r.startsWith('/') && !r.startsWith('/webmsgr/'))).toEqual([]);
    // 4b. Ціль існує. Форма правильна, а файла немає — перехоплювач телефона на
    //     невідомий шлях віддає index.html замість файла (так було з іконкою
    //     вкладки: %BASE_URL%phantom-icon.svg без phantom-icon.svg у теці).
    //     Обидві збірки разом — у bundleLinks.test.ts; тут — те, що їде в APK.
    const ghosts = htmlRefs
      .filter((r) => r.startsWith('/webmsgr/'))
      .map((r) => r.slice('/webmsgr/'.length).split(/[?#]/)[0])
      .filter((rel) => !existsSync(join(OUT, rel)));
    expect(ghosts, 'посилання є, файла немає').toEqual([]);

    // 5. Взірці вмісту: сівба, фолбеки, імена живих людей, коріння адрес.
    const js = bytes.filter((f) => /\.(js|css|html)$/.test(f.name));
    expect(js.length, 'жодного .js — сканувати нема чого').toBeGreaterThan(1);
    const found: string[] = [];
    for (const rule of FORBIDDEN) {
      for (const f of js) {
        const hits = f.buf.toString('utf8').match(new RegExp(rule.re.source, 'g'));
        if (hits) found.push(`${f.name}: ${rule.id} ×${hits.length} — ${rule.why}`);
      }
    }
    expect(found).toEqual([]);

    // 6. ОБИДВІ ТЕМИ В АРТЕФАКТІ. Правило власника: одна палітра на весь
    //    застосунок у кожен момент, і якщо вона міняється за часом доби —
    //    міняється скрізь. Веб бере тему з моста (services/theme.ts), а день
    //    живе перекриттями під `data-theme='day'`. Тут доводиться, що вони
    //    справді доїхали в бандл і покривають КОЖЕН колір, яким він фарбує.
    const cssFiles = bytes.filter((f) => f.name.endsWith('.css'));
    expect(cssFiles.length, 'у бандлі немає жодного .css').toBeGreaterThan(0);
    const cssText = cssFiles.map((f) => f.buf.toString('utf8')).join('\n');
    expect(cssText.includes("[data-theme='day']") || cssText.includes('[data-theme=day]')).toBe(true);

    const dayCovered = new Set(
      [...cssText.matchAll(/\[data-theme=(?:'day'|day)\][^{]*?\.(?:bg|text|border|ring|fill|stroke|from|to|via)-\\\[\\#([0-9A-Fa-f]{6})\\\]/g)].map(
        (m) => m[1].toUpperCase(),
      ),
    );
    const dayUncovered = [
      ...new Set(
        [...cssText.matchAll(/\.(?:bg|text|border|ring|fill|stroke|from|to|via)-\\\[\\#([0-9A-Fa-f]{6})\\\]/g)].map((m) =>
          m[1].toUpperCase(),
        ),
      ),
    ].filter((hex) => !dayCovered.has(hex));
    expect(
      dayUncovered,
      'у бандлі є колір, для якого немає денного відповідника — удень це місце лишиться нічним',
    ).toEqual([]);

    // Білий список мусить лишатись поясненим, а не порожньою обіцянкою.
    expect(ALLOWED.every((a) => a.why.length > 40)).toBe(true);
  }, 300_000);

  /**
   * Статичний граф входу — те, що телефон качає й розбирає ДО першого кадру.
   *
   * Виміряно 01.10: index.html статично тягнув `vendor-sandpack` (955 КБ)
   * лише тому, що rollup доклеїв до нього помічник динамічного імпорту vite;
   * граф входу був 1471 КБ замість ~510. Пісочниця коду, мапа й діаграми
   * месенджеру до першого кадру не потрібні — ні байтом. А десктопні лінькі
   * маршрути (MapLayout, DialogueLayout, SettingsPanel) у збірці телефона
   * недосяжні взагалі, тож їхніх чанків там бути не має.
   *
   * Червоніти вміє: приберіть правило `vite/preload-helper` у vite.config.ts
   * — граф входу знову містить vendor-sandpack; приберіть `__PHONE_BUILD__`
   * перед `React.lazy` у App.tsx — повертаються чанки MapLayout і сусіди.
   */
  it('граф входу без пісочниці, мапи й діаграм; десктопних маршрутів у збірці немає', () => {
    ensureFreshBuild();
    const assets = join(OUT, 'assets');
    const html = readFileSync(join(OUT, 'index.html'), 'utf8');
    const entry = html.match(/src="[^"]*assets\/([^"]+\.js)"/)?.[1];
    expect(entry, 'index.html без модуля входу').toBeTruthy();
    const seen = new Set<string>();
    const visit = (f: string) => {
      if (seen.has(f)) return;
      seen.add(f);
      const src = readFileSync(join(assets, f), 'utf8');
      for (const m of src.matchAll(/(?:^|[;\n}])\s*import\s*(?:[^'"()]*?from\s*)?["']\.\/([^"']+\.js)["']/g)) visit(m[1]);
    };
    visit(entry as string);
    // Граф справді пройдено: вхід + React щонайменше.
    expect(seen.size).toBeGreaterThan(1);
    expect([...seen].some((f) => f.startsWith('vendor-react-'))).toBe(true);

    const heavy = [...seen].filter((f) => /^vendor-(sandpack|maplibre|charts|three)-/.test(f));
    expect(heavy, 'важкий вендор у статичному графі входу').toEqual([]);
    // Вміст, а не лише імена: вендор міг переїхати в чанк з іншою назвою.
    const markers: Array<[string, RegExp]> = [
      ['sandpack', /SandpackRuntime|sandpack-bundler/],
      ['maplibre', /maplibregl|maplibre-gl/],
      ['recharts', /recharts-wrapper|recharts-surface/],
    ];
    const leaked: string[] = [];
    for (const f of seen) {
      const src = readFileSync(join(assets, f), 'utf8');
      for (const [name, re] of markers) if (re.test(src)) leaked.push(`${f}: ${name}`);
    }
    expect(leaked, 'код важкого вендора у статичному графі входу').toEqual([]);

    const desk = readdirSync(assets).filter((f) => /^(MapLayout|SettingsPanel)-.*\.js$/.test(f));
    expect(desk, 'десктопний маршрут народив чанк у збірці телефона').toEqual([]);
  }, 300_000);
});
