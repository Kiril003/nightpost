/**
 * «Що в мене сьогодні?» → картка Дня: дріт везе дату, пункти — живі з Дня;
 * картка вчорашнього дня пунктів не вигадує.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MessageCard } from '../components/messenger/cards/MessageCard';
import { dayCardOf, localDay } from '../components/messenger/cards/DayCard';
import { messageFromNode } from '../services/messengerApi';
import { dayApi, type DayItem } from '../services/dayApi';
import { useMessengerStore } from '../stores/messengerStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Message } from '../types/messenger';

const at = (h: number, dayOffset = 0) => {
  const d = new Date();
  d.setDate(d.getDate() + dayOffset);
  d.setHours(h, 0, 0, 0);
  return d.getTime();
};

const card = (ms: number, extra: Partial<Message> = {}): Message =>
  ({
    id: 'm1', senderId: 'peer_phantom_agent', senderName: 'Nightpost', senderAvatar: '', timestamp: '10:00',
    type: 'widget:day', isSelf: false, dayData: { v: 1, day: localDay(ms), at: ms }, ...extra,
  }) as unknown as Message;

const item = (id: string, h: number, title: string, overdue = false): DayItem =>
  ({ id, kind: 'reminder', title, atMs: at(h), endMs: null, allDay: false, peerId: null, overdue }) as DayItem;

const flush = () => act(async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); });

beforeEach(() => {
  vi.restoreAllMocks();
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
  useMessengerStore.setState({ chats: [], scheduledMessages: [], currentUser: { id: 'node_me', name: 'Кирило', avatar: '' } } as never);
});
afterEach(cleanup);

describe('DayCard', () => {
  it('рядок мосту з kind widget:day стає карткою з dayData', () => {
    const ms = at(10);
    const msg = messageFromNode(
      { id: 'srv_1', kind: 'widget:day', author_id: 'peer_phantom_agent', author_name: 'Nightpost', sent_at: '2026-10-25T10:00:00', body: '', dayData: { v: 1, day: localDay(ms), at: ms } } as never,
      'node_me',
    );
    expect(msg.type).toBe('widget:day');
    expect(dayCardOf(msg)).toEqual({ v: 1, day: localDay(ms), at: ms });
  });

  it('криве тіло не читається як день', () => {
    expect(dayCardOf(card(at(10), { dayData: { v: 1, day: 'вчора', at: 1 } } as never))).toBeNull();
    expect(dayCardOf(card(at(10), { dayData: undefined } as never))).toBeNull();
  });

  it('сьогодні: пункти з Дня живими, по часу', async () => {
    const today = vi.spyOn(dayApi, 'today').mockResolvedValue({
      calendar: 'granted',
      items: [item('r2', 18, 'Забрати посилку'), item('r1', 9, 'Подзвонити мамі', true)],
    });
    render(<MessageCard msg={card(at(10))} meId="node_me" meName="Кирило" />);
    await flush();
    expect(today).toHaveBeenCalled();
    const el = screen.getByTestId('day-card');
    expect(el.textContent).toContain('Сьогодні');
    const rows = [...el.querySelectorAll('[data-day-row]')].map((n) => n.textContent);
    expect(rows[0]).toContain('Подзвонити мамі');
    expect(rows[0]).toContain('Прострочено');
    expect(rows.some((r) => r?.includes('Забрати посилку'))).toBe(true);
  });

  it('порожній день — чесне «Вільно», без вигаданих пунктів', async () => {
    vi.spyOn(dayApi, 'today').mockResolvedValue({ calendar: 'granted', items: [] });
    render(<MessageCard msg={card(at(10))} meId="node_me" meName="Кирило" />);
    await flush();
    const el = screen.getByTestId('day-card');
    expect(el.querySelectorAll('[data-day-row]').length).toBe(0);
    expect(el.querySelector('[data-day-empty]')?.textContent).toBe('Вільно');
  });

  it('календар закритий і пусто — кнопка «Показати календар» питає дозвіл', async () => {
    vi.spyOn(dayApi, 'today').mockResolvedValue({ calendar: 'denied', items: [] });
    const ask = vi.spyOn(dayApi, 'askCalendar').mockResolvedValue({ asked: true, granted: true });
    render(<MessageCard msg={card(at(10))} meId="node_me" meName="Кирило" />);
    await flush();
    fireEvent.click(screen.getByText('Показати календар'));
    await flush();
    // Зріз 5: спершу аркуш з підписами, системний запит — лише на «Дозволити».
    expect(ask).not.toHaveBeenCalled();
    fireEvent.click(document.querySelector('[data-calendar-ask-allow]') as HTMLElement);
    await flush();
    expect(ask).toHaveBeenCalledTimes(1);
  });

  it('вчорашня картка: лише дата й «Відкрити День», День не питає', async () => {
    const today = vi.spyOn(dayApi, 'today').mockResolvedValue({ calendar: 'granted', items: [item('r1', 9, 'Подзвонити мамі')] });
    render(<MessageCard msg={card(at(10, -1))} meId="node_me" meName="Кирило" />);
    await flush();
    const el = screen.getByTestId('day-card-past');
    expect(el.textContent).toContain('Минулий день');
    expect(el.textContent).not.toContain('Подзвонити мамі');
    expect(today).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText('Відкрити День'));
    await flush();
    expect(screen.getByTestId('day-sheet')).toBeTruthy();
  });
});
