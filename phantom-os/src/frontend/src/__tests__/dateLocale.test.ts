import fs from 'node:fs';
import path from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Message } from '../types/messenger';
import { dateTag } from '../i18n/dateTag';
import { shortDate } from '../components/messenger/SharedSection';
import { factDay } from '../components/messenger/PeerProfileSheet';
import { ago, when as knowsWhen } from '../components/messenger/PhantomKnows';
import { dayWord } from '../components/messenger/meetup/words';
import { when as detailsWhen } from '../components/messenger/MessageDetailsModal';
import { whenOf } from '../components/messenger/MediaLightboxModal';
import { blockTimeWords } from '../components/messenger/feed/SolanaCard';
import { formatReminderTime } from '../components/messenger/today/RemindersSheet';
import { formatEventWhen } from '../components/messenger/meetup/VenueSheet';
import { dateLabelOf } from '../components/messenger/feed/feedModel';
import { heardLine } from '../services/peerShared';
import { dueWords } from '../services/peerBetween';
import { lineTime, seenWord } from '../services/deviceApi';
import { addedLine } from '../services/peopleIdentity';
import { chatTimeLabel } from '../components/messenger/chatListFormat';
import { todayDateLine, todayTimeLabel } from '../components/messenger/todayFormat';
import { clockLabel, dayMonthLabel } from '../utils/clockFormat';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';

const setLang = (l: 'uk' | 'en' | 'cs') =>
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: l } }));

