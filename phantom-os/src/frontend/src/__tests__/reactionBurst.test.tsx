import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, waitFor } from '@testing-library/react';

const done: Array<() => void> = [];
const anim = {
  play: vi.fn(), pause: vi.fn(), goToAndStop: vi.fn(), goToAndPlay: vi.fn(), destroy: vi.fn(),
  addEventListener: vi.fn((ev: string, cb: () => void) => { if (ev === 'complete') done.push(cb); }),
};
const loadAnimation = vi.fn(() => anim);
vi.mock('../stickers/lottieRuntime', () => ({ loadLottie: () => Promise.resolve({ loadAnimation }) }));

import { buildFeedRows } from '../components/messenger/feed/feedModel';
import { MessageRow } from '../components/messenger/feed/MessageRow';
import { FeedActionsContext, type FeedActions } from '../components/messenger/feed/feedActions';
import type { Message, Reaction } from '../types/messenger';

const actions = new Proxy({}, { get: () => () => {} }) as FeedActions;

const row = (reactions: Reaction[]) =>
  buildFeedRows(
    [
      {
        id: 'r1', senderId: 'u2', senderName: 'Оля', senderAvatar: '', text: 'Завтра о сьомій?', timestamp: '14:32',
        sentAt: '2026-10-15T14:32:00Z', type: 'text', isSelf: false, reactions,
      } as Message,
    ],
    'me',
    new Date('2026-10-15T15:00:00Z'),
  )[0];

const view = (reactions: Reaction[]) => (
  <FeedActionsContext.Provider value={actions}>
    <MessageRow
      row={row(reactions)} chatId="c1" currentUserId="me" currentUserName="Я" groupish={false} direct phone
      isSelectionMode={false} isSelected={false} isHighlighted={false} isSearchMatch={false}
      isActiveSearchMatch={false} fresh={false} expanded={false} copied={false}
    />
  </FeedActionsContext.Provider>
);

const heart = (count: number, users: string[]): Reaction[] => [{ emoji: '❤️', count, users } as Reaction];

let reduce = false;
beforeEach(() => {
  reduce = false;
  done.length = 0;
  vi.clearAllMocks();
  window.matchMedia = ((q: string) => ({ matches: reduce && q.includes('reduce'), media: q, addEventListener() {}, removeEventListener() {} })) as unknown as typeof window.matchMedia;
});
afterEach(cleanup);

describe('реакція оживає', () => {
  it('стара реакція при відкритті чату — без спалаху', () => {
    const { container } = render(view(heart(2, ['u2', 'u3'])));
    expect(container.querySelector('[data-reaction-burst]')).toBeNull();
  });

  it('нова ❤️ — серце з набору раз злітає над пігулкою і зникає', async () => {
    const { container, rerender } = render(view(heart(1, ['u2'])));
    rerender(view(heart(2, ['u2', 'me'])));
    const burst = container.querySelector('[data-reaction-burst="d1/heart"]');
    expect(burst).not.toBeNull();
    await waitFor(() => expect(burst?.querySelector('[data-sticker-state="on"]')).not.toBeNull());
    expect(loadAnimation).toHaveBeenCalledWith(expect.objectContaining({ loop: false }));
    expect(anim.goToAndPlay).toHaveBeenCalledWith(0, true);
    act(() => done.forEach((cb) => cb()));
    expect(container.querySelector('[data-reaction-burst]')).toBeNull();
  });

  it('перша ❤️ на вже показаному листі (пігулки ще не було) — теж злітає', () => {
    const { container, rerender } = render(view([]));
    rerender(view(heart(1, ['me'])));
    expect(container.querySelector('[data-reaction-burst="d1/heart"]')).not.toBeNull();
  });

  it('рух вимкнено — жодного спалаху', () => {
    reduce = true;
    const { container, rerender } = render(view(heart(1, ['u2'])));
    rerender(view(heart(2, ['u2', 'me'])));
    expect(container.querySelector('[data-reaction-burst]')).toBeNull();
  });

  it('смайл без двійника в наборі — без спалаху', () => {
    const fire = (count: number): Reaction[] => [{ emoji: '🤔', count, users: [] } as Reaction];
    const { container, rerender } = render(view(fire(1)));
    rerender(view(fire(2)));
    expect(container.querySelector('[data-reaction-burst]')).toBeNull();
  });
});
