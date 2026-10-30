import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, fireEvent, waitFor, act } from '@testing-library/react';

const bridge = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('../services/api', async (orig) => {
  const real = await orig<typeof import('../services/api')>();
  return { ...real, request: bridge.request };
});

import { MomentComposer } from '../components/messenger/MomentsStrip';
import { MomentsProfile } from '../components/messenger/MomentsProfile';
import { MomentsArchive } from '../components/messenger/MomentsArchive';
import { MomentsSettings } from '../components/messenger/MomentsSettings';
import { useArchive, archived } from '../services/momentsArchive';
import { useMoments } from '../services/moments';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat } from '../types/messenger';

const chat = (id: string, circle: Chat['circle']): Chat => ({
  id, title: id, type: 'dm', circle, avatar: '', unreadCount: 0, pinned: false, messages: [],
  lastSnippet: 'hi', lastAt: new Date().toISOString(), peerNodeId: id,
});
const CHATS = [chat('a', 'close'), chat('b', 'friends')];

beforeEach(() => {
  localStorage.clear();
  useArchive.setState({ items: [] });
  useMoments.setState({ list: [], prefs: null });
  useMessengerStore.setState({ chats: CHATS } as never);
  bridge.request.mockReset().mockImplementation((method: string, path: string, body?: unknown) => {
    if (path === '/messenger/moments/prefs') return Promise.resolve({ audience: { kind: 'contacts' }, to: [], keep: false, everyone: false, muted: [] });
    if (method === 'GET') return Promise.resolve([]);
    return Promise.resolve(body ?? {});
  });
});

describe('moments-r', () => {
  it('compose: default audience is the close circle, chip opens the sheet', async () => {
    render(<MomentComposer file={null} people={[{ id: 'a', name: 'a', chatId: 'a' }, { id: 'b', name: 'b', chatId: 'b' }]} chats={CHATS} onClose={vi.fn()} />);
    await act(async () => {});
    expect(document.querySelector('[data-moment-audience-label]')!.textContent).toBe('Близькі');
    expect(document.querySelector('[data-moment-audience]')).toBeNull();
    fireEvent.click(document.querySelector('[data-moment-audience-chip]')!);
    expect(document.querySelector('[data-moment-audience]')).not.toBeNull();
  });

  it('profile reaches compose, archive and settings', async () => {
    render(<MomentsProfile onClose={vi.fn()} />);
    fireEvent.click(document.querySelector('[data-profile-compose]')!);
    expect(document.querySelector('[data-moment-composer]')).not.toBeNull();
    fireEvent.click(document.querySelector('[data-moment-cancel]')!);
    fireEvent.click(document.querySelector('[data-profile-archive]')!);
    expect(document.querySelector('[data-moments-archive]')).not.toBeNull();
    fireEvent.click(document.querySelector('[data-profile-settings]')!);
    expect(document.querySelector('[data-moments-settings]')).not.toBeNull();
  });

  it('archive: only older than a day or manual; forget removes', async () => {
    const old = { id: 'o', atMs: Date.now() - 3 * 86_400_000, text: 'old', reach: 'me' as const, circles: [], keep: false, kind: 'text' as const, manual: false };
    const fresh = { ...old, id: 'f', atMs: Date.now() - 1000 };
    expect(archived([old, fresh]).map((x) => x.id)).toEqual(['o']);
    localStorage.setItem('nightpost.moments.archive', JSON.stringify([old]));
    render(<MomentsArchive onClose={vi.fn()} />);
    fireEvent.click(document.querySelector('[data-archive-tile="o"]')!);
    fireEvent.click(document.querySelector('[data-archive-forget]')!);
    await waitFor(() => expect(useArchive.getState().items).toEqual([]));
  });

  it('settings: default audience is saved through the bridge', async () => {
    render(<MomentsSettings onClose={vi.fn()} />);
    await act(async () => {});
    fireEvent.click(document.querySelector('[data-testid="set-aud-me"]')!);
    await waitFor(() => expect(bridge.request).toHaveBeenCalledWith('PUT', '/messenger/moments/prefs', expect.objectContaining({ audience: { kind: 'me' } })));
  });
});
