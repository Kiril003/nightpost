/** Власні знаки реакцій: пігулка, рядок довгого натискання, запасний смайл у дроті, рух. */
import { beforeAll, afterEach, describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ChatArea } from '../components/messenger/ChatArea';
import type { Chat, Message } from '../types/messenger';
import { GLYPHS, glyphForReaction, wireEmojiFor, QUICK_GLYPH_IDS } from '../components/messenger/glyphs/reactionGlyphs';
import { ReactionGlyph } from '../components/messenger/glyphs/ReactionGlyph';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

/** Телефонна оболонка — це наявність мосту, а не ширина вікна. */
const asPhone = () => {
  (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = { call: () => '{}' };
};

afterEach(() => {
  delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
  vi.useRealTimers();
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

function feed(messages: Message[], onAddReaction: ChatAreaReact = noop) {
  return render(
    <MemoryRouter>
      <ChatArea
        currentChat={CHAT}
        messages={messages}
        currentUserId="me"
        onOpenLocation={noop}
        onVotePoll={noop}
        onPayBillShare={noop}
        onAddReaction={onAddReaction}
        onReplyMessage={noop}
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

type ChatAreaReact = (messageId: string, emoji: string) => Promise<string | null> | void;

/** Довге натискання — єдина дотикова дорога до дій над рядком. */
function longPress(node: Element) {
  vi.useFakeTimers();
  fireEvent.touchStart(node, { touches: [{ clientX: 10, clientY: 10 }] });
  act(() => {
    vi.advanceTimersByTime(500);
  });
  vi.useRealTimers();
}

const bubble = () => screen.getByText('Виїжджаємо за десять хвилин').closest('div[class*="msg-bubble"]')
  ?? screen.getByText('Виїжджаємо за десять хвилин');


describe('власні знаки реакцій', () => {
  it('пігулка під листом малює знак, а не смайл', () => {
    asPhone();
    feed([line({ reactions: [{ emoji: '❤️', count: 2, users: ['peer', 'me'] }] })]);
    const pill = screen.getByTestId('msg-reactions');
    expect(pill.querySelector('svg[data-glyph="love"]')).toBeTruthy();
    expect(pill.textContent).not.toContain('❤️');
  });

  it('невідомий смайл лишається текстом', () => {
    asPhone();
    feed([line({ reactions: [{ emoji: '🦖', count: 1, users: ['peer'] }] })]);
    const pill = screen.getByTestId('msg-reactions');
    expect(pill.querySelector('svg')).toBeNull();
    expect(pill.textContent).toContain('🦖');
  });

  it('довге натискання: рядок несе наші знаки', () => {
    asPhone();
    feed([line()]);
    longPress(bubble());
    const bar = document.querySelector('[data-msg-reactions-bar]')!;
    for (const id of QUICK_GLYPH_IDS) expect(bar.querySelector(`svg[data-glyph="${id}"]`)).toBeTruthy();
  });

  it('у дроті їде запасний смайл, а id впізнається назад', () => {
    expect(GLYPHS).toHaveLength(24);
    for (const g of GLYPHS) {
      expect(wireEmojiFor(g.id)).toBe(g.emoji);
      expect(glyphForReaction(g.emoji)?.id).toBe(g.id);
      expect(glyphForReaction(`np:${g.id}`)?.id).toBe(g.id);
    }
    expect(new Set(GLYPHS.map((g) => g.emoji)).size).toBe(24);
    const react = vi.fn();
    asPhone();
    feed([line()], react);
    longPress(bubble());
    fireEvent.click(screen.getByLabelText('Реакція ❤️'));
    expect(react).toHaveBeenCalledWith('m1', '❤️');
  });
});

describe('рух знака', () => {
  const mockMotion = (reduce: boolean) => {
    const animate = vi.fn(() => ({ cancel: () => {} }));
    (SVGElement.prototype as unknown as { animate: unknown }).animate = animate;
    window.matchMedia = ((q: string) => ({
      matches: reduce && q.includes('reduce'),
      media: q,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      onchange: null,
      dispatchEvent: () => false,
    })) as unknown as typeof window.matchMedia;
    return animate;
  };

  it('грає раз і не довше 600 мс', () => {
    const animate = mockMotion(false);
    render(<ReactionGlyph value="❤️" play />);
    expect(animate).toHaveBeenCalledTimes(1);
    const opts = (animate.mock.calls[0] as unknown[])[1] as { duration: number };
    expect(opts.duration).toBeLessThanOrEqual(600);
    const frames = (animate.mock.calls[0] as unknown[])[0] as Record<string, unknown>[];
    for (const f of frames) for (const k of Object.keys(f)) expect(['transform', 'opacity', 'offset']).toContain(k);
  });

  it('при prefers-reduced-motion не рухається', () => {
    const animate = mockMotion(true);
    render(<ReactionGlyph value="❤️" play />);
    expect(animate).not.toHaveBeenCalled();
  });
});
