/**
 * K1: presence as a code. `presenceCode` follows the same order as `presenceLine` and never
 * reads a translated string, so English and Ukrainian decide "near" the same way.
 */
import { afterEach, describe, expect, it } from 'vitest';
import { presenceCode, presenceLine, type Face, type PulsePerson } from '../services/livingPulse';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';

const NOW = 1_760_000_000_000;

const setLocale = (l: 'uk' | 'en') =>
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: l } }));

afterEach(() => setLocale('uk'));

const person = (near: PulsePerson['near']): PulsePerson => ({ peerId: 'c1', name: 'O', near, precision: null, atMs: NOW });
const face = (lanAgoMs: number | null, heardAgoMs: number | null): Face => ({
  avatar: null,
  lanSeenAtMs: lanAgoMs === null ? null : NOW - lanAgoMs,
  heardAtMs: heardAgoMs === null ? null : NOW - heardAgoMs,
});

const table: Array<[string, PulsePerson | null, Face | null, ReturnType<typeof presenceCode>]> = [
  ['shares, same place', person('same'), null, 'near'],
  ['shares, close by', person('near'), null, 'near'],
  ['shares, far', person('far'), null, 'sharing'],
  ['shares, distance unknown', person(null), null, 'sharing'],
  ['shares wins over an old lan sighting', person('far'), face(10_000, null), 'sharing'],
  ['lan 10 s ago', null, face(10_000, null), 'near'],
  ['lan 100 s ago, heard 5 min ago', null, face(100_000, 5 * 60_000), 'heard'],
  ['heard 30 s ago', null, face(null, 30_000), 'heard'],
  ['heard 8 days ago', null, face(null, 8 * 86_400_000), null],
  ['heard in the future', null, face(null, -60_000), null],
  ['nothing', null, face(null, null), null],
  ['no face at all', null, null, null],
];

describe('presenceCode', () => {
  for (const locale of ['uk', 'en'] as const) {
    for (const [name, p, f, want] of table) {
      it(`${locale}: ${name} -> ${want}`, () => {
        setLocale(locale);
        expect(presenceCode(p, f, NOW)).toBe(want);
      });
    }
  }

  it('a code exists exactly when presenceLine has words', () => {
    for (const [, p, f] of table) {
      expect(presenceCode(p, f, NOW) !== null).toBe(presenceLine(p, f, NOW) !== null);
    }
  });
});
