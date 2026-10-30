/**
 * Вузол відповів не тієї форми — оболонка ПК не падає в «Щось зламалось»,
 * а показує чесний порожній стан.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SystemState } from '@shared/types';
import { DeskShell } from '../components/shell/DeskShell';
import { DeskToday, asksOf } from '../components/shell/DeskToday';
import { agentStatusOf, calendarEventsOf } from '../components/shell/useShellSignals';
import { useSystemStore } from '../stores/systemStore';
import { useMessengerStore } from '../stores/messengerStore';
import { useUIStore } from '../stores/uiStore';
import * as api from '../services/api';
import { messengerApi } from '../services/messengerApi';

function mount(route: string) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[route]}>
        <Routes>
          <Route element={<DeskShell />}>
            <Route path="/day" element={<DeskToday />} />
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
    if (path === '/agent/status') return { foreground: null, background: 'idle' } as never;
    if (path === '/tools/calendar') return { events: { e1: 'x' } } as never;
    return {} as never;
  });
  vi.spyOn(api.agentAskApi, 'waiting').mockResolvedValue({ questions: 'none' } as never);
  vi.spyOn(api.symbioteApi, 'state').mockResolvedValue({ bodies: [], alive: 0, total: 0 });
  vi.spyOn(api.chatApi, 'getSessions').mockResolvedValue({ sessions: null } as never);
  vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([]);
});

afterEach(() => vi.restoreAllMocks());

describe('оболонка ПК і криві відповіді вузла', () => {
  it('нормалізатори повертають порожнє, а не кидають', () => {
    expect(agentStatusOf(null)).toEqual({ foreground: { active: false, goal: null }, background: { active: false, goal: null } });
    expect(agentStatusOf({ foreground: { active: 'yes', goal: 7 } }).foreground).toEqual({ active: false, goal: null });
    expect(agentStatusOf({ background: { active: true, goal: 'Звіт' } }).background).toEqual({ active: true, goal: 'Звіт' });
    expect(calendarEventsOf({ events: 'x' })).toEqual([]);
    expect(calendarEventsOf({ events: [null, { id: 'a', title: 'A', start_at: 's', end_at: 'e' }, { id: 'b' }] }).map((e) => e.id)).toEqual(['a']);
    expect(asksOf({ questions: 'none' })).toEqual([]);
    expect(asksOf({ questions: [{ task_id: 't', goal: 'g', question: 'q' }, { task_id: 1 }] })).toHaveLength(1);
  });

  it('День із кривими відповідями — чесно порожній, без падіння', async () => {
    const errors = vi.spyOn(console, 'error').mockImplementation(() => {});
    mount('/day');
    expect(await screen.findByTestId('desk-today-empty')).toBeTruthy();
    await waitFor(() => expect(api.request).toHaveBeenCalledWith('GET', '/tools/calendar'));
    await new Promise((r) => setTimeout(r, 50));
    expect(screen.getByTestId('desk-top')).toBeTruthy();
    expect(screen.getByTestId('desk-today-empty')).toBeTruthy();
    expect(errors.mock.calls.flat().join(' ')).not.toMatch(/TypeError|is not a function|Cannot read/);
  });
});
