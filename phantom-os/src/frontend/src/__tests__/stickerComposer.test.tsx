import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';

const anim = vi.hoisted(() => ({
  loadAnimation: vi.fn(() => ({ play: vi.fn(), pause: vi.fn(), goToAndStop: vi.fn(), destroy: vi.fn() })),
}));
vi.mock('../stickers/lottieRuntime', () => ({ loadLottie: () => Promise.resolve(anim) }));

import { MessageComposer } from '../components/messenger/MessageComposer';
import { parseSticker } from '../stickers/stickerWire';
import { shelf } from '../stickers/stickerShelf';
import { PACK } from '../stickers/pack';

function mount(send = vi.fn(), editing: unknown = null) {
  render(
    <MessageComposer
      onSendMessage={send}
      onSendVoiceMessage={vi.fn()}
      replyingTo={null}
      onCancelReply={vi.fn()}
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      editingMessage={editing as any}
      onCancelEdit={vi.fn()}
      onSaveEdit={vi.fn()}
      selectedMessagesForQuote={[]}
      onSynthesizeMultiQuote={vi.fn()}
      onClearSelectedQuotes={vi.fn()}
    />,
  );
  return send;
}

describe('composer sticker panel', () => {
  beforeEach(() => {
    try { localStorage.clear(); } catch { /* none */ }
  });
  afterEach(() => vi.useRealTimers());

  it('opens the panel with the whole pack and sends a tapped sticker as a sticker message', async () => {
    const send = mount();
    await act(async () => {
      fireEvent.click(screen.getByTestId('composer-stickers'));
    });
    const sheet = screen.getByTestId('sticker-sheet');
    const tiles = sheet.querySelectorAll('[data-sticker-pick]');
    expect(tiles.length).toBe(PACK.length);
    expect(screen.queryByTestId('sticker-recent')).toBeNull();
    await act(async () => {
      fireEvent.click(tiles[1]);
    });
    expect(send).toHaveBeenCalledTimes(1);
    const sent = parseSticker(send.mock.calls[0][0]);
    expect(sent).toEqual({ packId: PACK[1].packId, id: PACK[1].id, emoji: PACK[1].emoji });
    expect(screen.queryByTestId('sticker-sheet')).toBeNull();
    expect(shelf().recent.map((d) => d.id)).toEqual([PACK[1].id]);
  });

  it('shows recent first after a send and keeps favourites from a long press', async () => {
    vi.useFakeTimers();
    mount();
    await act(async () => {
      fireEvent.click(screen.getByTestId('composer-stickers'));
    });
    const tile = screen.getByTestId('sticker-pack').querySelectorAll('[data-sticker-pick]')[3] as HTMLElement;
    await act(async () => {
      fireEvent.pointerDown(tile);
      vi.advanceTimersByTime(500);
      fireEvent.pointerUp(tile);
      fireEvent.click(tile);
    });
    expect(shelf().fav.map((d) => d.id)).toEqual([PACK[3].id]);
    expect(screen.getByTestId('sticker-sheet')).toBeTruthy();
    expect(screen.getByTestId('sticker-fav').querySelectorAll('[data-sticker-pick]').length).toBe(1);
  });

  it('hides the sticker button while editing a message', () => {
    mount(vi.fn(), { id: 'm1', text: 'x', type: 'text', sender: 'me', timestamp: Date.now() });
    expect(screen.queryByTestId('composer-stickers')).toBeNull();
  });
});
