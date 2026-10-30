/**
 * list-fix, досяжність: справжній вхід Sidebar → PhoneChatList. Моменти
 * приходять з вузла (GET /messenger/moments, сирий JSON) через хост
 * MomentsStrip — і стають смугою; довгий дотик по рядку вмикає вибір.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, waitFor } from '@testing-library/react';
import { Sidebar } from '../components/messenger/Sidebar';
import { useMoments } from '../services/moments';
import { useMessengerStore } from '../stores/messengerStore';
import { useSettingsStore } from '../stores/settingsStore';
import type { Chat, UserProfile } from '../types/messenger';

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;
const NOW = Date.now();

const chat = (id: string, title: string): Chat =>
  ({
    id,
    title,
    type: 'dm',
    circle: 'friends',
    peerNodeId: `p_${id}`,
    messages: [],
    unreadCount: 0,
    lastSnippet: 'привіт',
    lastAt: new Date(NOW - 60_000).toISOString(),
  }) as unknown as Chat;

const CHATS = [chat('olia', 'Оля Коваль'), chat('yana', 'Яна Ліщук')];

const RAW = [
  { id: 'm1', from_id: 'p_olia', from_name: 'Оля', mine: false, at_ms: NOW - 60_000, until_ms: NOW + 3_600_000, text: 'Ринок о восьмій', seen: false, audience: { kind: 'contacts' } },
  { id: 'm2', from_id: 'p_yana', from_name: 'Яна', mine: false, at_ms: NOW - 120_000, until_ms: NOW + 3_600_000, text: 'Маяк', seen: true, audience: { kind: 'contacts' } },
  { id: 'gone', from_id: 'p_yana', from_name: 'Яна', mine: false, at_ms: NOW - 90_000_000, until_ms: NOW - 1_000, text: 'вчора', seen: false, audience: { kind: 'contacts' } },
];

const realFetch = globalThis.fetch;

beforeEach(() => {
  localStorage.clear();
  useSettingsStore.setState({ language: 'uk' } as never);
  useMessengerStore.setState({ typingStatus: {}, drafts: {} } as never);
  useMoments.setState({ list: null, unavailable: false } as never);
  globalThis.fetch = vi.fn(async (url: RequestInfo | URL) => {
    const path = String(url);
    if (path.endsWith('/messenger/moments')) return new Response(JSON.stringify(RAW), { status: 200 });
    if (path.endsWith('/messenger/queue/status')) return new Response(JSON.stringify({ queued: 0 }), { status: 200 });
    return new Response(JSON.stringify({ detail: 'немає', code: 'NOT_FOUND' }), { status: 404 });
  }) as typeof fetch;
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  globalThis.fetch = realFetch;
  useMoments.setState({ list: null } as never);
});

const mount = () =>
  render(<Sidebar chats={CHATS} activeChatId="" onSelectChat={vi.fn()} currentUser={USER} onNewChat={vi.fn()} />);

describe('list-fix: досяжність зі справжнього входу', () => {
  it('Sidebar → PhoneChatList → «Моменти»: моменти з вузла стають рядом історій (прострочений не потрапляє)', async () => {
    const { container } = mount();
    await waitFor(() => expect(container.querySelector('[data-pane-dot]')).toBeTruthy());
    expect(container.querySelector('[data-list-moments]')).toBeNull();
    fireEvent.click(container.querySelector('[data-pane-tab="moments"]')!);
    const strip = await waitFor(() => {
      const el = container.querySelector('[data-list-moments]');
      expect(el).toBeTruthy();
      return el as HTMLElement;
    });
    expect([...strip.querySelectorAll('[data-list-moment]')].map((el) => el.getAttribute('data-list-moment'))).toEqual(['me', 'p_olia', 'p_yana']);
    expect(strip.querySelector('[data-list-moment="p_olia"]')!.getAttribute('data-seen')).toBe('false');
    expect(strip.querySelector('[data-list-moment="p_yana"]')!.getAttribute('data-seen')).toBe('true');
  });

  it('Sidebar → PhoneChatList: довгий дотик по рядку вмикає вибір, а не аркуш дій', async () => {
    const { container } = mount();
    await waitFor(() => expect(container.querySelector('[data-chat-row="olia"]')).toBeTruthy());
    vi.useFakeTimers();
    const row = container.querySelector('[data-chat-row="olia"]') as HTMLElement;
    fireEvent.touchStart(row, { touches: [{ clientX: 40, clientY: 40 }] });
    act(() => {
      vi.advanceTimersByTime(520);
    });
    fireEvent.touchEnd(row);
    expect(container.querySelector('[data-list-select-bar] [data-sel-count]')!.textContent).toBe('Вибрано: 1');
    expect(document.querySelector('[data-testid="chat-actions-sheet"], [role="dialog"]')).toBeNull();
  });
});
