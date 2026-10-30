/**
 * Кожне посилання з index.html існує як файл у зібраній теці — для ОБОХ збірок.
 *
 * Клас вади — «посилання є, файла немає». index.html просив
 * %BASE_URL%phantom-icon.svg, а файла не було ніде: ні в public/, ні в dist/,
 * ні в dist-phone/ (виміряно 04.09: svg у бандлі — 0). На ПК це 404 на іконку
 * вкладки невідомо з якого часу; на телефоні хостовий сторож просить у
 * перехоплювача кожен шлях з index.html і на іконку діставав index.html
 * замість файла. Винятку для іконки не робимо свідомо: саме такий виняток
 * місяць ховав порожню сторінку.
 *
 * Сторож форми (`/webmsgr/…` у phoneBundle.test.ts) дивиться, КУДИ веде
 * посилання; цей — чи Є там файл, у тому артефакті, який поїде. Дешево й
 * закриває клас — для ПК і для телефона окремо.
 *
 * Червоніти вміє (мутація 04.09): приберіть dist-phone/phantom-icon.svg —
 * падає телефонний випадок; приберіть dist/phantom-icon.svg — падає ПК.
 */
import { describe, it, expect } from 'vitest';
import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = process.cwd();

type Mode = { name: string; out: string; script: string; base: string };
const MODES: Mode[] = [
  { name: 'ПК', out: 'dist', script: 'build', base: '/' },
  { name: 'телефон', out: 'dist-phone', script: 'build:phone', base: '/webmsgr/' },
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

const newestMtime = (path: string): number =>
  !existsSync(path)
    ? 0
    : statSync(path).isDirectory()
      ? walk(path).reduce((max, f) => Math.max(max, statSync(f).mtimeMs), 0)
      : statSync(path).mtimeMs;

/**
 * Свіжий артефакт або жодного: сторож, що читає вчорашню теку, зелений за
 * побудовою. Джерела тут — не лише src/: index.html і public/ теж їдуть у
 * теку, і саме public/ ця вада стосувалась.
 */
const ensureFresh = (mode: Mode): void => {
  const out = join(ROOT, mode.out);
  const sources = Math.max(
    ...['src', 'public', 'index.html', 'vite.config.ts'].map((p) => newestMtime(join(ROOT, p))),
  );
  if (existsSync(out) && newestMtime(out) > sources) return;
  execFileSync('npm', ['run', mode.script], {
    cwd: ROOT,
    stdio: 'pipe',
    timeout: 280_000,
    env: { ...process.env, NODE_ENV: 'production' },
  });
};

describe.each(MODES)('$name: посилання з index.html існують у $out/', (mode) => {
  it('кожен href/src веде на файл, що лежить у теці', () => {
    ensureFresh(mode);
    const out = join(ROOT, mode.out);
    const html = readFileSync(join(out, 'index.html'), 'utf8');
    const refs = [...html.matchAll(/(?:src|href)="([^"]+)"/g)].map((m) => m[1]);
    expect(refs.length, 'index.html без жодного src/href — це не сторінка').toBeGreaterThan(2);

    const missing: string[] = [];
    for (const ref of refs) {
      if (/^(https?:|data:)/.test(ref)) continue;
      // Поза коренем збірки — і не файл теки, і форма, яку телефон не пустить.
      if (!ref.startsWith(mode.base)) {
        missing.push(`${ref} — поза ${mode.base}`);
        continue;
      }
      const rel = ref.slice(mode.base.length).split(/[?#]/)[0];
      const target = join(out, rel);
      if (!existsSync(target) || !statSync(target).isFile()) missing.push(`${ref} → ${mode.out}/${rel} немає`);
    }
    expect(missing, 'посилання є, файла немає').toEqual([]);

    // Іконка — саме та вада, з якої це почалось: вона мусить бути серед посилань,
    // інакше «жодного відсутнього» означало б «іконку просто перестали просити».
    expect(refs.some((r) => r.endsWith('phantom-icon.svg'))).toBe(true);
  }, 300_000);
});
