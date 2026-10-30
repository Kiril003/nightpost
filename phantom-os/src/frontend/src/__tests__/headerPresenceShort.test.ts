/**
 * Рядок «… тому» у шапці розмови при 390 вміщує ~19 знаків (123 px) поруч із кнопками дзвінка.
 * Довший рядок ріже одиницю часу («востаннє на звʼязку 2 …»), тож час мусить влазити повністю.
 */
import { afterEach, describe, expect, it } from 'vitest';
import { presenceLine } from '../services/livingPulse';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';

const NOW = 1_760_000_000_000;
// Кирилиця ширша за латиницю: межі заміряно в шапці при 390 (123 px).
const HEADER_CHARS_AT_390: Record<string, number> = { uk: 19, en: 19, cs: 19 };

const setLocale = (l: string) =>
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: l } }));

afterEach(() => setLocale('uk'));

const AGES_MIN = [1, 5, 59, 60, 61, 125, 23 * 60 + 59, 24 * 60, 6 * 24 * 60 + 23 * 60];

describe('шапка розмови: час «… тому» не обрізається при 390', () => {
  for (const locale of ['uk', 'en', 'cs']) {
    it(`${locale}: кожен рядок часу ≤ ${HEADER_CHARS_AT_390[locale]} знаків`, () => {
      setLocale(locale);
      for (const m of AGES_MIN) {
        const line = presenceLine(null, { avatar: null, heardAtMs: NOW - m * 60_000, lanSeenAtMs: null }, NOW);
        expect(line, `${m} хв`).toBeTruthy();
        expect(line!.length, line!).toBeLessThanOrEqual(HEADER_CHARS_AT_390[locale]);
      }
    });
  }

  it('uk: коротка форма зберігає число й одиницю', () => {
    setLocale('uk');
    expect(presenceLine(null, { avatar: null, heardAtMs: NOW - 125 * 60_000, lanSeenAtMs: null }, NOW)).toBe('2 год тому');
  });
});
