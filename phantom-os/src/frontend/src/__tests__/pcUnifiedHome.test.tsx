import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SystemState } from '@shared/types';
import DeskRouter from '../app/DeskRouter';
import { useSystemStore } from '../stores/systemStore';
import { useSettingsStore } from '../stores/settingsStore';
import { useMessengerStore } from '../stores/messengerStore';
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

describe('PC unified home (/) and Nightpost assistant', () => {
  const hadBridge = 'PhantomBridge' in window;
  const prevBridge = (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;

  beforeEach(() => {
    vi.stubGlobal('__PHONE_BUILD__', false);
    delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({ ok: true, status: 200, json: async () => ({ unclaimed: false }) })),
    );

    useSystemStore.setState({
      authenticated: true,
      state: SystemState.SHADOW,
      context: null,
      wsConnected: true,
    });
    useSettingsStore.setState({ values: {}, loaded: true });
    useMessengerStore.setState({
      chats: [],
      activeChatId: '',
      hydrated: false,
    });

    vi.spyOn(api.authApi, 'bootstrap').mockResolvedValue({ unclaimed: false });
    vi.spyOn(api, 'request').mockImplementation(async (_m: string, path: string) => {
      if (path === '/agent/status') {
        return { foreground: { active: false, goal: null }, background: { active: false, goal: null } } as never;
      }
      if (path === '/tools/calendar') return { events: [] } as never;
      return {} as never;
    });
    vi.spyOn(api.chatApi, 'getSessions').mockResolvedValue({ sessions: [], total: 0 });
    vi.spyOn(messengerApi, 'identity').mockResolvedValue({
      node_id: 'node_86a15538f0c24e91',
      bundle: { name: 'Олена' },
      compact: 'phantom1node86a15538',
      max_attachment_bytes: 10 * 1024 * 1024,
    });
    vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([PEER_CONV, NODE_CONV]);
    vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'queueStatus').mockResolvedValue({ queued: 0 } as never);
    vi.spyOn(messengerApi, 'markVisible').mockResolvedValue(undefined as never);
  });

  afterEach(() => {
    if (hadBridge) {
      (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = prevBridge;
    } else {
      delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
    }
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('mounts chat-list and talk-root at /, reveals search-ask when typing in search, and opens pinned node chat when clicking Nightpost rail button', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { container } = render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={['/']}>
          <DeskRouter />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    const chatList = await screen.findByTestId('chat-list');
    const talkRoot = await screen.findByTestId('talk-root');
    expect(chatList).toBeTruthy();
    expect(talkRoot).toBeTruthy();
    expect(useMessengerStore.getState().activeChatId).toBe('conv_marta');

    // Open search in PhoneChatList on PC and verify unified search/ask capsule
    const searchPill = container.querySelector('[data-search-pill]') as HTMLElement;
    expect(searchPill).not.toBeNull();
    fireEvent.click(searchPill);
    expect(screen.getByTestId('search-ask-voice')).toBeTruthy();

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'план на завтра' } });
    const searchAsk = await screen.findByTestId('search-ask');
    expect(searchAsk.textContent).toContain('план на завтра');

    // Поза месенджером розмова закривається, інакше нові листи читались би самі
    const dock = screen.getByTestId('phantom-dock');
    expect(within(dock).queryByRole('button', { name: 'Головна' })).toBeNull();
    fireEvent.click(within(dock).getByRole('button', { name: 'Я' }));
    await screen.findByRole('heading', { name: 'Налаштування' });
    await waitFor(() => expect(useMessengerStore.getState().activeChatId).toBe(''));

    // Click Nightpost brand button — selects pinned node chat and navigates back to /
    fireEvent.click(screen.getByTestId('desk-brand'));
    await waitFor(() => {
      expect(useMessengerStore.getState().activeChatId).toBe('peer_phantom_agent');
    });
    expect(await screen.findByTestId('chat-list')).toBeTruthy();
    expect(await screen.findByTestId('talk-root')).toBeTruthy();
  });
});
