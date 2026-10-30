import { beforeAll, beforeEach, afterEach, describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ChatArea } from '../components/messenger/ChatArea';
import type { Chat, Message } from '../types/messenger';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

const CHAT = {
  id: 'chat_dm',
  title: 'Оксана',
  avatar: '',
  type: 'dm',
  circle: 'all',
  pinned: false,
  unreadCount: 0,
  messages: [],
} as unknown as Chat;

const line = (over: Partial<Message> = {}): Message =>
  ({
    id: 'm1',
    senderId: 'peer',
    senderName: 'Оксана',
    senderAvatar: '',
    timestamp: '14:32',
    sentAt: '2026-09-18T11:32:00.000Z',
    type: 'text',
    text: 'Виїжджаємо за десять хвилин',
    isSelf: false,
    ...over,
  }) as unknown as Message;

const noop = () => {};

function feed(
  messages: Message[],
  onReplyMessage: (m: Message) => void = noop,
  chat: Chat = CHAT,
  onAddReaction: (id: string, emoji: string) => void = noop,
) {
  return render(
    <MemoryRouter>
      <ChatArea
        currentChat={chat}
        messages={messages}
        currentUserId="me"
        onOpenLocation={noop}
        onVotePoll={noop}
        onPayBillShare={noop}
        onAddReaction={onAddReaction}
        onReplyMessage={onReplyMessage}
        onEditMessage={noop}
        selectedMessageIds={[]}
        onToggleSelectMessage={noop}
        isSelectionMode={false}
        isSearching={false}
        onCloseSearch={noop}
      />
    </MemoryRouter>,
  );
}

const bubble = () =>
  (screen.getByText('Виїжджаємо за десять хвилин').closest('div[class*="msg-bubble"]') ??
    screen.getByText('Виїжджаємо за десять хвилин')) as HTMLElement;

