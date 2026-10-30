/**
 * Wave-locale gaps: which catalogue keys a lazy locale still lacks.
 *
 *   npx vite-node scripts/i18n-missing.ts                  # counts per locale
 *   npx vite-node scripts/i18n-missing.ts -- export <dir>  # <dir>/<locale>.json = {key: {en, uk}} to translate
 *   npx vite-node scripts/i18n-missing.ts -- merge <dir>   # folds <dir>/<locale>/*.json into messages/lazy
 *
 * `.few` keys matter only where CLDR has "few" (pl); elsewhere they're skipped.
 * Keys whose uk is gone are dropped from lazy files on merge.
 */
import { mkdirSync, readFileSync, readdirSync, writeFileSync, existsSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { en } from '../src/i18n/messages/en';
import { uk } from '../src/i18n/messages/uk';
import { LAZY_LOCALES } from '../src/i18n/locales';

const LAZY = resolve(__dirname, '../src/i18n/messages/lazy');
const FEW = new Set(['pl']);
const args = process.argv.slice(2).filter((a) => a !== '--');
const keys = Object.keys(uk) as (keyof typeof uk)[];
const read = (l: string): Record<string, string> => JSON.parse(readFileSync(join(LAZY, `${l}.json`), 'utf8'));
const wanted = (l: string) => keys.filter((k) => !k.endsWith('.few') || FEW.has(l));

if (args[0] === 'export') {
  const dir = resolve(args[1]);
  mkdirSync(dir, { recursive: true });
  for (const l of LAZY_LOCALES) {
    const have = read(l);
    const out: Record<string, { en: string; uk: string }> = {};
    for (const k of wanted(l)) if (!(k in have)) out[k] = { en: (en as Record<string, string>)[k] ?? uk[k], uk: uk[k] };
    writeFileSync(join(dir, `${l}.json`), JSON.stringify(out, null, 1) + '\n');
    console.log(l, Object.keys(out).length);
  }
} else if (args[0] === 'merge') {
  const dir = resolve(args[1]);
  for (const l of LAZY_LOCALES) {
    const sub = join(dir, l);
    if (!existsSync(sub)) continue;
    const table = read(l);
    for (const f of readdirSync(sub).filter((x) => x.endsWith('.json')).sort()) {
      Object.assign(table, JSON.parse(readFileSync(join(sub, f), 'utf8')));
    }
    const sorted: Record<string, string> = {};
    for (const k of keys) if (typeof table[k] === 'string' && table[k].trim()) sorted[k] = table[k];
    writeFileSync(join(LAZY, `${l}.json`), JSON.stringify(sorted, null, 1) + '\n');
    console.log(l, Object.keys(sorted).length);
  }
} else {
  for (const l of LAZY_LOCALES) {
    const have = read(l);
    console.log(l, wanted(l).filter((k) => !(k in have)).length, 'missing');
  }
}
