/**
 * Присутність не пишеться літералом.
 *
 * «В мережі» — це вимір: його робить вузол або міст і привозить полем
 * (`is_online`, `presence`). Усе, що в коді написано як `isOnline: true`,
 * виміром не є за побудовою — це зелена крапка, намальована наперед. Саме так
 * і жили вигадані люди: `chat_dm_<імʼя>` з відмови довідника, троє учасників
 * нової групи, троє в ActionHub і кожен, кому щойно прийшов лист.
 *
 * Тому правило просте й не має винятків: у `src/` (поза тестами) немає
 * жодного літерала true поруч із полем присутності. Присвоєння з даних
 * (`isOnline: u.is_online`) сторож не чіпає — воно і є правильною формою.
 *
 * Червоніти вміє: допишіть у будь-який компонент `isOnline: true` — падає.
 */
import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

/** vitest ходить із кореня фронтенду — звідти й починаємо обхід. */
const ROOT = join(process.cwd(), 'src');

/** Літерал true у полі присутності — у будь-якому з написань, які тут бували. */
const FORBIDDEN = /\b(isOnline|is_online|online)\s*:\s*(true|!0)\b/;

const walk = (dir: string): string[] => {
  const out: string[] = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) {
      if (name === '__tests__' || name === 'node_modules') continue;
      out.push(...walk(p));
      continue;
    }
    if (!/\.(ts|tsx)$/.test(name)) continue;
    if (/\.(test|spec)\.tsx?$/.test(name)) continue;
    out.push(p);
  }
  return out;
};

describe('жодної намальованої присутності в src/', () => {
  it('поле «в мережі» ніде не присвоєне літералом', () => {
    const hits: string[] = [];
    const files = walk(ROOT);
    for (const file of files) {
      readFileSync(file, 'utf8')
        .split('\n')
        .forEach((line, i) => {
          // Коментар — не код: у ньому і описано, як саме тут малювали крапку.
          const t = line.trim();
          if (t.startsWith('//') || t.startsWith('*') || t.startsWith('/*')) return;
          const code = line.replace(/\/\/.*$/, '');
          if (FORBIDDEN.test(code)) hits.push(`${relative(ROOT, file)}:${i + 1}: ${t}`);
        });
    }
    // Файли справді прочитано — інакше «нуль порушень» означав би нуль файлів.
    expect(files.length).toBeGreaterThan(200);
    expect(hits).toEqual([]);
  });
});
