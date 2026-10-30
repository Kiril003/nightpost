/**
 * K1: the call-gate reasons come from the catalogue (parts/groups.ts), so English shows English.
 * The three exports keep the Ukrainian text: older tests compare `why` with them in uk.
 */
import { afterEach, describe, expect, it } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { callGate, NO_CALL_GROUP, NO_CALL_NO_PEER, NO_CALL_ROADS } from '../services/callGate';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';

const CYRILLIC = /[А-Яа-яІіЇїЄєҐґ]/;
const ok = { roads: { inbound: true }, phoneShell: false, hasPeer: true, isGroup: false };
const reasons = () => [
  callGate({ ...ok, roads: {}, phoneShell: true }).why,
  callGate({ ...ok, isGroup: true }).why,
  callGate({ ...ok, hasPeer: false }).why,
];

const setLocale = (l: 'uk' | 'en') =>
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: l } }));

afterEach(() => setLocale('uk'));

describe('call-gate words', () => {
  it('uk: each reason is the exported Ukrainian text, unchanged', () => {
    expect(reasons()).toEqual([NO_CALL_ROADS, NO_CALL_GROUP, NO_CALL_NO_PEER]);
    expect(NO_CALL_ROADS).toBe('Дзвінки ще готуються — спробуйте за хвилинку');
    expect(NO_CALL_GROUP).toBe('Дзвонити можна лише одній людині, не всій групі');
    expect(NO_CALL_NO_PEER).toBe('Цій людині поки не можна подзвонити');
  });

  it('en: each reason is English, distinct, and not the Ukrainian constant', () => {
    setLocale('en');
    const en = reasons();
    en.forEach((why) => {
      expect(why.length).toBeGreaterThan(10);
      expect(why).not.toMatch(CYRILLIC);
    });
    expect(new Set(en).size).toBe(3);
    expect(en).not.toContain(NO_CALL_GROUP);
  });

  it('callGate.ts code carries no Cyrillic literal (comments may)', () => {
    const src = fs.readFileSync(path.resolve(__dirname, '../services/callGate.ts'), 'utf8');
    const code = src
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .split('\n')
      .map((l) => l.replace(/\/\/.*$/, ''))
      .join('\n');
    expect(code).not.toMatch(CYRILLIC);
  });
});
