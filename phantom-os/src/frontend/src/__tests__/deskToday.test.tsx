import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SystemState } from '@shared/types';
import { DeskShell } from '../components/shell/DeskShell';
import { DeskToday, todayEvents, todayReminders } from '../components/shell/DeskToday';
import MessengerLayout from '../layouts/MessengerLayout';
import { useSystemStore } from '../stores/systemStore';
import { useMessengerStore } from '../stores/messengerStore';
import { useUIStore } from '../stores/uiStore';
import * as api from '../services/api';
import { messengerApi, type NodeConversation } from '../services/messengerApi';

const PEER_CONV: NodeConversation = {
  id: 'conv_marta',
  title: 'Марта',
  kind: 'dm',
  circle: 'friends',
  handle: null,
  avatar: null,
  pinned: false,
  archived: false,
  created_at: '2026-09-26T10:00:00Z',
  updated_at: '2026-09-26T10:00:00Z',
  peer_node_id: 'a1b2c3d4e5f60718',
  last_kind: 'text',
  last_snippet: 'Привіт!',
  last_at: '2026-09-26T10:00:00Z',
  unread_count: 2,
};

function mountShell(route = '/') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[route]}>
        <Routes>
          <Route element={<DeskShell />}>
            <Route path="/" element={<MessengerLayout />} />
            <Route path="/messenger" element={<MessengerLayout />} />
            <Route path="/day" element={<DeskToday />} />
            <Route path="/hq" element={<div>hq-screen</div>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(2026, 9, 16, 13, 10));
  Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: 1440 });
  useSystemStore.setState({ authenticated: true, state: SystemState.SHADOW, context: null, wsConnected: true });
  useUIStore.setState({ deskPane: null, toasts: [] });
  useMessengerStore.setState({ chats: [], activeChatId: '', hydrated: false, isCreateChatModalOpen: false });
  vi.spyOn(api, 'request').mockImplementation(async (_m: string, path: string) => {
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
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('deskToday', () => {
  it('Day slot opens the full Today screen', async () => {
    mountShell('/');
    const dock = await screen.findByTestId('phantom-dock');
    const dayBtn = within(dock).getByRole('button', { name: 'День' });
    fireEvent.click(dayBtn);
    expect(await screen.findByTestId('desk-today')).toBeTruthy();
    expect(dayBtn).toHaveAttribute('aria-current', 'page');
    expect(screen.queryByTestId('desk-side-pane')).toBeNull();
  });

  it("Today lists only today's events and reminders", async () => {
    const now = new Date(2026, 9, 16, 13, 10).getTime();
    const events = [
      {
        id: 'e1',
        title: 'Climbing',
        start_at: new Date(2026, 9, 16, 16, 0).toISOString(),
        end_at: new Date(2026, 9, 16, 18, 0).toISOString(),
        all_day: false,
        location: 'North Wall',
      },
      {
        id: 'e2',
        title: 'Dinner',
        start_at: new Date(2026, 9, 16, 19, 30).toISOString(),
        end_at: new Date(2026, 9, 16, 21, 0).toISOString(),
        all_day: false,
        location: null,
      },
      {
        id: 'e3',
        title: 'Other',
        start_at: new Date(2026, 9, 17, 10, 0).toISOString(),
        end_at: new Date(2026, 9, 17, 11, 0).toISOString(),
        all_day: false,
        location: null,
      },
    ];
    expect(todayEvents(events, now).map((e) => e.title)).toEqual(['Climbing', 'Dinner']);

    const remTodayMs = new Date(2026, 9, 16, 18, 0).getTime();
    const remTomorrowMs = new Date(2026, 9, 17, 9, 0).getTime();
    expect(
      todayReminders(
        [
          { id: 'r1', text: 'Bring shoes', atMs: remTodayMs },
          { id: 'r2', text: 'Later', atMs: remTomorrowMs },
        ],
        now,
      ),
    ).toEqual([{ id: 'r1', text: 'Bring shoes', atMs: remTodayMs }]);

    vi.spyOn(api, 'request').mockImplementation(async (_m: string, path: string) => {
      if (path === '/agent/status') return { foreground: { active: false, goal: null }, background: { active: false, goal: null } } as never;
      if (path === '/tools/calendar') return { events } as never;
      if (path === '/messenger/reminders') {
        return [
          { id: 'r1', text: 'Bring shoes', at_ms: remTodayMs },
          { id: 'r2', text: 'Later', at_ms: remTomorrowMs },
        ] as never;
      }
      return {} as never;
    });

    mountShell('/day');
    const next = await screen.findByTestId('today-next');
    expect(next.textContent).toContain('Climbing');
    expect(next.textContent).toContain('16:00');
    expect(next.textContent).toContain('North Wall');

    const rows = await screen.findAllByTestId('today-event');
    expect(rows.map((r) => r.textContent)).toHaveLength(2);
    expect(rows[0].textContent).toContain('Climbing');
    expect(rows[1].textContent).toContain('Dinner');
    expect(screen.getByTestId('desk-today').textContent).not.toContain('Other');

    const remRows = await screen.findAllByTestId('today-reminder');
    expect(remRows).toHaveLength(1);
    expect(remRows[0].textContent).toContain('Bring shoes');
  });

  it('Engine questions and waiting chats lead to their place', async () => {
    vi.spyOn(api.agentAskApi, 'waiting').mockResolvedValue({
      questions: [
        {
          task_id: 't1',
          goal: 'Compare venues',
          question: 'Book 10:00 or 11:30?',
          kind: 'answer',
          asked_at: '2026-10-16T12:00:00Z',
        },
      ],
    });
    const first = mountShell('/day');
    const ask = await screen.findByTestId('today-ask');
    expect(ask.textContent).toContain('Compare venues');
    expect(ask.textContent).toContain('Book 10:00 or 11:30?');
    fireEvent.click(within(ask).getByRole('button', { name: 'Відповісти' }));
    expect(await screen.findByText('hq-screen')).toBeTruthy();
    first.unmount();

    vi.spyOn(api.agentAskApi, 'waiting').mockResolvedValue({ questions: [] });
    vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([PEER_CONV]);
    useMessengerStore.setState({ chats: [], activeChatId: '', hydrated: false });
    mountShell('/day');
    const chatBtn = await screen.findByTestId('today-chat');
    expect(chatBtn.textContent).toContain('Марта');
    fireEvent.click(chatBtn);
    expect(useMessengerStore.getState().activeChatId).toBe('conv_marta');
  });

  it('Today is honest when empty', async () => {
    mountShell('/day');
    expect(await screen.findByTestId('desk-today-empty')).toBeTruthy();
    expect(screen.queryByTestId('today-agenda')).toBeNull();
    expect(screen.queryByTestId('today-next')).toBeNull();
    expect(screen.queryByTestId('today-waiting')).toBeNull();
    expect(screen.getByTestId('desk-today').textContent).not.toMatch(/USDC|Гаманець|tram/);
  });

  it('one-sided day has no empty half', async () => {
    vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([PEER_CONV]);
    mountShell('/day');
    const single = await screen.findByTestId('today-single');
    expect(within(single).getByTestId('today-waiting')).toBeTruthy();
    expect(screen.getByTestId('desk-today').querySelector('.flex-\\[1\\.25\\]')).toBeNull();
  });
});
