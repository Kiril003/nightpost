import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, waitFor } from '@testing-library/react';

const anim = { play: vi.fn(), pause: vi.fn(), goToAndStop: vi.fn(), goToAndPlay: vi.fn(), destroy: vi.fn(), addEventListener: vi.fn() };
const loadAnimation = vi.fn(() => anim);
vi.mock('../stickers/lottieRuntime', () => ({ loadLottie: () => Promise.resolve({ loadAnimation }) }));

import { buildFeedRows } from '../components/messenger/feed/feedModel';
import { MessageRow } from '../components/messenger/feed/MessageRow';
import { FeedActionsContext, type FeedActions } from '../components/messenger/feed/feedActions';
import { POSTER_FRAME } from '../stickers/StickerView';
import type { Message } from '../types/messenger';

const actions = new Proxy({}, { get: () => () => {} }) as FeedActions;

const msgOf = (id: string, text: string): Message =>
  ({
    id, senderId: 'u2', senderName: 'Оля', senderAvatar: '', text, timestamp: '14:32',
    sentAt: '2026-10-15T14:32:00Z', type: 'text', isSelf: false,
  }) as Message;

const renderRow = (id: string, text: string) => {
  const [row] = buildFeedRows([msgOf(id, text)], 'me', new Date('2026-10-15T15:00:00Z'));
  return render(
    <FeedActionsContext.Provider value={actions}>
      <MessageRow
        row={row} chatId="c1" currentUserId="me" currentUserName="Я" groupish={false} direct phone
        isSelectionMode={false} isSelected={false} isHighlighted={false} isSearchMatch={false}
        isActiveSearchMatch={false} fresh={false} expanded={false} copied={false}
      />
    </FeedActionsContext.Provider>,
  );
};

let reduce = false;
beforeEach(() => {
  reduce = false;
  vi.clearAllMocks();
  window.matchMedia = ((q: string) => ({ matches: reduce && q.includes('reduce'), media: q, addEventListener() {}, removeEventListener() {} })) as unknown as typeof window.matchMedia;
});
afterEach(cleanup);

describe('самотнє емодзі оживає', () => {
  it('👍 саме по собі — живий двійник з набору, грає один раз', async () => {
    const { container } = renderRow('e1', '👍');
    const el = container.querySelector('[data-sticker="d1/thumbs"]');
    expect(el).not.toBeNull();
    await waitFor(() => expect(el?.getAttribute('data-sticker-state')).toBe('on'));
    expect(loadAnimation).toHaveBeenCalledWith(expect.objectContaining({ loop: false }));
    expect(anim.goToAndPlay).toHaveBeenCalledWith(0, true);
    expect(anim.play).not.toHaveBeenCalled();
  });

  it('вже грало — при поверненні стоїть, дотик грає знову', async () => {
    const first = renderRow('e2', '❤️');
    await waitFor(() => expect(first.container.querySelector('[data-sticker-state="on"]')).not.toBeNull());
    act(() => first.unmount());
    vi.clearAllMocks();
    const { container } = renderRow('e2', '❤️');
    const el = container.querySelector('[data-sticker="d1/heart"]') as HTMLElement;
    await waitFor(() => expect(el.getAttribute('data-sticker-state')).toBe('on'));
    expect(anim.goToAndStop).toHaveBeenCalledWith(POSTER_FRAME, true);
    expect(anim.goToAndPlay).not.toHaveBeenCalled();
    fireEvent.click(el);
    expect(anim.goToAndPlay).toHaveBeenCalledWith(0, true);
  });

  it('рух вимкнено — нерухомий кадр і дотик нічого не крутить', async () => {
    reduce = true;
    const { container } = renderRow('e3', '🎉');
    const el = container.querySelector('[data-sticker="d1/party"]') as HTMLElement;
    await waitFor(() => expect(el.getAttribute('data-sticker-state')).toBe('on'));
    fireEvent.click(el);
    expect(anim.goToAndStop).toHaveBeenCalledWith(POSTER_FRAME, true);
    expect(anim.goToAndPlay).not.toHaveBeenCalled();
  });

  it('емодзі без двійника і два емодзі — лишаються текстом', () => {
    const fox = renderRow('e4', '🦊');
    expect(fox.container.querySelector('[data-sticker]')).toBeNull();
    expect(fox.container.textContent).toContain('🦊');
    fox.unmount();
    const two = renderRow('e5', '👍👍');
    expect(two.container.querySelector('[data-sticker]')).toBeNull();
  });
});

describe('час сидить на нижньому краї стікера', () => {
  it('стікер і велике емодзі — пігулка часу накладена на край, без щілини', () => {
    const st = renderRow('e6', '☕\n⟦st1:d1/tea⟧');
    const line = st.container.querySelector('[data-msg-status-line]') as HTMLElement;
    expect(line.getAttribute('data-on-sticker')).toBe('true');
    expect(line.className).toContain('-mt-6');
    expect(line.style.width).toBe('160px');
    st.unmount();
    const big = renderRow('e7', '👍');
    expect(big.container.querySelector('[data-msg-status-line]')?.getAttribute('data-on-sticker')).toBe('true');
  });

  it('звичайний смайл без двійника — час під ним, як був', () => {
    const { container } = renderRow('e8', '🦊');
    const line = container.querySelector('[data-msg-status-line]') as HTMLElement;
    expect(line.getAttribute('data-on-sticker')).toBeNull();
    expect(line.className).toContain('mt-1');
  });
});
