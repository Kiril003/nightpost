import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../services/circlesApi', async (orig) => {
  const real = await orig<typeof import('../services/circlesApi')>();
  return {
    ...real,
    listCircles: vi.fn(async () => []),
    listCirclesWithMeta: vi.fn(async () => ({ circles: [], maxCustom: 8 })),
    createCircle: vi.fn(),
    deleteCircle: vi.fn(),
    joinCircle: vi.fn(),
    leaveCircle: vi.fn(),
  };
});

import * as circlesApi from '../services/circlesApi';
import { messengerApi } from '../services/messengerApi';
import { primaryOf, useCirclesStore } from '../stores/circlesStore';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat } from '../types/messenger';

const chat = (id: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id,
    title: 'Olena',
    type: 'dm',
    circle: 'all',
    avatar: '',
    unreadCount: 0,
    pinned: false,
    messages: [],
    ...extra,
  }) as unknown as Chat;

beforeEach(() => {
  useCirclesStore.setState({ circles: [], maxCustom: 8 });
  useMessengerStore.setState({ chats: [chat('c1', { circle: 'friends', circles: ['friends'] })] } as never);
});

afterEach(() => {
  vi.restoreAllMocks();
  useMessengerStore.setState({ chats: [] } as never);
});

describe('circlesStore', () => {
  it('primaryOf orders built-ins before custom and returns all when empty', () => {
    expect(primaryOf([])).toBe('all');
    expect(primaryOf(['u:custom', 'work', 'close'])).toBe('close');
    expect(primaryOf(['u:alpha', 'u:beta'])).toBe('u:alpha');
    expect(primaryOf(['social', 'personal'])).toBe('personal');
  });

  it('a toggle on writes circles and the primary into messengerStore', async () => {
    vi.mocked(circlesApi.joinCircle).mockResolvedValueOnce(['friends', 'close', 'u:custom']);

    const ok = await useCirclesStore.getState().toggle('c1', 'close', true);
    expect(ok).toBe(true);
    expect(circlesApi.joinCircle).toHaveBeenCalledWith('close', 'c1');

    const updated = useMessengerStore.getState().chats.find((c) => c.id === 'c1');
    expect(updated?.circles).toEqual(['friends', 'close', 'u:custom']);
    expect(updated?.circle).toBe('close');
  });

  it('a toggle off to empty gives all', async () => {
    vi.mocked(circlesApi.leaveCircle).mockResolvedValueOnce([]);

    const ok = await useCirclesStore.getState().toggle('c1', 'friends', false);
    expect(ok).toBe(true);
    expect(circlesApi.leaveCircle).toHaveBeenCalledWith('friends', 'c1');

    const updated = useMessengerStore.getState().chats.find((c) => c.id === 'c1');
    expect(updated?.circles).toEqual([]);
    expect(updated?.circle).toBe('all');
  });

  it('a conversation refresh brings the circles list from the phone', async () => {
    vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([
      { id: 'c1', title: 'Olena', kind: 'dm', circle: 'close', circles: ['close', 'work'], pinned: false },
    ] as never);

    await useMessengerStore.getState().refreshConversations();

    const updated = useMessengerStore.getState().chats.find((c) => c.id === 'c1');
    expect(updated?.circle).toBe('close');
    expect(updated?.circles).toEqual(['close', 'work']);
  });
});
