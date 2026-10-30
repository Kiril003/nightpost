import { afterEach, describe, expect, it } from 'vitest';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import { uk, en } from '../i18n/messages/parts/l4';
import { tNow, tPlural } from '../components/chat/tNow';
import { checklistStatus } from '../components/messenger/cards/cardModel';
import { ratingWord } from '../components/messenger/meetup/words';
import { humanMinutes, phantomLine } from '../components/shell/presence';
import { wakingLine } from '../components/shell/DeskWaking';
import { REACTION_ROAD } from '../components/messenger/feed/feedParts';

const setLocale = (l: 'uk' | 'en') =>
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: l } }));

afterEach(() => setLocale('uk'));

describe('lane l4 catalogue', () => {
  it('every Ukrainian key has an English value', () => {
    const missing = Object.keys(uk).filter((k) => !(k in en));
    expect(missing).toEqual([]);
  });

  it('Ukrainian stays the default and keeps the original wording', () => {
    expect(wakingLine(1000)).toBe('Прокидаюсь…');
    expect(checklistStatus({ id: 'a', title: '', items: [] } as never)).toBe('Поки порожньо');
    expect(REACTION_ROAD.sending.say).toBe('Надсилається');
    expect(`${3} ${tPlural(3, 'words.ratings')}`).toBe('3 оцінки');
  });

  it('switches model helpers and module-level labels to English', () => {
    setLocale('en');
    expect(wakingLine(1000)).toBe('Waking up…');
    expect(checklistStatus({ id: 'a', title: '', items: [] } as never)).toBe('Nothing yet');
    expect(REACTION_ROAD.sending.say).toBe('Sending');
    expect(ratingWord(null)).toBe('no ratings yet');
    expect(ratingWord({ mean: 4.5, count: 1 } as never)).toBe('4.5 · 1 rating');
    expect(ratingWord({ mean: 4, count: 3 } as never)).toBe('4.0 · 3 ratings');
    expect(humanMinutes(90)).toBe('1 hour 30 min');
    expect(tNow('feedBanners.matchOf', { n: 2, total: 5 })).toBe('2 of 5');
  });

  it('keeps Ukrainian plural forms', () => {
    expect(tPlural(1, 'words.ratings')).toBe('оцінка');
    expect(tPlural(2, 'words.ratings')).toBe('оцінки');
    expect(tPlural(5, 'words.ratings')).toBe('оцінок');
    expect(tPlural(11, 'words.ratings')).toBe('оцінок');
  });

  it('greets in English with the user name', () => {
    setLocale('en');
    const line = phantomLine({ part: 'day', mood: 'calm', waiting: [], name: 'Max' });
    expect(line).toBe('Good afternoon, Max. Nothing is waiting for you.');
  });
});

