/**
 * Що з вихідного дерева ЖИВЕ в збірці телефона — без самої збірки.
 *
 * `phoneBundle.test.ts` дивиться на готовий `dist-phone/` і тому мусить його
 * зібрати (хвилини). Тут те саме питання ставиться графу модулів: esbuild
 * складає `src/main.tsx` з `__PHONE_BUILD__ = true`, як vite у phone-режимі,
 * і метафайл каже, які файли лишили в результаті хоч байт. Це секунда, тож
 * сторож ганяється з кожним `vitest run`.
 *
 * Межа виміру. esbuild трясе дерево слабше за rollup: модуль, у якого є
 * верхньорівневий виклик (`React.forwardRef(…)`, `create(…)`), він лишає, навіть
 * коли того ніхто не кличе. Тож «живе тут» — це ВЕРХНЯ межа того, що їде в APK:
 * якщо файла немає тут, його немає і в dist-phone. Зворотне не гарантоване, і
 * тому заборонений список нижче — лише те, що справді їхало (виміряно 03.10
 * рядками в index-*.js, див. bundlePatterns.ts).
 *
 * Що стережеться:
 *   1. Десктопні ліниві маршрути (MapLayout, DialogueLayout, SettingsPanel) —
 *      у збірці телефона їх відсікає літерал `__PHONE_BUILD__` перед
 *      `React.lazy` в App.tsx. Приберіть його — повернуться сюди.
 *   2. Віконний менеджер ПК: Overlays, FloatingWindow, StandingOrdersOverlay,
 *      appsStore, useFaceDetection (~60 КБ) — монтувались у App для обох
 *      збірок, хоча телефон їх не показує ніколи.
 *   3. StateTransitionController і familiarStore — хореографія станів і
 *      «фамільяр», яких не малює жодна поверхня.
 *   4. Оболонка ПК (components/shell, components/auth) — `DeskRouter`
 *      підвантажується лише поза телефоном.
 *
 * Червоніти вміє: поверніть `<Overlays/>` в App.tsx (файл видалено — тоді
 * впаде збірка), або зніміть `__PHONE_BUILD__ ?` перед `React.lazy` чи перед
 * імпортом `DeskRouter` — і відповідні файли повернуться в граф.
 */
import { describe, it, expect } from 'vitest';
import { execFileSync } from 'node:child_process';

const ROOT = process.cwd();

/**
 * Окремий процес node, а не виклик esbuild тут: під jsdom (test-setup.ts)
 * `TextEncoder` не повертає справжній `Uint8Array`, і esbuild відмовляється
 * працювати. Опції — ті самі, що в vite phone-режимі: літерал
 * `__PHONE_BUILD__`, продакшн-гілки `import.meta.env`, псевдоніми.
 */
const GRAPH_SCRIPT = `
import { build } from 'esbuild';
import { relative, resolve } from 'node:path';
const ROOT = process.cwd();
const phone = process.argv[1] === 'phone';
const result = await build({
  entryPoints: [resolve(ROOT, 'src/main.tsx')],
  bundle: true, write: false, metafile: true, format: 'esm', platform: 'browser',
  packages: 'external', treeShaking: true, minify: true,
  define: { __PHONE_BUILD__: JSON.stringify(phone), 'import.meta.env.DEV': 'false', 'import.meta.env.PROD': 'true' },
  alias: { '@shared': resolve(ROOT, '../shared'), '@': resolve(ROOT, 'src') },
  loader: Object.fromEntries(['.css', '.svg', '.png', '.jpg', '.webp', '.woff2', '.woff', '.wasm', '.onnx'].map((e) => [e, 'empty'])),
  jsx: 'automatic', logLevel: 'silent', outdir: resolve(ROOT, 'node_modules/.cache/phone-entry-graph'),
  plugins: [{ name: 'vite-query-imports', setup(b) {
    b.onResolve({ filter: /\\?(raw|url|worker|inline)$/ }, (a) => ({ path: a.path, external: true }));
  } }],
});
const live = new Set();
for (const out of Object.values(result.metafile.outputs))
  for (const [input, info] of Object.entries(out.inputs))
    if (info.bytesInOutput > 0) live.add(relative(ROOT, resolve(ROOT, input)));
process.stdout.write(JSON.stringify([...live].sort()));
`;

function liveModules(phone: boolean): string[] {
  const out = execFileSync(process.execPath, ['--input-type=module', '-e', GRAPH_SCRIPT, phone ? 'phone' : 'desk'], {
    cwd: ROOT,
    encoding: 'utf8',
    timeout: 50_000,
    maxBuffer: 16 * 1024 * 1024,
  });
  return JSON.parse(out) as string[];
}

/** Ні байта цього в збірці телефона. */
const FORBIDDEN_IN_PHONE: Array<{ re: RegExp; why: string }> = [
  { re: /^src\/layouts\/MapLayout\.tsx$/, why: 'десктопний маршрут' },
  { re: /^src\/components\/settings\/SettingsPanel\.tsx$/, why: 'десктопний маршрут /settings/all' },
  { re: /^src\/components\/core\/(Overlays|FloatingWindow|StandingOrdersOverlay)\.tsx$/, why: 'віконний менеджер ПК' },
  { re: /^src\/stores\/(appsStore|familiarStore)\.ts$/, why: 'стор поверхні, якої телефон не має' },
  { re: /^src\/hooks\/useFaceDetection\.ts$/, why: 'камера-оверлей ПК' },
  { re: /^src\/app\/StateTransitionController\.tsx$/, why: 'хореографія станів системи' },
  { re: /^src\/components\/(shell|auth)\//, why: 'оболонка ПК: вхід, знайомство, Головна' },
  { re: /^src\/components\/engine\//, why: 'Рушій ПК: файли вузла' },
  { re: /^src\/components\/messenger\/DeskSidePane\.tsx$/, why: 'четверта колонка ПК тягне maplibre' },
];

describe('граф входу збірки телефона', () => {
  it('десктопних маршрутів, віконного менеджера й оболонки ПК у ньому немає', () => {
    const live = liveModules(true);
    // Граф справді складено: вхід, месенджер і його корінь — живі.
    expect(live).toContain('src/main.tsx');
    expect(live).toContain('src/layouts/MessengerLayout.tsx');
    expect(live).toContain('src/components/messenger/MessengerRoot.tsx');
    expect(live.length).toBeGreaterThan(100);

    const leaked = live.flatMap((f) =>
      FORBIDDEN_IN_PHONE.filter((r) => r.re.test(f)).map((r) => `${f} — ${r.why}`),
    );
    expect(leaked).toEqual([]);
  }, 60_000);

  it('а в збірці ПК ті самі двері живі — вимір не сліпий', () => {
    // Без цієї половини «нічого не знайдено» могло б означати зламаний обхід.
    const live = liveModules(false);
    for (const f of [
      'src/layouts/MapLayout.tsx',
      'src/components/settings/SettingsPanel.tsx',
      'src/components/auth/LoginScreen.tsx',
      'src/components/shell/DeskShell.tsx',
      'src/components/engine/files/EngineFiles.tsx',
      'src/components/messenger/DeskSidePane.tsx',
    ]) {
      expect(live, f).toContain(f);
    }
  }, 60_000);
});
