/**
 * Воркер maplibre: хто будує мапу, той і оголошує воркер.
 *
 * ВАДА, заради якої цей тест існує. `lib/maplibreWorker.ts` імпортувався у
 * `main.tsx` — у вході застосунку. Щоб сказати maplibre один рядок
 * (`setWorkerUrl`), модуль статично тягне ВЕСЬ `maplibre-gl`: 712 КБ. Вхід —
 * це граф першого кадру, тож телефон качав і розбирав рушій мап до першої
 * бульбашки, хоч месенджер мап не малює.
 *
 * Рядок знято, оголошення переїхало до тих, хто справді робить
 * `new maplibregl.Map(`. Небезпека цього переїзду рівно одна і вона мовчазна:
 * НОВИЙ модуль збудує мапу, забуде імпорт — і CSP-збірка maplibre відмовиться
 * стартувати воркер уже на пристрої, а не на воротах.
 *
 * Тому звіряємо ДВА оголошення одного контракту: список тих, хто будує мапу,
 * і список тих, хто оголосив воркер. Вони мусять збігатись.
 */
import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';

const SRC = path.resolve(__dirname, '..');

/** Усі .ts/.tsx під src/, окрім самих тестів. */
const sources = (dir: string, acc: string[] = []): string[] => {
  for (const name of readdirSync(dir)) {
    const full = path.join(dir, name);
    if (statSync(full).isDirectory()) {
      if (name === '__tests__' || name === 'node_modules') continue;
      sources(full, acc);
    } else if (/\.tsx?$/.test(name)) {
      acc.push(full);
    }
  }
  return acc;
};

const files = sources(SRC);
const rel = (f: string) => path.relative(SRC, f);

/**
 * Коментарі геть ПЕРЕД звіркою.
 *
 * Перша редакція цього тесту почервоніла на самій собі: `mapTokens.ts` і
 * `maplibreWorker.ts` згадують `new maplibregl.Map()` у поясненнях, а
 * коментар у `main.tsx` цитує знятий рядок імпорту дослівно. Сторож, який
 * читає коментарі, судить про текст, а не про код.
 */
const code = (file: string): string =>
  readFileSync(file, 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1');

/** `new maplibregl.Map(` — саме побудова мапи, не маркер і не попап. */
const BUILDS_MAP = /new\s+maplibregl\.Map\s*\(/;
const DECLARES_WORKER = /import\s+['"][^'"]*lib\/maplibreWorker['"]/;

describe('воркер maplibre оголошує той, хто будує мапу', () => {
  it('кожен будівник мапи імпортує lib/maplibreWorker', () => {
    const builders = files.filter((f) => BUILDS_MAP.test(code(f)));

    // Якщо тут нуль — регулярка розійшлася з кодом, і тест став зеленим,
    // бо не має що перевіряти. Це та сама вада, що він ловить.
    expect(builders.length).toBeGreaterThan(0);

    const missing = builders
      .filter((f) => !DECLARES_WORKER.test(code(f)))
      .map(rel);

    expect(missing, `будують мапу, але не оголосили воркер: ${missing.join(', ')}`).toEqual([]);
  });

  it('вхід застосунку НЕ тягне maplibre у перший кадр', () => {
    const main = code(path.join(SRC, 'main.tsx'));
    // Коментар про вилучення в main.tsx згадує модуль, тож дивимось саме на
    // рядок імпорту, а не на будь-яку згадку імені.
    expect(DECLARES_WORKER.test(main)).toBe(false);
    expect(/import\s+[^'"\n]*from\s+['"]maplibre-gl['"]/.test(main)).toBe(false);
  });
});
