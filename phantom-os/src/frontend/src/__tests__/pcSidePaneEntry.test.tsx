/**
 * Колонка ПК з реальних входів: кнопка «Мапа» на рейці, «Сьогодні» на рейці,
 * назва розмови в шапці. Тест pcSidePane.test.tsx вмикає deskPane зі стору;
 * тут людина тисне те, що тисне людина.
 */
import { describe, it, expect, vi, beforeAll, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, within, waitFor, act } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SystemState } from '@shared/types';
import { DeskShell } from '../components/shell/DeskShell';
import MessengerLayout from '../layouts/MessengerLayout';
import { useSystemStore } from '../stores/systemStore';
import { useMessengerStore } from '../stores/messengerStore';
import { useUIStore } from '../stores/uiStore';
import * as api from '../services/api';
import { messengerApi } from '../services/messengerApi';
import { phantomSelfApi } from '../services/phantomSelfApi';
import type { Chat, LocationData } from '../types/messenger';

const chat = (id: string, title: string, over: Partial<Chat> = {}): Chat => ({
  id,
  title,
  avatar: '',
  type: 'direct',
  circle: 'friends',
  peerNodeId: `peer-${id}`,
  unreadCount: 0,
  lastSnippet: 'Привіт',
  lastAt: '2025-10-10T12:00:00.000Z',
  messages: [],
  ...over,
});

const PLACE: LocationData = {
  name: 'Café X',
  category: 'cafe',
  address: 'Головна 1',
  distance: '',
  walkingTime: '',
  coords: { lat: 49.83, lng: 18.28 },
};

function mountShell() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route element={<DeskShell />}>
            <Route path="/" element={<MessengerLayout />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('pcSidePaneEntry: колонка відкривається й закривається тим, що тисне людина', () => {
  beforeAll(() => {
    Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
    Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
  });

  beforeEach(() => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: 1440 });
    useSystemStore.setState({ authenticated: true, state: SystemState.SHADOW, context: null, wsConnected: true });
    useUIStore.setState({ deskPane: null, toasts: [] });
    useMessengerStore.setState({
      chats: [chat('c1', 'Олена'), chat('g1', 'Команда', { type: 'group', circle: 'work', peerNodeId: undefined })],
      activeChatId: 'c1',
      hydrated: true,
      activeLocationData: null,
      hydrateFromNode: async () => {},
    });
    vi.spyOn(api, 'request').mockImplementation(async (_m: string, path: string) => {
      if (path === '/agent/status') return { foreground: { active: false, goal: null }, background: { active: false, goal: null } } as never;
      if (path === '/tools/calendar') return { events: [] } as never;
      return {} as never;
    });
    vi.spyOn(api.chatApi, 'getSessions').mockResolvedValue({ sessions: [], total: 0 });
    vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([]);
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
  });

  afterEach(() => vi.restoreAllMocks());

  it('«Мапа» на рейці відкриває колонку Мапа поруч із розмовою, друге натискання закриває; «Сьогодні» теж закриває й скидає місце', async () => {
    mountShell();
    const dock = await screen.findByTestId('phantom-dock');
    const mapBtn = within(dock).getByRole('button', { name: 'Мапа' });

    fireEvent.click(mapBtn);
    const aside = await screen.findByTestId('desk-side-pane', undefined, { timeout: 10_000 });
    expect(aside).toHaveAttribute('data-pane', 'map');
    expect(useMessengerStore.getState().activeChatId).toBe('c1');

    fireEvent.click(mapBtn);
    expect(screen.queryByTestId('desk-side-pane')).toBeNull();

    fireEvent.click(mapBtn);
    await screen.findByTestId('desk-side-pane');
    act(() => {
      useMessengerStore.getState().openLocationSheet(PLACE);
    });
    fireEvent.click(within(dock).getByRole('button', { name: 'Сьогодні' }));
    await waitFor(() => expect(screen.queryByTestId('desk-side-pane')).toBeNull());
    expect(useMessengerStore.getState().activeLocationData).toBeNull();
  });

  it('назва групи в шапці відкриває Інфо групи в колонці, а не заслінку на все вікно', async () => {
    useMessengerStore.setState({ activeChatId: 'g1' });
    mountShell();
    const title = await waitFor(() => {
      const el = document.querySelector<HTMLElement>('[data-chat-header-title]');
      if (!el) throw new Error('no header title');
      return el;
    });
    fireEvent.click(title);
    const aside = await screen.findByTestId('desk-side-pane', undefined, { timeout: 10_000 });
    expect(aside).toHaveAttribute('data-pane', 'info');
    const sheet = await within(aside).findByTestId('group-profile');
    expect(aside).toContainElement(sheet);
    expect(document.querySelector('.f-scrim')).toBeNull();
    expect(screen.getByTestId('phantom-dock')).toBeInTheDocument();
  });
});
