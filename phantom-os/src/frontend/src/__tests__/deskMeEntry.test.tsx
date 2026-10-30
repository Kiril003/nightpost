/**
 * «Я» на ПК веде в Налаштування (b73774376), а екран акаунта (MeScreen) —
 * з рядка акаунта вгорі Налаштувань. Рядок є завжди: і без жодної розмови,
 * коли шапки розмови з меню «⋯» ще немає.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, within, waitFor } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SystemState } from '@shared/types';
import { DeskShell } from '../components/shell/DeskShell';
import { DeskSettings } from '../components/shell/DeskSettings';
import MessengerLayout from '../layouts/MessengerLayout';
import { useSystemStore } from '../stores/systemStore';
import { useMessengerStore } from '../stores/messengerStore';
import { useSettingsStore } from '../stores/settingsStore';
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
            <Route path="/settings" element={<DeskSettings />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  useSystemStore.setState({ authenticated: true, state: SystemState.SHADOW, context: null, wsConnected: true });
  useMessengerStore.setState((s) => ({
    chats: [],
    activeChatId: '',
    hydrated: false,
    isSettingsModalOpen: false,
    currentUser: { ...s.currentUser, id: 'node-me', name: 'Olena', avatar: '' },
  }));
  useSettingsStore.setState({ values: {}, loaded: true });
  vi.spyOn(api, 'request').mockImplementation(async (_m: string, path: string) => {
    if (path === '/agent/status') return { foreground: { active: false, goal: null }, background: { active: false, goal: null } } as never;
    if (path === '/tools/calendar') return { events: [] } as never;
    return {} as never;
  });
  vi.spyOn(api.chatApi, 'getSessions').mockResolvedValue({ sessions: [], total: 0 });
  vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'directoryMe').mockResolvedValue({ published: true, handle: 'olena' } as never);
});

afterEach(() => vi.restoreAllMocks());

describe('вхід в екран акаунта на ПК', () => {
  it('без розмов: «Я» → рядок акаунта → MeScreen', async () => {
    mountShell();
    const dock = await screen.findByTestId('phantom-dock');
    fireEvent.click(within(dock).getByRole('button', { name: 'Я' }));
    const row = await screen.findByTestId('desk-account');
    expect(row.textContent).toContain('Olena');
    await within(row).findByText('@olena');
    fireEvent.click(row);
    expect(await screen.findByTestId('me-screen', {}, { timeout: 5000 })).toBeTruthy();
  });

  it('рядок перший під заголовком, 72 px, без бурштину', async () => {
    mountShell('/settings');
    const pane = await screen.findByTestId('desk-settings');
    const row = within(pane).getByTestId('desk-account');
    expect(pane.children[1]).toBe(row);
    expect(row.className).toContain('min-h-[72px]');
    expect(row.outerHTML).not.toMatch(/accent/);
  });

  it('ніка немає у каталозі — другого рядка немає, як і в MeScreen', async () => {
    const dir = vi.spyOn(messengerApi, 'directoryMe').mockResolvedValue({ published: false, handle: null } as never);
    mountShell('/settings');
    const row = await screen.findByTestId('desk-account');
    await waitFor(() => expect(dir).toHaveBeenCalled());
    await new Promise((r) => setTimeout(r, 0));
    expect(row.textContent).toContain('Olena');
    expect(within(row).queryByText(/^@/)).toBeNull();
  });
});
