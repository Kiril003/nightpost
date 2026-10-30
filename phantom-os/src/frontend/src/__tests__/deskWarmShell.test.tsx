import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { render, screen, fireEvent, within, act } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SystemState } from '@shared/types';
import { DeskShell } from '../components/shell/DeskShell';
import MessengerLayout from '../layouts/MessengerLayout';
import { useSystemStore } from '../stores/systemStore';
import { useMessengerStore } from '../stores/messengerStore';
import { useUIStore } from '../stores/uiStore';
import * as api from '../services/api';
import { messengerApi, type NodeConversation } from '../services/messengerApi';

const ROOT = process.cwd();
const read = (p: string): string => readFileSync(join(ROOT, p), 'utf8');

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
};

const NODE_CONV: NodeConversation = {
  id: 'peer_phantom_agent',
  title: 'Nightpost',
  kind: 'phantom',
  circle: 'all',
  handle: null,
  avatar: null,
  pinned: true,
  archived: false,
  created_at: '2026-09-24T07:15:00Z',
  updated_at: '2026-09-24T07:15:00Z',
  contact_id: 'peer_phantom_agent',
  peer_node_id: 'peer_phantom_agent',
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
            <Route path="/day" element={<div>day-screen</div>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: 1440 });
  useSystemStore.setState({ authenticated: true, state: SystemState.SHADOW, context: null, wsConnected: true });
  useUIStore.setState({ deskPane: null, toasts: [] });
  useMessengerStore.setState({ chats: [], activeChatId: '', hydrated: false });
  vi.spyOn(api, 'request').mockImplementation(async (_m: string, path: string) => {
    if (path === '/agent/status') return { foreground: { active: false, goal: null }, background: { active: false, goal: null } } as never;
    if (path === '/tools/calendar') return { events: [] } as never;
    return {} as never;
  });
  vi.spyOn(api.chatApi, 'getSessions').mockResolvedValue({ sessions: [], total: 0 });
  vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([]);
});

afterEach(() => vi.restoreAllMocks());

describe('deskWarmShell', () => {
  it('island carries six slots in order', async () => {
    const { container } = mountShell();
    const dock = await screen.findByTestId('phantom-dock');
    const labels = within(dock).getAllByRole('button').map((b) => b.getAttribute('aria-label'));
    expect(labels).toEqual(['Сьогодні', 'Мапа', 'День', 'Гаманець', 'Рушій', 'Я']);
    expect(dock.className).toContain('bottom-3');
    expect(dock.className).toContain('rounded-full');
    expect(container.querySelector('.w-\\[76px\\]')).toBeNull();
  });

  it('Wallet slot opens the Chats side column; Day opens Today', async () => {
    mountShell();
    const dock = await screen.findByTestId('phantom-dock');
    const dayBtn = within(dock).getByRole('button', { name: 'День' });
    const walletBtn = within(dock).getByRole('button', { name: 'Гаманець' });

    fireEvent.click(dayBtn);
    expect(screen.queryByTestId('desk-side-pane')).toBeNull();
    expect(dayBtn).toHaveAttribute('aria-current', 'page');

    fireEvent.click(walletBtn);
    const walletAside = await screen.findByTestId('desk-side-pane', {}, { timeout: 8000 });
    expect(walletAside).toHaveAttribute('data-pane', 'wallet');
  });

  it('top strip holds the clock and the brand only; brand opens the Nightpost chat', async () => {
    vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([PEER_CONV, NODE_CONV]);
    mountShell();
    const top = await screen.findByTestId('desk-top');
    expect(within(top).getByText(/^\d{2}:\d{2}$/)).toBeTruthy();
    expect(within(top).getAllByRole('button').map((b) => b.dataset.testid)).toEqual(['desk-brand']);

    await screen.findByTestId('chat-list');
    expect(useMessengerStore.getState().activeChatId).toBe('conv_marta');

    fireEvent.click(within(top).getByTestId('desk-brand'));
    expect(useMessengerStore.getState().activeChatId).toBe('peer_phantom_agent');
  });

  it('top strip carries no persona word', async () => {
    useSystemStore.setState({ state: SystemState.SHADOW, wsConnected: true });
    mountShell();
    const top = await screen.findByTestId('desk-top');
    expect(within(top).queryByTestId('shell-mood')).toBeNull();
    expect(top.textContent).not.toMatch(/Спокій|Пильную|Працюю|Тривога/);
  });

  it('lost node shows one plain label in the top strip', () => {
    vi.useFakeTimers();
    try {
      useSystemStore.setState({ wsConnected: false });
      mountShell();
      const top = screen.getByTestId('desk-top');
      expect(within(top).queryByTestId('desk-lost')).toBeNull();

      act(() => {
        vi.advanceTimersByTime(6000);
      });

      const lost = within(top).getByTestId('desk-lost');
      expect(lost.textContent).toBe('Не відповідає');
      expect(document.body.textContent).not.toMatch(/пробую знову/);

      act(() => {
        useSystemStore.setState({ wsConnected: true });
      });
      expect(within(top).queryByTestId('desk-lost')).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('content clears the island by 16 px', async () => {
    const { container } = mountShell();
    await screen.findByTestId('desk-top');
    const main = container.querySelector('.shell-root > main');
    expect(main).toBeTruthy();
    expect(main!.className).toContain('pb-[88px]');
    expect(main!.className).not.toContain('pb-20');
  });

  it('uses shared tokens without a separate deskWarm palette', () => {
    const shell = read('src/components/shell/DeskShell.tsx');
    expect(shell).not.toContain('deskWarm.css');
  });
});
