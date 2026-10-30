import { afterEach, describe, expect, it } from 'vitest';
import { en } from '../i18n/messages/en';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import { composeEdu, eduPreview } from '../services/edu/eduWire';
import { UNNAMED } from '../components/messenger/chatListFormat';

describe('G4: English copy', () => {
  it('no «No name» fallback anywhere in the English UI', () => {
    const bad = Object.entries(en).filter(([, v]) => /^no name$/i.test(String(v).trim()));
    expect(bad).toEqual([]);
  });
});

const setLocale = (l: 'uk' | 'en') =>
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: l } }));

describe('G4: a class card preview speaks the reader’s language', () => {
  afterEach(() => setLocale('uk'));

  it('a uk-sent class card reads in English on an English phone, without «Без імені»', () => {
    const text = composeEdu(['Клас · Без імені', 'Фізика'], { k: 'class', n: UNNAMED, s: 'Фізика', at: 1 });
    setLocale('en');
    expect(eduPreview(text)).toBe('Class · Фізика');
    const assign = composeEdu(['Завдання · Лаб 4'], { k: 'assign', id: 'a1', t: 'Лаб 4', due: null, note: '', at: 1 });
    expect(eduPreview(assign)).toBe('Assignment · Лаб 4');
  });

  it('uk stays as sent; a plain message is untouched', () => {
    const text = composeEdu(['Клас · 7-Б', 'Фізика'], { k: 'class', n: '7-Б', s: 'Фізика', at: 1 });
    expect(eduPreview(text)).toBe('Клас · 7-Б');
    expect(eduPreview('просто текст')).toBe('просто текст');
  });
});
