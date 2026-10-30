import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  ACCENT_BLOCK_END,
  ACCENT_BLOCK_START,
  DAY_BLOCK_END,
  DAY_BLOCK_START,
  accentCss,
  dayThemeCss,
  usedColourUtilities,
} from '../src/styles/dayTheme.ts';

const file = join(import.meta.dirname, '..', 'src', 'styles', 'messenger.css');

/** Один скан дерева на обидва блоки: розійтись вони не можуть. */
const used = usedColourUtilities();

/** Вставляє блок на місце старого, або дописує в кінець, якщо його ще нема. */
const replace = (css: string, start: string, end: string, block: string): string => {
  const at = css.indexOf(start);
  if (at < 0) return `${css.trimEnd()}\n\n${block}\n`;
  return css.slice(0, at) + block + css.slice(css.indexOf(end, at) + end.length);
};

let css = readFileSync(file, 'utf8');
const day = dayThemeCss(used);
const accent = accentCss(used);
css = replace(css, DAY_BLOCK_START, DAY_BLOCK_END, day);
css = replace(css, ACCENT_BLOCK_START, ACCENT_BLOCK_END, accent);
writeFileSync(file, css);

console.log(`день: ${day.split('\n').length} рядків, акцент: ${accent.split('\n').length} рядків`);
