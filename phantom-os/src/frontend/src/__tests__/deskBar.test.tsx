import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SystemState } from '@shared/types';
import { DeskShell } from '../components/shell/DeskShell';
import { nowOf } from '../components/shell/DeskFlanks';
import MessengerLayout from '../layouts/MessengerLayout';
import { useSystemStore } from '../stores/systemStore';
import { useMessengerStore } from '../stores/messengerStore';
import { useUIStore } from '../stores/uiStore';
import * as api from '../services/api';
import { messengerApi } from '../services/messengerApi';

function mountShell(route = '/') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[route]}>
        <Routes>
          <Route element={<DeskShell />}>
            <Route path="/" element={<MessengerLayout />} />
            <Route path="/messenger" element={<MessengerLayout />} />
            <Route path="/hq" element={<div>hq-screen</div>} />
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
  useMessengerStore.setState({ chats: [], activeChatId: '', hydrated: false, isCreateChatModalOpen: false });
  vi.spyOn(api, 'request').mockImplementation(async (_m: string, path: string) => {
    if (path === '/agent/status') return { foreground: { active: false, goal: null }, background: { active: false, goal: null } } as never;
    if (path === '/tools/calendar') return { events: [] } as never;
    return {} as never;
  });
  vi.spyOn(api.symbioteApi, 'state').mockResolvedValue({ bodies: [], alive: 0, total: 0 });
  vi.spyOn(api.chatApi, 'getSessions').mockResolvedValue({ sessions: [], total: 0 });
  vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([]);
});

afterEach(() => vi.restoreAllMocks());

describe('deskBar', () => {
  it('Now shows only a call, an Engine run or an event within the hour', () => {
    expect(
      nowOf({
        call: { state: 'active', peer: { displayName: 'Марта' } },
        agentBusy: true,
        agentGoal: 'Compare venues',
        nextEvent: { title: 'Demo', inMinutes: 10 },
      }),
    ).toEqual({ kind: 'call', title: 'Марта', detail: 'Дзвінок' });

    expect(
      nowOf({
        call: { state: 'idle', peer: null },
        agentBusy: true,
        agentGoal: 'Compare venues',
        nextEvent: { title: 'Demo', inMinutes: 10 },
      }),
    ).toEqual({ kind: 'run', title: 'Compare venues', detail: 'Працює' });

    const ev = nowOf({
      call: { state: 'idle', peer: null },
      agentBusy: false,
      agentGoal: null,
      nextEvent: { title: 'Demo', inMinutes: 30 },
    });
    expect(ev?.kind).toBe('event');
    expect(ev?.title).toBe('Demo');
    expect(ev?.detail).toContain('30');

    expect(
      nowOf({
        call: { state: 'idle', peer: null },
        agentBusy: false,
        agentGoal: null,
        nextEvent: { title: 'Later', inMinutes: 90 },
      }),
    ).toBeNull();

    expect(
      nowOf({
        call: { state: 'ringing', peer: { displayName: 'Марта' } },
        agentBusy: false,
        agentGoal: null,
        nextEvent: null,
      }),
    ).toBeNull();
  });

  it('Now pill renders from the shell signals', async () => {
    vi.spyOn(api, 'request').mockImplementation(async (_m: string, path: string) => {
      if (path === '/agent/status') return { foreground: { active: true, goal: 'Compare venues' }, background: { active: false, goal: null } } as never;
      if (path === '/tools/calendar') return { events: [] } as never;
      return {} as never;
    });
    mountShell();
    const pill = await screen.findByTestId('desk-now');
    expect(pill).toHaveAttribute('data-kind', 'run');
    expect(pill.textContent).toContain('Compare venues');
    fireEvent.click(pill);
    expect(await screen.findByText('hq-screen')).toBeTruthy();
  });

  it('Phone reflects the real pairing state', async () => {
    const basePhone: api.BodyPresence = {
      body_id: 'p1',
      kind: 'phone',
      name: 'A52',
      online: false,
      fresh: false,
      age_s: 900,
      battery_pct: null,
      charging: null,
      lat: null,
      lon: null,
      accuracy_m: null,
      capabilities: [],
    };
    vi.spyOn(api, 'request').mockImplementation(async (_m: string, path: string) => {
      if (path === '/agent/status') return { foreground: { active: false, goal: null }, background: { active: false, goal: null } } as never;
      if (path === '/tools/calendar') return { events: [] } as never;
      return {} as never;
    });
    vi.spyOn(api.symbioteApi, 'state').mockResolvedValue({ bodies: [basePhone], alive: 0, total: 1 });
    const first = mountShell();
    const phoneOffline = await screen.findByTestId('desk-phone');
    expect(phoneOffline).toHaveAttribute('data-online', 'false');
    expect(phoneOffline.textContent).toMatch(/Не на зв['ʼ]язку/);
    first.unmount();

    vi.spyOn(api, 'request').mockImplementation(async (_m: string, path: string) => {
      if (path === '/agent/status') return { foreground: { active: false, goal: null }, background: { active: false, goal: null } } as never;
      if (path === '/tools/calendar') return { events: [] } as never;
      return {} as never;
    });
    vi.spyOn(api.symbioteApi, 'state').mockResolvedValue({ bodies: [{ ...basePhone, online: true }], alive: 1, total: 1 });
    mountShell();
    const phoneOnline = await screen.findByTestId('desk-phone');
    expect(phoneOnline).toHaveAttribute('data-online', 'true');
    expect(phoneOnline.textContent).toMatch(/На зв['ʼ]язку/);

    const newBtn = screen.getByTestId('desk-new');
    fireEvent.click(newBtn);
    expect(useMessengerStore.getState().isCreateChatModalOpen).toBe(true);
  });

  it('flanks are empty with no data', async () => {
    mountShell();
    await screen.findByTestId('phantom-dock');
    expect(screen.queryByTestId('desk-now')).toBeNull();
    expect(screen.queryByTestId('desk-flank-right')).toBeNull();
  });
});