describe('useRowGestures feel', () => {
  let hapticSpy: ReturnType<typeof vi.fn>;
  let vibrateSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    hapticSpy = vi.fn();
    vibrateSpy = vi.fn();
    (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = {
      call: () => '{}',
      haptic: hapticSpy,
    };
    Object.defineProperty(navigator, 'vibrate', {
      value: vibrateSpy,
      writable: true,
      configurable: true,
    });
  });

  afterEach(() => {
    delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
    vi.useRealTimers();
  });

  it('fires haptic("threshold") once mid-drag when crossing 44 px', () => {
    vi.useFakeTimers();
    vi.setSystemTime(200_000);
    feed([line()]);
    const node = bubble();

    fireEvent.touchStart(node, { touches: [{ clientX: 100, clientY: 10 }] });
    fireEvent.touchMove(node, { touches: [{ clientX: 70, clientY: 10 }] });
    expect(hapticSpy).not.toHaveBeenCalledWith('threshold');

    fireEvent.touchMove(node, { touches: [{ clientX: 50, clientY: 10 }] });
    expect(hapticSpy).toHaveBeenCalledWith('threshold');
    expect(hapticSpy.mock.calls.filter((c) => c[0] === 'threshold').length).toBe(1);

    vi.advanceTimersByTime(100);
    fireEvent.touchMove(node, { touches: [{ clientX: 40, clientY: 10 }] });
    expect(hapticSpy.mock.calls.filter((c) => c[0] === 'threshold').length).toBe(1);
  });

  it('the reply circle fills with amber once the swipe is armed and goes quiet below the threshold', () => {
    feed([line()]);
    const node = bubble();
    const dot = () => document.querySelector('.msg-swipe-reply-indicator') as HTMLElement;
    fireEvent.touchStart(node, { touches: [{ clientX: 100, clientY: 10 }] });
    fireEvent.touchMove(node, { touches: [{ clientX: 70, clientY: 10 }] });
    expect(dot().getAttribute('data-armed')).not.toBe('true');
    fireEvent.touchMove(node, { touches: [{ clientX: 40, clientY: 10 }] });
    expect(dot().getAttribute('data-armed')).toBe('true');
    expect(dot().className).toContain('data-[armed=true]:bg-accent');
    fireEvent.touchMove(node, { touches: [{ clientX: 80, clientY: 10 }] });
    expect(dot().getAttribute('data-armed')).not.toBe('true');
  });

  it('rubberbands a -200 move beyond 75 px and short of 128 px', () => {
    feed([line()]);
    const node = bubble();

    fireEvent.touchStart(node, { touches: [{ clientX: 210, clientY: 10 }] });
    fireEvent.touchMove(node, { touches: [{ clientX: 10, clientY: 10 }] });

    const match = node.style.transform.match(/translateX\(([-\d.]+)px\)/);
    expect(match).not.toBeNull();
    const x = Number(match![1]);
    expect(x).toBeLessThan(-75);
    expect(x).toBeGreaterThan(-128);
  });

  it('replies on a left swipe past threshold, with no toast and no navigator.vibrate', () => {
    const onReply = vi.fn();
    const { container } = feed([line()], onReply);
    const node = bubble();
    const before = container.textContent;

    fireEvent.touchStart(node, { touches: [{ clientX: 80, clientY: 10 }] });
    fireEvent.touchMove(node, { touches: [{ clientX: 20, clientY: 10 }] });
    fireEvent.touchEnd(node);

    expect(onReply).toHaveBeenCalledTimes(1);
    expect(container.textContent).toBe(before);
    expect(vibrateSpy).not.toHaveBeenCalled();
  });

  it('own lines swipe left to reply too, and a right swipe never replies', () => {
    const onReply = vi.fn();
    feed([line(), line({ id: 'm2', senderId: 'me', senderName: 'Я', isSelf: true, text: 'Я вже вийшов' })], onReply);
    const peer = bubble();
    const own = (screen.getByText('Я вже вийшов').closest('div[class*="msg-bubble"]') ?? screen.getByText('Я вже вийшов')) as HTMLElement;

    fireEvent.touchStart(peer, { touches: [{ clientX: 20, clientY: 10 }] });
    fireEvent.touchMove(peer, { touches: [{ clientX: 90, clientY: 10 }] });
    expect(peer.style.transform).toBe('translateX(0px)');
    fireEvent.touchEnd(peer);
    expect(onReply).not.toHaveBeenCalled();

    fireEvent.touchStart(own, { touches: [{ clientX: 90, clientY: 10 }] });
    fireEvent.touchMove(own, { touches: [{ clientX: 30, clientY: 10 }] });
    fireEvent.touchEnd(own);
    expect(onReply).toHaveBeenCalledTimes(1);
    expect(onReply.mock.calls[0][0].id).toBe('m2');
  });

  it('fires haptic("long") and opens the actions menu after a 420 ms hold', () => {
    vi.useFakeTimers();
    vi.setSystemTime(300_000);
    feed([line()]);
    const node = bubble();

    fireEvent.touchStart(node, { touches: [{ clientX: 10, clientY: 10 }] });
    act(() => {
      vi.advanceTimersByTime(420);
    });

    expect(hapticSpy).toHaveBeenCalledWith('long');
    expect(screen.getByText('Відповісти')).toBeTruthy();
    expect(vibrateSpy).not.toHaveBeenCalled();
  });

  it('keeps the swipe offset when the swipe starts after the press scale has begun', () => {
    vi.useFakeTimers({
      toFake: ['setTimeout', 'clearTimeout', 'requestAnimationFrame', 'cancelAnimationFrame', 'Date', 'performance'],
    });
    vi.setSystemTime(400_000);
    feed([line()]);
    const node = bubble();

    fireEvent.touchStart(node, { touches: [{ clientX: 50, clientY: 10 }] });
    act(() => {
      vi.advanceTimersByTime(250);
    });
    expect(node.style.transform).toMatch(/^scale\(/);

    fireEvent.touchMove(node, { touches: [{ clientX: 10, clientY: 10 }] });
    act(() => {
      vi.advanceTimersByTime(150);
    });
    expect(node.style.transform).toBe('translateX(-40px)');
  });

  it('clears the leftover offset when a new touch lands while the snap-back is running', () => {
    vi.useFakeTimers({
      toFake: ['setTimeout', 'clearTimeout', 'requestAnimationFrame', 'cancelAnimationFrame', 'Date', 'performance'],
    });
    vi.setSystemTime(500_000);
    feed([line()]);
    const node = bubble();

    fireEvent.touchStart(node, { touches: [{ clientX: 80, clientY: 10 }] });
    fireEvent.touchMove(node, { touches: [{ clientX: 20, clientY: 10 }] });
    act(() => {
      vi.advanceTimersByTime(350);
    });
    fireEvent.touchEnd(node);
    act(() => {
      vi.advanceTimersByTime(48);
    });
    expect(node.style.transform).toMatch(/translateX\(/);

    fireEvent.touchStart(node, { touches: [{ clientX: 10, clientY: 10 }] });
    expect(node.style.transform).toBe('');
  });

  it('a double tap in Saved puts a heart on the note', () => {
    vi.useFakeTimers();
    vi.setSystemTime(600_000);
    const onAddReaction = vi.fn();
    const saved = { ...CHAT, id: 'chat_saved_notes', title: 'Збережене', type: 'saved' } as unknown as Chat;
    feed([line({ senderId: 'me', isSelf: true })], noop, saved, onAddReaction);
    const node = bubble();

    fireEvent.touchStart(node, { touches: [{ clientX: 10, clientY: 10 }] });
    fireEvent.touchEnd(node);
    vi.advanceTimersByTime(120);
    fireEvent.touchStart(node, { touches: [{ clientX: 10, clientY: 10 }] });
    fireEvent.touchEnd(node);

    expect(onAddReaction).toHaveBeenCalledWith('m1', '❤️');
  });
});
