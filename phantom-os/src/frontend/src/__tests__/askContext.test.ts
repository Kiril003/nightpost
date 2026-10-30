/**
 * «Спитати Nightpost»: контекст кожної поверхні будується лише з того, що на
 * екрані. Немає даних — немає й підказок (лише поле). Червоніє, щойно
 * підказку вигадати без даних або розпухнуть факти понад стелю.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { chatAsk, composeAsk, dayAsk, FACTS_MAX, freeEvening, personAsk, walletAsk } from '../services/askContext';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { DayItem } from '../services/dayApi';
import type { Chat, Message } from '../types/messenger';

const at = (h: number, m = 0) => {
  const d = new Date(2026, 9, 28, h, m, 0, 0);
  return d.getTime();
};

const ev = (id: string, h: number, title: string, endH?: number): DayItem => ({
  id,
  kind: 'event',
  title,
  atMs: at(h),
  endMs: endH == null ? null : at(endH),
  allDay: false,
  peerId: null,
  overdue: false,
});

const msg = (id: string, text: string, from = 'p1', name = 'Петро'): Message =>
  ({ id, senderId: from, senderName: name, senderAvatar: '', timestamp: '10:00', type: 'text', text, isSelf: from === 'me' }) as unknown as Message;

beforeEach(() => {
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
});

describe('День', () => {
  it('порожній день — без підказок, мітка «День»', () => {
    const ctx = dayAsk({ items: [], nowMs: at(12) });
    expect(ctx.surface).toBe('day');
    expect(ctx.suggestions).toEqual([]);
    expect(ctx.label).toBe('День');
  });

  it('три події — мітка з числом, «що далі» і вільний вечір', () => {
    const items = [ev('a', 18, 'Тренування', 19), ev('b', 19, 'Зустріч з Оксаною', 20), ev('c', 21, 'Забрати ключі', 21)];
    const ctx = dayAsk({ items, nowMs: at(12) });
    expect(ctx.label).toBe('День · 3 події');
    expect(ctx.suggestions).toEqual(['Що далі сьогодні?', 'Коли я вільний увечері?']);
    expect(ctx.facts).toContain('Тренування');
    expect(ctx.facts).toContain('18:00');
  });

  it('вечір зайнятий щільно — підказки про вечір немає', () => {
    const items = [18, 19, 20, 21, 22].map((h) => ev(`e${h}`, h, `Подія ${h}`, h + 1));
    expect(freeEvening(items, at(12))).toBe(false);
    expect(dayAsk({ items, nowMs: at(12) }).suggestions).toEqual(['Що далі сьогодні?']);
  });

  it('усе вже минуло і вечір скінчився — підказок немає', () => {
    const items = [ev('a', 9, 'Ранок', 10)];
    expect(dayAsk({ items, nowMs: at(23, 30) }).suggestions).toEqual([]);
  });
});

describe('Гаманець', () => {
  it('немає даних — без підказок', () => {
    expect(walletAsk(null).suggestions).toEqual([]);
    expect(walletAsk({ balances: [], week: [], splits: [], test: true }).suggestions).toEqual([]);
    expect(walletAsk(null).label).toBe('Гаманець');
  });

  it('перекази й частки — лише ті підказки, що мають дані', () => {
    const ctx = walletAsk({
      balances: [{ coin: 'SOL', amount: '1.24' }],
      week: [{ peer: 'p', name: 'Оксана', coin: 'SOL', amount: '0.10' }],
      splits: [{ peer: 'q', name: 'Двір', coin: 'SOL', amount: '0.30', owesMe: true }],
      test: true,
    });
    expect(ctx.label).toBe('Гаманець · тиждень');
    expect(ctx.suggestions).toEqual(['Куди пішли гроші цього тижня?', 'Хто мені винен?']);
    expect(ctx.facts).toContain('1.24 SOL');
    expect(ctx.facts).toContain('Оксана');
  });
});

describe('Людина', () => {
  it('немає листів і обіцянок — без підказок, мітка — імʼя', () => {
    const ctx = personAsk({ name: 'Оксана', messages: [], meId: 'me', promises: [] });
    expect(ctx.surface).toBe('person');
    expect(ctx.label).toBe('Оксана');
    expect(ctx.suggestions).toEqual([]);
  });

  it('листи й обіцянки — дві підказки', () => {
    const ctx = personAsk({
      name: 'Оксана',
      messages: [msg('1', 'Так, чекаю біля входу', 'p1', 'Оксана')],
      meId: 'me',
      promises: ['Повернути книгу'],
    });
    expect(ctx.suggestions).toEqual(['Про що ми говорили останнім часом?', 'Які мої обіцянки цій людині?']);
    expect(ctx.facts).toContain('Повернути книгу');
    expect(ctx.facts).toContain('чекаю біля входу');
  });
});

describe('Розмова і група', () => {
  const chat = (over: Partial<Chat>): Chat =>
    ({ id: 'c', title: 'Двір', type: 'group', unreadCount: 0, messages: [], ...over }) as unknown as Chat;

  it('порожня розмова — без підказок', () => {
    const ctx = chatAsk({ chat: chat({ unreadCount: 4 }), meId: 'me' });
    expect(ctx.surface).toBe('group');
    expect(ctx.suggestions).toEqual([]);
  });

  it('5 непрочитаних — мітка з числом, підсумок і «що вирішили»', () => {
    const messages = [msg('1', 'Збори о шостій біля підʼїзду'), msg('2', 'Воду вимкнуть у четвер'), msg('3', 'Скинемось на фарбу')];
    const ctx = chatAsk({ chat: chat({ unreadCount: 5, messages }), meId: 'me' });
    expect(ctx.label).toBe('Двір · 5 непрочитаних');
    expect(ctx.suggestions).toEqual(['Підсумуй непрочитане', 'Що вирішили?']);
    expect(ctx.facts).toContain('Петро: Збори о шостій');
  });

  it('1:1 з одним непрочитаним — лише «що вирішили»', () => {
    const ctx = chatAsk({ chat: chat({ type: 'dm', title: 'Оксана', unreadCount: 1, messages: [msg('1', 'Привіт')] }), meId: 'me' });
    expect(ctx.surface).toBe('chat');
    expect(ctx.label).toBe('Оксана · 1 непрочитане');
    expect(ctx.suggestions).toEqual(['Що вирішили?']);
  });

  it('факти не перевищують стелю', () => {
    const messages = Array.from({ length: 400 }, (_, i) => msg(String(i), `Дуже довгий лист номер ${i} `.repeat(4)));
    const ctx = chatAsk({ chat: chat({ unreadCount: 2, messages }), meId: 'me' });
    expect(ctx.facts.length).toBeLessThanOrEqual(FACTS_MAX);
    expect(ctx.facts).toContain('номер 399');
  });
});

describe('Чернетка', () => {
  it('чернетка і останній вхідний — мітка відповіді, без підказок', () => {
    const ctx = composeAsk({ draft: 'Біжу, буду за 5 хвилин', lastIncoming: 'Так, чекаю біля входу', name: 'Оксана' });
    expect(ctx.surface).toBe('compose');
    expect(ctx.label).toBe('Відповідь · Оксана');
    expect(ctx.draft).toBe('Біжу, буду за 5 хвилин');
    expect(ctx.suggestions).toEqual([]);
    expect(ctx.facts).toContain('чекаю біля входу');
  });

  it('без вхідного — мітка «Чернетка»', () => {
    expect(composeAsk({ draft: '' }).label).toBe('Чернетка');
  });
});
