import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { actionFor, hintWords, isHintDone, markHintDone, quickReplies, resetHintsDone, runAction } from '../letterActions';
import { armReminders, disarmReminders, pendingReminders } from '../letterReminders';

// Неділя, 27 вересня 2026, 19:24.
const SENT = new Date(2026, 8, 27, 19, 20);
const NOW = new Date(2026, 8, 27, 19, 24);

describe('дія з листа', () => {
  it('«до 20:00» → нагадати о 19:40, як у макеті', () => {
    const a = actionFor('Посилка чекає до 20:00', SENT, NOW);
    expect(a?.kind).toBe('remind');
    expect(hintWords(a!).say).toBe('Нагадати о 19:40?');
  });

  it('завтра — зі словом дня', () => {
    expect(hintWords(actionFor('завтра о 10 буду', SENT, NOW)!).say).toBe('Нагадати завтра о 09:40?');
  });

  it('день без години — зранку того дня', () => {
    expect(hintWords(actionFor('У середу поїдемо', SENT, NOW)!).say).toBe('Нагадати в середу зранку?');
  });

  it('час, що вже минув, — не дія, і тоді береться номер', () => {
    const later = new Date(2026, 8, 27, 20, 30);
    expect(actionFor('Посилка чекає до 20:00', SENT, later)).toBeNull();
    const a = actionFor('Номер 2045 1188 3302, до 20:00', SENT, later);
    expect(a?.kind).toBe('copy-parcel');
    expect(hintWords(a!).say).toBe('Номер посилки 2045 1188 3302');
  });

  it('близько до межі — нагадати в саму мить', () => {
    const close = new Date(2026, 8, 27, 19, 50);
    expect(hintWords(actionFor('до 20:00', SENT, close)!).say).toBe('Нагадати о 20:00?');
  });

  it('телефон → скопіювати', () => {
    expect(hintWords(actionFor('Мій новий 067 123 45 67', SENT, NOW)!)).toEqual({
      say: 'Телефон 067 123 45 67',
      button: 'Скопіювати',
      card: 'Скопіювати телефон',
    });
  });

  it('звичайний лист — без дії', () => {
    expect(actionFor('Сиджу в кавʼярні навпроти пошти', SENT, NOW)).toBeNull();
    expect(actionFor('Скинь 500 грн', SENT, NOW)).toBeNull();
  });
});

describe('відповіді одним дотиком', () => {
  it('так/ні і «Буду о…» на питання про зустріч', () => {
    expect(quickReplies('Будеш о 20:30?', SENT, NOW)).toEqual(['Так', 'Ні', 'Буду о 20:30']);
  });
  it('так/ні без часу', () => {
    expect(quickReplies('Забереш посилку?', SENT, NOW)).toEqual(['Так', 'Ні']);
  });
  it('прохання', () => {
    expect(quickReplies('Скинь номер', SENT, NOW)).toEqual(['Добре', 'Зараз']);
  });
  it('відкрите питання й розповідь — нічого', () => {
    expect(quickReplies('Коли приїдеш?', SENT, NOW)).toEqual([]);
    expect(quickReplies('Сиджу в кавʼярні', SENT, NOW)).toEqual([]);
  });
  it('англійською — англійською', () => {
    expect(quickReplies('Are you coming?', SENT, NOW)).toEqual(['Yes', 'No']);
  });
});

describe('закриті підказки', () => {
  beforeEach(() => {
    localStorage.clear();
    resetHintsDone();
  });
  it('закрита не повертається навіть після перезапуску', () => {
    expect(isHintDone('m1')).toBe(false);
    markHintDone('m1');
    resetHintsDone();
    expect(isHintDone('m1')).toBe(true);
  });
});

describe('нагадування', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
  });
  afterEach(() => {
    disarmReminders();
    vi.useRealTimers();
    delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
  });

  it('ставиться, живе в сховищі і дзвонить рідним сповіщенням у свою мить', async () => {
    const notify = vi.fn(() => true);
    (window as unknown as { PhantomBridge: unknown }).PhantomBridge = { notifyMessage: notify };
    const a = actionFor('Посилка чекає до 20:00', SENT, NOW)!;
    await runAction(a, { who: 'Оксана', text: 'Посилка чекає до 20:00', chatId: 'c1' });
    expect(pendingReminders()).toHaveLength(1);
    vi.advanceTimersByTime(15 * 60_000);
    expect(notify).not.toHaveBeenCalled();
    vi.advanceTimersByTime(2 * 60_000);
    expect(notify).toHaveBeenCalledWith('Нагадування · Оксана', 'Посилка чекає до 20:00', 'c1');
    expect(pendingReminders()).toHaveLength(0);
  });

  it('на телефоні — рядок у рідній таблиці через міст, без таймера сторінки', async () => {
    const calls: Array<[string, string, string | null]> = [];
    (window as unknown as { PhantomBridge: unknown }).PhantomBridge = {
      call: (m: string, p: string, b: string | null) => {
        calls.push([m, p, b]);
        return JSON.stringify({ ok: true, status: 200, data: { id: 'n1' } });
      },
    };
    const a = actionFor('Посилка чекає до 20:00', SENT, NOW)!;
    await runAction(a, { who: 'Оксана', text: 'Посилка чекає до 20:00', chatId: 'c1' });
    expect(calls).toEqual([
      ['POST', '/messenger/reminders', JSON.stringify({ text: 'Оксана: Посилка чекає до 20:00', at_ms: new Date(2026, 8, 27, 19, 40).getTime() })],
    ]);
    expect(pendingReminders()).toHaveLength(0);
  });

  it('старий міст без шляху — запас сторінки', async () => {
    (window as unknown as { PhantomBridge: unknown }).PhantomBridge = {
      call: () => JSON.stringify({ ok: false, status: 404, code: 'not_found' }),
      notifyMessage: () => true,
    };
    await runAction(actionFor('до 20:00', SENT, NOW)!, { who: 'Оксана', text: 'до 20:00', chatId: 'c1' });
    expect(pendingReminders()).toHaveLength(1);
  });

  it('пропущене, поки месенджер спав, приходить при відкритті', () => {
    const notify = vi.fn(() => true);
    (window as unknown as { PhantomBridge: unknown }).PhantomBridge = { notifyMessage: notify };
    localStorage.setItem(
      'phantom.letterReminders',
      JSON.stringify([
        { id: 'r1', at: NOW.getTime() - 3600_000, who: 'Тарас', text: 'о 18:30', chatId: 'c2' },
        { id: 'r2', at: NOW.getTime() - 48 * 3600_000, who: 'Тарас', text: 'давнє', chatId: 'c2' },
      ]),
    );
    armReminders(NOW.getTime());
    vi.advanceTimersByTime(0);
    expect(notify).toHaveBeenCalledTimes(1);
    expect(notify).toHaveBeenCalledWith('Нагадування · Тарас', 'о 18:30', 'c2');
    expect(pendingReminders()).toHaveLength(0);
  });
});
