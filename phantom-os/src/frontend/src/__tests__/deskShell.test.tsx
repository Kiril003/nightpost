/**
 * Оболонка PHANTOM на ПК (components/shell) — сторож живого, не фасаду.
 *
 *   1. Присутність рахується лише з сигналів вузла: лист чекає → «Пильную»,
 *      агент працює → «Працюю», варта й чужий поруч → «Тривога», тиша → «Спокій».
 *   2. Головна без даних — лише куля й речення; жодної картки-заглушки
 *      («мовчить», «— гПа», «Календар вільний», «NEXUS»).
 *   3. З даними — картка «Чекають на вас» й «Далі» з реальних полів.
 *   4. Док — рівно шість живих розділів; «Вийти» не в доку, а в «Я».
 *   5. Головна бере розмови, НЕ відкриваючи їх: жодного «прочитано» за людину.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SystemState } from '@shared/types';
import type { ContextSnapshot } from '@shared/types';
import { DeskShell } from '../components/shell/DeskShell';
import MessengerLayout from '../layouts/MessengerLayout';
import { displayNameOf, presenceOf, phantomLine } from '../components/shell/presence';
import { nextEventOf, waitingOf } from '../components/shell/useShellSignals';
import { useSystemStore } from '../stores/systemStore';
import { useMessengerStore } from '../stores/messengerStore';
import * as api from '../services/api';
import { messengerApi } from '../services/messengerApi';
import type { Chat } from '../types/messenger';

const chat = (over: Partial<Chat>): Chat =>
  ({ id: 'c1', title: 'Оксана', avatar: '', type: 'dm', circle: 'all', unreadCount: 0, messages: [], ...over }) as Chat;

const ctxWith = (other: boolean) =>
  ({ presence: { user_detected: true, user_distance_cm: null, other_detected: other, other_distance_cm: null } }) as unknown as ContextSnapshot;

describe('присутність Nightpost', () => {
  it('рахується з сигналів, а не вигадується', () => {
    const base = { state: SystemState.SHADOW, context: null, agentBusy: false, waiting: 0 };
    expect(presenceOf(base).word).toBe('Спокій');
    expect(presenceOf({ ...base, waiting: 1 }).mood).toBe('watch');
    expect(presenceOf({ ...base, waiting: 1, agentBusy: true }).mood).toBe('work');
    expect(presenceOf({ ...base, state: SystemState.SENTINEL, context: ctxWith(false) }).mood).toBe('watch');
    expect(presenceOf({ ...base, state: SystemState.SENTINEL, context: ctxWith(true) }).word).toBe('Тривога');
  });

  it('речення Nightpost називає людину, що чекає, і найближчу подію', () => {
    expect(phantomLine({ part: 'dusk', mood: 'watch', waiting: [{ name: 'Оксана' }] })).toBe('Добрий вечір. Оксана чекає на відповідь.');
    expect(phantomLine({ part: 'day', mood: 'calm', waiting: [], nextEvent: { title: 'Зустріч', inMinutes: 95 } })).toBe(
      'Добрий день. За 1 годину 35 хв — Зустріч.',
    );
    expect(phantomLine({ part: 'night', mood: 'calm', waiting: [] })).toBe('Добраніч. Нічого не чекає на вас.');
  });

  it('звертається на ім\'я, яким людина назвалась; без імені — як раніше', () => {
    expect(phantomLine({ part: 'dusk', mood: 'calm', waiting: [], name: 'Кирило' })).toBe('Добрий вечір, Кирило. Нічого не чекає на вас.');
    expect(phantomLine({ part: 'dusk', mood: 'calm', waiting: [], name: '  ' })).toBe('Добрий вечір. Нічого не чекає на вас.');
    expect(displayNameOf({ username: 'phantom', preferences: { display_name: 'Кирило' } })).toBe('Кирило');
    expect(displayNameOf({ username: 'phantom', preferences: {} })).toBe('phantom');
  });

  it('хто чекає: лише живі розмови з непрочитаним, без показових і каналів', () => {
    const out = waitingOf([
      chat({ id: 'a', unreadCount: 2, lastAt: '2026-09-26T10:00:00Z' }),
      chat({ id: 'b', unreadCount: 1, isDemo: true }),
      chat({ id: 'c', unreadCount: 1, type: 'channel' }),
      chat({ id: 'd', unreadCount: 0 }),
      chat({ id: 'e', unreadCount: 1, lastAt: '2026-09-26T12:00:00Z' }),
    ]);
    expect(out.map((c) => c.id)).toEqual(['e', 'a']);
  });

  it('найближча подія: час вузла без поясу — UTC; минулі й далі доби — ні', () => {
    const now = Date.parse('2026-09-26T16:00:00Z');
    const ev = (id: string, start: string, end: string) => ({ id, title: id, start_at: start, end_at: end, all_day: false, location: null });
    const next = nextEventOf(
      [ev('минула', '2026-09-26T10:00:00', '2026-09-26T11:00:00'), ev('завтра', '2026-09-28T10:00:00', '2026-09-28T11:00:00'), ev('скоро', '2026-09-26T17:35:00', '2026-09-26T18:00:00')],
      now,
    );
    expect(next?.title).toBe('скоро');
    expect(next?.inMinutes).toBe(95);
  });
});

function mountShell(route = '/') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[route]}>
        <Routes>
          <Route element={<DeskShell />}>
            <Route path="/" element={<MessengerLayout />} />
            <Route path="/messenger" element={<MessengerLayout />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  useSystemStore.setState({ authenticated: true, state: SystemState.SHADOW, context: null, wsConnected: true });
  useMessengerStore.setState({ chats: [], activeChatId: '', hydrated: false });
  vi.spyOn(api, 'request').mockImplementation(async (_m: string, path: string) => {
    if (path === '/agent/status') return { foreground: { active: false, goal: null }, background: { active: false, goal: null } } as never;
    if (path === '/tools/calendar') return { events: [] } as never;
    return {} as never;
  });
  vi.spyOn(api.chatApi, 'getSessions').mockResolvedValue({ sessions: [], total: 0 });
  vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
});

afterEach(() => vi.restoreAllMocks());

describe('оболонка ПК', () => {
  it('верхня смуга тримає присутність, час і обліковий запис; острів внизу — розділи', async () => {
    vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([]);
    const { container } = mountShell();
    const top = await screen.findByTestId('desk-top');
    const dock = await screen.findByRole('navigation', { name: 'Розділи' });
    expect(within(top).queryByTestId('shell-mood')).toBeNull();
    expect(within(top).getAllByRole('button').map((b) => b.dataset.testid)).toEqual(['desk-brand']);
    expect(within(top).getByText(/^\d{2}:\d{2}$/)).toBeTruthy();
    expect(within(dock).getByRole('button', { name: 'Рушій' })).toBeTruthy();
    expect(container.querySelector('.shell-root > header')).toBe(top);
    expect(dock.className).toContain('rounded-full');
  });

  it('док — шість живих слотів, Головна — це Розмови; «Вийти» не в доку', async () => {
    vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([]);
    mountShell();
    const dock = await screen.findByTestId('phantom-dock');
    const labels = within(dock).getAllByRole('button').map((b) => b.getAttribute('aria-label'));
    expect(labels).toEqual(['Сьогодні', 'Мапа', 'День', 'Гаманець', 'Рушій', 'Я']);
    expect(within(dock).queryByText(/Вийти|Вихід|Поліс|Воля|Вартовий|Привид|Система|Додатки/)).toBeNull();
  });
});