describe('мова дат', () => {
  afterEach(() => {
    setLang('uk');
    vi.useRealTimers();
  });

  const OCT15 = new Date(2026, 9, 15, 12, 0);
  const oct3 = new Date(2026, 9, 3, 14, 5);
  const freezeNow = () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(OCT15);
  };

  it('dateTag: uk → uk-UA, en → en-GB, cs → cs-CZ', () => {
    expect(dateTag('uk')).toBe('uk-UA');
    expect(dateTag('en')).toBe('en-GB');
    expect(dateTag('cs')).toBe('cs-CZ');
  });

  it('«Сьогодні» чеською: день і місяць чеські, без української', () => {
    const sun = new Date(2026, 9, 11, 12, 0);
    expect(todayDateLine(sun, 'cs')).toBe('Neděle 11. října');
    setLang('cs');
    expect(todayDateLine(sun)).toBe('Neděle 11. října');
    expect(todayDateLine(sun)).not.toMatch(/[а-яіїє]/i);
  });

  it('«Сьогодні»: час, день тижня й дата в рядку розмови йдуть за мовою', () => {
    const now = new Date(2026, 9, 15, 12, 0);
    expect(todayTimeLabel(new Date(2026, 9, 15, 9, 5).toISOString(), now, 'cs')).toBe('09:05');
    expect(todayTimeLabel(new Date(2026, 9, 13, 12, 0).toISOString(), now, 'cs')).toBe('út');
    expect(todayTimeLabel(new Date(2026, 9, 3, 12, 0).toISOString(), now, 'cs')).toBe('3. 10.');
    setLang('cs');
    expect(todayTimeLabel(new Date(2026, 9, 3, 12, 0).toISOString(), now)).toBe('3. 10.');
  });

  it('список розмов: давня дата чеська (11. 10.), не українська (11.10)', () => {
    const iso = new Date(2026, 9, 11, 14, 5, 0).toISOString();
    setLang('uk');
    const uk = chatTimeLabel(iso);
    setLang('cs');
    const cs = chatTimeLabel(iso);
    expect(cs).toBe('11. 10.');
    expect(cs).not.toBe(uk);
  });

  it('стрічка: «20 серпня» чеською — «20. srpna»', () => {
    setLang('cs');
    expect(dayMonthLabel(new Date(2026, 7, 20), false)).toBe('20. srpna');
    expect(dayMonthLabel(new Date(2025, 7, 20), true)).toBe('20. srpna 2025');
  });

  it('clockLabel: форматер береться за мовою, а не лишається першим створеним', () => {
    const spy = vi.spyOn(Intl, 'DateTimeFormat');
    setLang('uk');
    clockLabel(oct3);
    setLang('cs');
    expect(clockLabel(oct3)).toBe('14:05');
    expect(spy.mock.calls.map((c) => c[0])).toContain('cs-CZ');
    spy.mockRestore();
  });

  it('feedModel.dateLabelOf: роздільник дня чеською', () => {
    setLang('cs');
    const msg = { sentAt: '2026-08-20T12:00:00Z' } as Message;
    expect(dateLabelOf(msg, new Date(2026, 9, 15))).toBe('20. srpna');
  });

  it('SharedSection.shortDate', () => {
    expect(shortDate('2026-10-03T12:00:00', 'cs')).toBe('3. 10.');
  });

  it('PeerProfileSheet.factDay', () => {
    expect(factDay('2026-10-03T12:00:00', 'cs')).toBe('3. října');
  });

  it('PhantomKnows.when і ago', () => {
    freezeNow();
    const t = ((k: string) => k) as Parameters<typeof knowsWhen>[1];
    expect(knowsWhen('2026-10-03T12:00:00', t, 'cs')).toBe('3. října');
    expect(ago(oct3.getTime(), t, 'cs')).toBe('3. října');
  });

  it('meetup/words.dayWord', () => {
    setLang('cs');
    expect(dayWord(oct3.getTime(), OCT15.getTime())).toBe('so 3. 10.');
  });

  it('MessageDetailsModal.when', () => {
    const m = { sentAt: oct3.toISOString(), timestamp: '' } as Message;
    expect(detailsWhen(m, 'cs')).toBe('3. října v 14:05');
  });

  it('MediaLightboxModal.whenOf', () => {
    const m = { sentAt: oct3.toISOString(), timestamp: '' } as Message;
    expect(whenOf(m, 'cs')).toBe('3. října v 14:05');
  });

  it('SolanaCard.blockTimeWords', () => {
    setLang('cs');
    expect(blockTimeWords(oct3.getTime() / 1000)).toBe('3. 10. 2026 14:05');
  });

  it('RemindersSheet.formatReminderTime', () => {
    freezeNow();
    expect(formatReminderTime(oct3.getTime(), 'cs')).toBe('so 3. · 14:05');
  });

  it('VenueSheet.formatEventWhen', () => {
    setLang('cs');
    expect(formatEventWhen('2026-10-03T14:05')).toBe('3. 10. · 14:05');
  });

  it('peerShared.heardLine: давня дата чеською', () => {
    setLang('cs');
    const heard = new Date(2026, 7, 1, 14, 5).toISOString();
    expect(heardLine(heard, null, OCT15.getTime())).toBe('ve spojení 1. 8. 2026');
  });

  it('peerBetween.dueWords', () => {
    setLang('cs');
    expect(dueWords('2026-10-20T15:00', OCT15)).toBe('20. října do 15:00');
  });

  it('deviceApi.seenWord і lineTime', () => {
    setLang('cs');
    expect(seenWord(oct3.toISOString(), OCT15.getTime())).toBe('aktivní 3. října');
    expect(lineTime(oct3.toISOString(), OCT15.getTime())).toBe('3. 10., 14:05');
  });

  it('peopleIdentity.addedLine', () => {
    freezeNow();
    setLang('cs');
    expect(addedLine('2026-10-03T12:00:00')).toBe('Přidáno 3. 10.');
  });

  it('жоден шлях месенджера не вибирає мову дат як «en чи інакше uk-UA»', () => {
    const root = path.resolve(__dirname, '..');
    const dirs = ['components/messenger', 'services', 'stores', 'utils'];
    const bad: string[] = [];
    const walk = (dir: string) => {
      for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
        const p = path.join(dir, e.name);
        if (e.isDirectory()) {
          if (e.name !== '__tests__') walk(p);
        } else if (/\.tsx?$/.test(e.name)) {
          const src = fs.readFileSync(p, 'utf8');
          const date = /(toLocale(?:Date|Time)?String|DateTimeFormat)\(\s*(?:'uk-UA'|'en-(?:GB|US)'|undefined|\[\]|[^;\n]*?\?\s*'(?:uk-UA|en-GB|en-US)')/;
          const rel = path.relative(root, p);
          // cardModel форматує числа (роздільник розрядів), не дати.
          if (rel !== 'components/messenger/cards/cardModel.ts' && date.test(src)) bad.push(rel);
        }
      }
    };
    dirs.forEach((d) => walk(path.join(root, d)));
    expect(bad).toEqual([]);
  });
});
