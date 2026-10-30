import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';

/**
 * `preset` означає рівно одне: рядок у таблиці PRESET_TOOLS.
 *
 * Гілка `if (preset === 'work')` НЕ ВИГЛЯДАЄ помилкою — вона виглядає як
 * турбота про користувача, і додасть її хтось із найкращих намірів. Ціна:
 * три логіки замість однієї механіки, тобто ті самі півсотні вікон,
 * розкладені по трьох теках. Саме за це власник сказав «напхано».
 *
 * Перелік файлів береться З ДИСКА, а не з памʼяті: 31.08 я перевіряв
 * досяжність модалок за ключами, які вигадав сам, і grep чесно доповів
 * «нуль» — для рядків, яких у продукті немає.
 */

const SRC = path.resolve(__dirname, '../..');
const SHARED = path.resolve(__dirname, '../../../../shared');

/** Єдині два місця, де слово `preset` сміє щось означати. */
const ALLOWED = ['stores/spaceStore.ts', 'types/space.ts'];

const walk = (dir: string): string[] => {
  const out: string[] = [];
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.name === 'node_modules' || e.name === '__tests__' || e.name === 'dist') continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) out.push(...walk(p));
    else if (/\.tsx?$/.test(e.name)) out.push(p);
  }
  return out;
};

/** Порівняння пресету зі значенням — саме те, що ми забороняємо. */
const branches = (src: string): string[] =>
  src
    .split('\n')
    .filter((l) =>
      /\bpreset\s*===?\s*['"]/.test(l) ||
      /\bcase\s+['"](work|study|business|family|notes|blank)['"]/.test(l) ||
      /\bswitch\s*\(\s*[\w.]*preset\s*\)/.test(l),
    )
    .map((l) => l.trim());

describe('пресет має рівно одне значення', () => {
  it('сторож червоніє на гілці, яку ми забороняємо', () => {
    const sample = [
      "const label = space.preset === 'work' ? 'Робочий' : 'Інший';",
      "switch (space.preset) {",
      "  case 'study':",
    ].join('\n');
    expect(branches(sample).length).toBeGreaterThanOrEqual(3);
  });

  it('сторож НЕ чіпає законного вжитку', () => {
    const ok = [
      'const PRESET_TOOLS: Record<SpacePreset, ToolId[]> = {',
      '  work: [tasks],',
      'export type SpacePreset = "blank" | "work";',
      'createSpace({ title, createdBy, preset })',
    ].join('\n');
    expect(branches(ok)).toHaveLength(0);
  });

  it('у дереві немає жодної гілки за пресетом поза таблицею', () => {
    const files = [...walk(SRC), ...(fs.existsSync(SHARED) ? walk(SHARED) : [])];
    expect(files.length).toBeGreaterThan(50);

    const offenders: string[] = [];
    for (const f of files) {
      const rel = f.replace(`${SRC}/`, '').replace(`${SHARED}/`, 'shared/');
      if (ALLOWED.some((a) => rel.endsWith(a))) continue;
      const hits = branches(fs.readFileSync(f, 'utf8'));
      if (hits.length) offenders.push(`${rel}: ${hits[0]}`);
    }

    expect(
      offenders,
      `Пресет розгалужився поза таблицею PRESET_TOOLS:\n${offenders.join('\n')}\n\n` +
        'Пресет — ПІДКАЗКА для набору інструментів, не тип простору. Робочий, ' +
        'навчальний і бізнесовий мусять лишатись однією механікою з різними ' +
        'tools[]; інакше це три логіки й ті самі вікна, розкладені по теках.',
    ).toEqual([]);
  });
});
