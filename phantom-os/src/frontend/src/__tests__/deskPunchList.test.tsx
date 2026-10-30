import { describe, it, expect, vi, beforeEach, afterEach, type MockInstance } from 'vitest';
import { act, render, screen, fireEvent, within, cleanup } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SystemState } from '@shared/types';
import { DeskShell } from '../components/shell/DeskShell';
import { DeskToday } from '../components/shell/DeskToday';
import { taggedLines } from '../services/listSignals';
import { useSystemStore } from '../stores/systemStore';
import { useMessengerStore } from '../stores/messengerStore';
import { useUIStore } from '../stores/uiStore';
import * as api from '../services/api';
import { messengerApi } from '../services/messengerApi';
import type { Chat, Message } from '../types/messenger';

const msg = (id: string, text: string, sentAt: string): Message =>
  ({ id, senderId: 'me', senderName: 'Я', senderAvatar: '', timestamp: '10:00', type: 'text', text, sentAt }) as unknown as Message;

const CHATS: Chat[] = [
  {
    id: 'saved',
    title: 'Збережене',
    type: 'saved',
    messages: [
      msg('m1', 'Оплатити рахунок за інтернет #desk', '2026-10-16T08:00:00Z'),
      msg('m2', 'просто думка #deskwork', '2026-10-16T09:00:00Z'),
      msg('m3', '#desk Подзвонити в сервіс', '2026-10-16T10:00:00Z'),
    ],
  } as unknown as Chat,
];

let request: MockInstance<typeof api.request>;

beforeEach(() => {
  localStorage.clear();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(2026, 9, 16, 13, 10));
  Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: 1440 });
  useSystemStore.setState({ authenticated: true, state: SystemState.SHADOW, context: null, wsConnected: true });
  useUIStore.setState({ deskPane: null, toasts: [] });
  useMessengerStore.setState({ chats: [], activeChatId: '', hydrated: false, isCreateChatModalOpen: false });
  request = vi.spyOn(api, 'request').mockImplementation(async (_m: string, path: string) => {
    if (path === '/agent/status') return { foreground: { active: false, goal: null }, background: { active: false, goal: null } } as never;
    if (path === '/tools/calendar') return { events: [] } as never;
    if (path === '/messenger/reminders') return [] as never;
    return {} as never;
  });
  vi.spyOn(api.agentAskApi, 'waiting').mockResolvedValue({ questions: [] });
  vi.spyOn(api.symbioteApi, 'state').mockResolvedValue({ bodies: [], alive: 0, total: 0 });
  vi.spyOn(api.chatApi, 'getSessions').mockResolvedValue({ sessions: [], total: 0 });
  vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([]);
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('#desk punch list', () => {
  it('only the whole #desk tag counts, newest first, tag stripped', () => {
    expect(taggedLines(CHATS, 'desk', new Set())).toEqual([
      { chatId: 'saved', messageId: 'm3', text: 'Подзвонити в сервіс' },
      { chatId: 'saved', messageId: 'm1', text: 'Оплатити рахунок за інтернет' },
    ]);
    expect(taggedLines(CHATS, 'desk', new Set(['m3'])).map((l) => l.messageId)).toEqual(['m1']);
  });

  it('Desk Today: two #desk messages give two rows; a tick clears one locally and sends nothing', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={['/day']}>
          <Routes>
            <Route element={<DeskShell />}>
              <Route path="/day" element={<DeskToday />} />
            </Route>
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await screen.findByTestId('desk-today');
    act(() => useMessengerStore.setState({ chats: CHATS }));

    const list = await screen.findByTestId('today-desk');
    const rows = within(list).getAllByTestId('today-desk-row');
    expect(rows.map((r) => r.textContent)).toEqual(['Подзвонити в сервіс', 'Оплатити рахунок за інтернет']);

    const before = request.mock.calls.filter(([m]) => m !== 'GET').length;
    fireEvent.click(within(rows[0]).getByRole('button', { name: 'Готово' }));
    expect(within(screen.getByTestId('today-desk')).getAllByTestId('today-desk-row').map((r) => r.textContent)).toEqual([
      'Оплатити рахунок за інтернет',
    ]);
    expect(request.mock.calls.filter(([m]) => m !== 'GET').length).toBe(before);
    expect(JSON.parse(localStorage.getItem('phantom_desk_ticks') ?? '[]')).toEqual(['m3']);
  });
});
