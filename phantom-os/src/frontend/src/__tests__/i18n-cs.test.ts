/**
 * Czech is a promise that every catalogue string exists in Czech. A missing
 * key degrades to English and then Ukrainian — readable, but a Czech phone
 * would show a foreign sentence in the middle of a Czech screen — so the key
 * sets, the `{name}` placeholders and the plural families are pinned here, per
 * lane part and for the whole catalogue.
 */
import { afterEach, describe, expect, it } from 'vitest';
import { FALLBACK_CHAIN, LOCALES, isLocale, translate } from '../i18n';
import { cs } from '../i18n/messages/cs';
import { en } from '../i18n/messages/en';
import { uk, type MessageKey } from '../i18n/messages/uk';
import * as l1 from '../i18n/messages/parts/l1';
import * as l2 from '../i18n/messages/parts/l2';
import * as l3 from '../i18n/messages/parts/l3';
import * as l4 from '../i18n/messages/parts/l4';
import * as l5 from '../i18n/messages/parts/l5';
import * as look from '../i18n/messages/parts/look';
import { cs as csL1 } from '../i18n/messages/parts/cs/l1';
import { cs as csL2 } from '../i18n/messages/parts/cs/l2';
import { cs as csL3 } from '../i18n/messages/parts/cs/l3';
import { cs as csL4 } from '../i18n/messages/parts/cs/l4';
import { cs as csL5 } from '../i18n/messages/parts/cs/l5';
import { cs as csLook } from '../i18n/messages/parts/cs/look';
import { pluralKey } from '../components/chat/tNow';
import { pluralUa } from '../utils/format';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';

type Dict = Record<string, string | undefined>;
const dict = (o: unknown) => o as Dict;

const PARTS: Array<[string, Dict, Dict]> = [
  ['l1', dict(l1.uk), dict(csL1)],
  ['l2', dict(l2.uk), dict(csL2)],
  ['l3', dict(l3.uk), dict(csL3)],
  ['l4', dict(l4.uk), dict(csL4)],
  ['l5', dict(l5.uk), dict(csL5)],
  ['look', dict(look.uk), dict(csLook)],
];

const CYRILLIC = /[А-Яа-яІіЇїЄєҐґ]/;
const placeholders = (s: string | undefined) => (s?.match(/\{\w+\}/g) ?? []).sort().join(',');

const setLocale = (l: 'uk' | 'en' | 'cs') =>
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: l } }));

afterEach(() => setLocale('uk'));

describe('Czech locale registry', () => {
  it('is registered next to uk and en and chains cs → en → uk', () => {
    expect(LOCALES.slice(0, 3)).toEqual(['uk', 'en', 'cs']);
    expect(isLocale('cs')).toBe(true);
    expect(isLocale('cs-CZ')).toBe(false);
    expect(FALLBACK_CHAIN.cs).toEqual(['en', 'uk']);
    expect(FALLBACK_CHAIN.en).toEqual(['uk']);
    expect(FALLBACK_CHAIN.uk).toEqual([]);
  });

  it('answers in Czech, not Ukrainian or English', () => {
    expect(translate('chat.sessions.title', 'cs')).not.toBe(translate('chat.sessions.title', 'uk'));
    expect(translate('chat.sessions.title', 'cs')).not.toBe(translate('chat.sessions.title', 'en'));
    expect(translate('chat.sessions.title', 'cs')).toBe(cs['chat.sessions.title']);
  });

  it('a key missing from cs shows English first, then Ukrainian, then the key', () => {
    const key = 'chat.thinking' as MessageKey;
    const c = cs as Dict;
    const e = en as Dict;
    const saved = { cs: c[key], en: e[key] };
    try {
      delete c[key];
      expect(translate(key, 'cs')).toBe(saved.en);
      delete e[key];
      expect(translate(key, 'cs')).toBe(uk[key]);
      expect(translate('no.such.key' as MessageKey, 'cs')).toBe('no.such.key');
    } finally {
      if (saved.cs === undefined) delete c[key];
      else c[key] = saved.cs;
      if (saved.en === undefined) delete e[key];
      else e[key] = saved.en;
    }
  });

  it('Czech counts 1 · 2-4 · the rest, not the Ukrainian way', () => {
    const form = (n: number) => pluralKey('cs', n, 'words.x').split('.').pop();
    expect([0, 1, 2, 3, 4, 5, 11, 12, 14, 21, 22, 24, 100, 101].map(form)).toEqual(
      ['many', 'one', 'few', 'few', 'few', 'many', 'many', 'many', 'many', 'many', 'many', 'many', 'many', 'many'],
    );
    expect(pluralKey('uk', 21, 'words.x')).toBe('words.x.one');
    expect(pluralKey('en', 2, 'words.x')).toBe('words.x.many');
  });
});

describe('words that count', () => {
  it('pluralUa follows the Czech count under cs and the Ukrainian one otherwise', () => {
    const w = (n: number) => pluralUa(n, 'one', 'few', 'many');
    setLocale('cs');
    expect([1, 2, 4, 5, 21, 22, 101].map(w)).toEqual(['one', 'few', 'few', 'many', 'many', 'many', 'many']);
    setLocale('uk');
    expect([1, 2, 4, 5, 21, 22, 101].map(w)).toEqual(['one', 'few', 'few', 'many', 'one', 'few', 'one']);
  });
});

describe('Czech catalogue parity', () => {
  it('every lane part has exactly the keys of its Ukrainian source', () => {
    for (const [name, source, target] of PARTS) {
      const missing = Object.keys(source).filter((k) => !(k in target));
      const orphan = Object.keys(target).filter((k) => !(k in source));
      expect({ part: name, missing: missing.slice(0, 20), count: missing.length }).toEqual({ part: name, missing: [], count: 0 });
      expect({ part: name, orphan }).toEqual({ part: name, orphan: [] });
    }
  });

  it('cs has every key of en, and en has nothing cs lacks', () => {
    const csKeys = new Set(Object.keys(cs));
    const missing = Object.keys(en).filter((k) => !csKeys.has(k));
    expect(missing.slice(0, 20)).toEqual([]);
    expect(missing.length).toBe(0);
    expect(Object.keys(cs).filter((k) => !(k in uk))).toEqual([]);
    expect(Object.keys(cs).length).toBe(Object.keys(uk).length);
  });

  it('placeholders are identical to the English ones, key by key', () => {
    const bad = Object.keys(en).filter((k) => placeholders(dict(en)[k]) !== placeholders(dict(cs)[k]));
    expect(bad.slice(0, 20)).toEqual([]);
    expect(bad.length).toBe(0);
  });

  it('no value is empty and none carries Cyrillic', () => {
    const empty = Object.entries(cs).filter(([, v]) => !v || !v.trim()).map(([k]) => k);
    expect(empty).toEqual([]);
    const leaked = Object.entries(cs).filter(([, v]) => CYRILLIC.test(v ?? '')).map(([k]) => k);
    expect(leaked).toEqual([]);
  });

  it('every plural family carries the three suffixes Czech counts with', () => {
    const bases = new Set(
      Object.keys(uk)
        .filter((k) => /\.(one|few|many)$/.test(k))
        .map((k) => k.replace(/\.(one|few|many)$/, '')),
    );
    expect(bases.size).toBeGreaterThanOrEqual(15);
    const thin: string[] = [];
    for (const base of bases) {
      for (const f of ['one', 'few', 'many']) {
        if (!dict(cs)[`${base}.${f}`]) thin.push(`${base}.${f}`);
      }
    }
    expect(thin).toEqual([]);
  });
});
