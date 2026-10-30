import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';
import { MomentsStrip } from '../components/messenger/MomentsStrip';
import { useMoments, type Moment } from '../services/moments';
import { useMessengerStore } from '../stores/messengerStore';
import { useFolders } from '../stores/folderStore';
import type { Chat } from '../types/messenger';


const chat = (id: string, title: string, peerNodeId = id): Chat =>
  ({
    id,
    title,
    type: 'dm',
    circle: 'all',
    peerNodeId,
    avatar: '',
    unreadCount: 0,
    pinned: false,
    messages: [],
    lastSnippet: 'привіт',
    lastAt: new Date().toISOString(),
  }) as unknown as Chat;

const moment = (id: string, fromId: string, extra: Partial<Moment> = {}): Moment => ({
  id,
  fromId,
  fromName: fromId,
  mine: false,
  atMs: Date.now() - 1_000,
  editedMs: 0,
  untilMs: Date.now() + 3_600_000,
  keep: false,
  reach: 'contacts' as Moment['reach'],
  circles: [],
  text: 'привіт',
  seen: false,
  toCount: 1,
  media: [],
  viewers: [],
  reactions: {},
  myReaction: '',
  ...extra,
});

const setMoments = (list: Moment[] | null) => {
  useMoments.setState({ list } as never);
};

beforeEach(() => {
  useMessengerStore.setState({ typingStatus: {}, drafts: {} } as never);
  useFolders.setState({ folders: [] } as never);
  setMoments([]);
});

afterEach(() => {
  cleanup();
  setMoments(null);
  delete (window as unknown as { __phantomComposeMoment?: () => boolean }).__phantomComposeMoment;
  delete (window as unknown as { __phantomOpenMoment?: (id: string) => boolean }).__phantomOpenMoment;
});

describe('profile highlight door: __phantomOpenMoment', () => {
  const door = () => (window as unknown as { __phantomOpenMoment?: (id: string) => boolean }).__phantomOpenMoment;

  it('opens my own moment in the existing viewer', () => {
    const chats = [chat('c1', 'Оксана', 'p1')];
    setMoments([moment('mine-1', 'me', { mine: true, seen: true }), moment('m2', 'p1')]);
    const { container } = render(<MomentsStrip chats={chats} onOpenChat={vi.fn()} />);
    expect(typeof door()).toBe('function');
    let opened = false;
    act(() => {
      opened = door()!('mine-1');
    });
    expect(opened).toBe(true);
    expect(container.ownerDocument.querySelector('[data-moment-viewer="mine-1"]')).toBeTruthy();
  });

  it('opens through the host when the chats row is mounted after it', () => {
    const chats = [chat('c1', 'Оксана', 'p1')];
    setMoments([moment('m2', 'p1')]);
    const { container } = render(
      <>
        <MomentsStrip chats={chats} onOpenChat={vi.fn()} variant="host" />
        <MomentsStrip chats={chats} onOpenChat={vi.fn()} variant="row" />
      </>,
    );
    act(() => {
      door()!('m2');
    });
    expect(container.ownerDocument.querySelector('[data-moment-viewer="m2"]')).toBeTruthy();
  });

  it('refuses an id it does not hold and unregisters on unmount', () => {
    setMoments([moment('m2', 'p1')]);
    const { unmount } = render(<MomentsStrip chats={[chat('c1', 'Оксана', 'p1')]} onOpenChat={vi.fn()} />);
    expect(door()!('gone')).toBe(false);
    expect(document.querySelector('[data-moment-viewer]')).toBeNull();
    unmount();
    expect(door()).toBeUndefined();
  });

  it('a tap that lands before the list loads opens the moment once it arrives', () => {
    setMoments(null);
    render(<MomentsStrip chats={[chat('c1', 'Оксана', 'p1')]} onOpenChat={vi.fn()} />);
    expect(door()!('mine-1')).toBe(true);
    act(() => setMoments([moment('mine-1', 'me', { mine: true, seen: true })]));
    expect(document.querySelector('[data-moment-viewer="mine-1"]')).toBeTruthy();
  });
});
