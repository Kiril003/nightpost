import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from '@testing-library/react';
import { MomentsStrip, ringRankOf } from '../components/messenger/MomentsStrip';
import { stripPeople, useMoments, type Moment } from '../services/moments';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat } from '../types/messenger';

const chat = (id: string, peerNodeId: string, circles: string[]): Chat =>
  ({
    id,
    title: id,
    type: 'dm',
    circle: circles[0] ?? 'all',
    circles,
    peerNodeId,
    avatar: '',
    unreadCount: 0,
    pinned: false,
    messages: [],
  }) as unknown as Chat;

const moment = (id: string, fromId: string, atMs: number, extra: Partial<Moment> = {}): Moment =>
  ({
    id,
    fromId,
    fromName: fromId,
    mine: false,
    seen: false,
    atMs,
    text: 'привіт',
    media: [],
    reach: 'contacts',
    circles: [],
    keep: false,
    reactions: {},
    ...extra,
  }) as unknown as Moment;

const CHATS = [chat('work', 'p-work', ['work']), chat('close', 'p-close', ['friends', 'close']), chat('friend', 'p-friend', ['friends'])];

beforeEach(() => {
  useMessengerStore.setState({ typingStatus: {}, drafts: {} } as never);
});

afterEach(() => {
  cleanup();
  useMoments.setState({ list: null } as never);
});

describe('Moments ordered by your rings', () => {
  it('unseen: Close before Friends before Work, even when Work is newest; no ring goes last', () => {
    const rank = ringRankOf(CHATS);
    const list = [
      moment('w', 'p-work', 300),
      moment('s', 'p-stranger', 400),
      moment('f', 'p-friend', 200),
      moment('c', 'p-close', 100),
    ];
    expect(stripPeople(list, rank).map((g) => g[0].fromId)).toEqual(['p-close', 'p-friend', 'p-work', 'p-stranger']);
  });

  it('mine first, seen after unseen whatever the ring', () => {
    const rank = ringRankOf(CHATS);
    const list = [
      moment('c', 'p-close', 100, { seen: true }),
      moment('w', 'p-work', 50),
      moment('me', 'me', 10, { mine: true }),
    ];
    expect(stripPeople(list, rank).map((g) => g[0].id)).toEqual(['me', 'w', 'c']);
  });

  it('the strip renders in ring order', () => {
    useMoments.setState({ list: [moment('w', 'p-work', 300), moment('c', 'p-close', 100)] } as never);
    const { container } = render(<MomentsStrip variant="stack" chats={CHATS} onOpenChat={vi.fn()} />);
    const ids = [...container.querySelectorAll('[data-moment-circle]')].map((n) => n.getAttribute('data-moment-circle'));
    expect(ids).toEqual(['c', 'w']);
  });
});
