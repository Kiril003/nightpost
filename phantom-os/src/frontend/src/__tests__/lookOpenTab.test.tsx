import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';

vi.mock('../services/messengerApi', async (orig) => {
  const real = await orig<typeof import('../services/messengerApi')>();
  return {
    ...real,
    messengerApi: {
      ...real.messengerApi,
      pulse: vi.fn(() => new Promise(() => {})),
      peerProfiles: vi.fn(() => new Promise(() => {})),
    },
  };
});

import { PhoneChatList } from '../components/messenger/PhoneChatList';
import type { Chat, UserProfile } from '../types/messenger';

const USER = { id: 'me', name: 'Me', avatar: '' } as unknown as UserProfile;
const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id, title, type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false, peerNodeId: `n_${id}`,
    messages: [], lastSnippet: 'hi', lastAt: new Date().toISOString(), ...extra,
  }) as unknown as Chat;
const CHATS = [chat('c', 'Ada', { circle: 'close' }), chat('n', 'Ben'), ...['Cy', 'Di', 'Ed', 'Flo'].map((n) => chat(n, n))];

type Door = { __phantomOpenTab?: (key: string) => boolean };
const door = () => (window as unknown as Door).__phantomOpenTab;
const selected = () => document.querySelector('[data-filter-tab][aria-selected="true"]')?.getAttribute('data-filter-tab');

afterEach(cleanup);

describe('W5: __phantomOpenTab opens a circle page from You', () => {
  const mount = () =>
    render(
      <PhoneChatList chats={CHATS} currentUser={USER} onSelectChat={vi.fn()} onNewChat={vi.fn()} onOpenChatActions={vi.fn()} />,
    );

  it('maps c:<circle> from You onto its filter and says opened', () => {
    mount();
    expect(selected()).toBe('all');
    let ok: boolean | undefined;
    act(() => {
      ok = door()?.('c:close');
    });
    expect(ok).toBe(true);
    expect(selected()).toBe('close');
    act(() => {
      ok = door()?.('c:friends');
    });
    expect(ok).toBe(true);
    expect(selected()).toBe('friends');
  });

  it('refuses a circle without a filter and stays on All', () => {
    mount();
    let ok: boolean | undefined;
    act(() => {
      ok = door()?.('c:work');
    });
    expect(ok).toBe(false);
    expect(selected()).toBe('all');
  });

  it('is gone after the list unmounts', () => {
    const view = mount();
    view.unmount();
    expect(door()).toBeUndefined();
  });
});
