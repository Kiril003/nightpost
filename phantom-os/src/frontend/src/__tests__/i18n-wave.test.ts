/**
 * Wave 1 languages (owner 29.10: «додай більше мов»): es, pt-BR, de, fr, pl,
 * tr, it, id. They load lazily, fall back per key to English (never straight
 * to Ukrainian), follow the device when nothing is chosen, pick plural forms
 * by CLDR, keep placeholders, avoid banned words and stay short enough for a
 * 390 px screen.
 */
import { readFileSync, readdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import {
  FALLBACK_CHAIN,
  LAZY_LOCALES,
  LOCALES,
  loadLocale,
  matchLocale,
  systemLocale,
  translate,
} from '../i18n';
import { en } from '../i18n/messages/en';
import { uk } from '../i18n/messages/uk';
import { pickPlural, pluralKey } from '../components/chat/tNow';
import { applyBootstrapLanguage } from '../services/settingsBootstrap';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import { FORBIDDEN } from './bundlePatterns';

const LAZY_DIR = resolve(__dirname, '../i18n/messages/lazy');
const table = (l: string): Record<string, string> =>
  JSON.parse(readFileSync(join(LAZY_DIR, `${l}.json`), 'utf8')) as Record<string, string>;
const placeholders = (s: string | undefined) => (s?.match(/\{\w+\}/g) ?? []).sort().join(',');

afterEach(() => {
  localStorage.clear();
  useSettingsStore.setState({ values: {} });
});

describe('wave 1 registry', () => {
  it('lists the eight wave locales after uk/en/cs, each with a JSON catalogue', () => {
    expect(LOCALES).toEqual(['uk', 'en', 'cs', 'es', 'pt-BR', 'de', 'fr', 'pl', 'tr', 'it', 'id']);
    const files = readdirSync(LAZY_DIR).filter((f) => f.endsWith('.json')).map((f) => f.slice(0, -5)).sort();
    expect(files).toEqual([...LAZY_LOCALES].sort());
    for (const l of LAZY_LOCALES) expect(FALLBACK_CHAIN[l]).toEqual(['en', 'uk']);
  });

  it('maps device tags onto ours', () => {
    expect(matchLocale('es-MX')).toBe('es');
    expect(matchLocale('pt-PT')).toBe('pt-BR');
    expect(matchLocale('pt')).toBe('pt-BR');
    expect(matchLocale('in-ID')).toBe('id');
    expect(matchLocale('de_AT')).toBe('de');
    expect(matchLocale('uk-UA')).toBe('uk');
    expect(matchLocale('ru-RU')).toBeNull();
    expect(systemLocale(['ru-RU', 'de-AT', 'en-US'])).toBe('de');
    expect(systemLocale(['ru-RU'])).toBe('en');
    expect(systemLocale([])).toBe('en');
  });
});

describe('lazy catalogues', () => {
  it('a loaded wave locale answers in its own words, and a missing key reads English, not Ukrainian', async () => {
    await loadLocale('es');
    const es = table('es');
    const own = Object.keys(es)[0] as keyof typeof uk | undefined;
    if (own) expect(translate(own, 'es')).toBe(es[own]);
    const missing = (Object.keys(en) as (keyof typeof uk)[]).find((k) => !(k in es) && en[k]);
    if (missing) expect(translate(missing, 'es')).toBe(en[missing]);
  });
});

describe('no Ukrainian on a de or es phone', () => {
  it('every key reads the wave language or English; Cyrillic only where English itself has it', async () => {
    for (const l of ['de', 'es'] as const) {
      await loadLocale(l);
      const leaks = (Object.keys(uk) as (keyof typeof uk)[]).filter(
        (k) => /[Ѐ-ӿ]/.test(translate(k, l)) && !/[Ѐ-ӿ]/.test((en as Record<string, string>)[k] ?? ''),
      );
      expect(leaks.slice(0, 20), `${l}: ${leaks.length} keys fall to Ukrainian`).toEqual([]);
    }
  });
});

describe('plural forms by CLDR', () => {
  it('folds Intl categories onto one / few / many', () => {
    expect(pluralKey('pl', 1, 'x')).toBe('x.one');
    expect(pluralKey('pl', 3, 'x')).toBe('x.few');
    expect(pluralKey('pl', 5, 'x')).toBe('x.many');
    expect(pluralKey('pl', 22, 'x')).toBe('x.few');
    expect(pluralKey('uk', 21, 'x')).toBe('x.one');
    expect(pluralKey('uk', 11, 'x')).toBe('x.many');
    expect(pluralKey('cs', 3, 'x')).toBe('x.few');
    expect(pluralKey('cs', 7, 'x')).toBe('x.many');
    expect(pluralKey('es', 1, 'x')).toBe('x.one');
    expect(pluralKey('es', 2, 'x')).toBe('x.many');
    expect(pluralKey('fr', 0, 'x')).toBe('x.one');
    expect(pluralKey('pt-BR', 0, 'x')).toBe('x.one');
    expect(pluralKey('de', 0, 'x')).toBe('x.many');
    expect(pluralKey('tr', 2, 'x')).toBe('x.many');
    expect(pluralKey('id', 1, 'x')).toBe('x.many');
    expect(pluralKey('en', 1, 'x')).toBe('x.one');
    expect(pickPlural('pl', 4, 'a', 'b', 'c')).toBe('b');
  });
});

describe('device language when nothing is chosen', () => {
  const setDevice = (tags: string[]) =>
    Object.defineProperty(navigator, 'languages', { configurable: true, get: () => tags });

  afterEach(() => setDevice(['uk-UA', 'uk']));

  it('a German phone with no choice opens in German', () => {
    setDevice(['de-DE', 'en-US']);
    expect(applyBootstrapLanguage()).toBe('de');
    expect(useSettingsStore.getState().values[LANGUAGE_SETTING_KEY]).toBe('de');
    expect(document.documentElement.getAttribute('lang')).toBe('de');
  });

  it('a phone in a language we lack opens in English', () => {
    setDevice(['ru-RU']);
    expect(applyBootstrapLanguage()).toBe('en');
  });

  it('a saved choice beats the device', () => {
    setDevice(['de-DE']);
    localStorage.setItem('phantom_language', 'cs');
    expect(applyBootstrapLanguage()).toBe('cs');
  });
});

describe('wave catalogues are sound', () => {
  const BANNED =
    /\blight\b|blackout|darkness|bluetooth|\bmesh\b|jamm|air raid|apag[oó]n|oscuridad|escurid[aã]o|stromausfall|dunkelheit|obscurit[eé]|ciemno[sś]|karart[ıi]|oscurit[àa]|kegelapan|pemadaman/i;
  const NAMES = FORBIDDEN.find((f) => f.id === 'person-name-literal')!.re;
  const CIRCLE = /c[íi]rculos?|c[íi]rculo|\bkreise?n?\b|\bcercles?\b|\bkr[ąę]g|kręgi|çember|\bcerchi[ae]?\b|lingkaran/i;

  for (const l of LAZY_LOCALES) {
    it(`${l}: only known keys, same placeholders, no banned words, fits 390 px`, () => {
      const t = table(l);
      const problems: string[] = [];
      for (const [key, value] of Object.entries(t)) {
        const src = (en as Record<string, string | undefined>)[key] ?? (uk as Record<string, string>)[key];
        if (src === undefined) {
          problems.push(`${key}: no such key`);
          continue;
        }
        if (typeof value !== 'string' || !value.trim()) problems.push(`${key}: empty`);
        if (/[Ѐ-ӿ]/.test(value)) problems.push(`${key}: Cyrillic`);
        if (placeholders(value) !== placeholders(src)) problems.push(`${key}: placeholders ${placeholders(value)} ≠ ${placeholders(src)}`);
        const clean = value.replace(/\{[^}]*\}/g, '');
        const bad = BANNED.exec(clean) ?? (key.startsWith('people.') ? null : CIRCLE.exec(clean));
        if (bad) problems.push(`${key}: banned «${bad[0]}»`);
        const name = new RegExp(NAMES.source, NAMES.flags.replace('g', '')).exec(clean);
        if (name) problems.push(`${key}: the phone bundle guard reads «${name[0]}» as a person's name`);
        const ref = src.replace(/\{[^}]*\}/g, 'xxxx');
        const mine = value.replace(/\{[^}]*\}/g, 'xxxx');
        if (ref.length <= 24 && mine.length > Math.max(Math.ceil(ref.length * 1.8), ref.length + 12)) {
          problems.push(`${key}: too long for a label (${mine.length} vs ${ref.length}) «${value}»`);
        }
      }
      expect(problems).toEqual([]);
    });
  }

  it('reports missing keys per wave locale (soft: the lane closes gaps once per train)', () => {
    const all = Object.keys(uk);
    const gaps = Object.fromEntries(LAZY_LOCALES.map((l) => [l, all.filter((k) => !(k in table(l))).length]));
    console.info(`[i18n] wave keys missing (of ${all.length}):`, JSON.stringify(gaps));
    expect(Object.keys(gaps)).toHaveLength(LAZY_LOCALES.length);
  });
});
