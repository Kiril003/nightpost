import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MessageCard } from '../components/messenger/cards/MessageCard';
import { buildFeedRows } from '../components/messenger/feed/feedModel';
import { MessageRow } from '../components/messenger/feed/MessageRow';
import { FeedActionsContext, type FeedActions } from '../components/messenger/feed/feedActions';
import { codeWindowEnd, shutCodes } from '../services/codeWindow';
import { PRIMITIVES } from '../feel/motionTokens';
import type { Message } from '../types/messenger';

const wedMorning = new Date(2026, 2, 4, 10, 0, 0, 0);

function makeDoorMessage(overrides: Partial<Message> = {}): Message {
  return {
    id: 'door-msg-1',
    senderId: 'peer-1',
    senderName: 'Олена',
    senderAvatar: '',
    timestamp: '10:00',
    type: 'text',
    text: 'code: 4829# until Sun',
    sentAt: wedMorning.toISOString(),
    ...overrides,
  };
}

function stubReducedMotion(reduced: boolean) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn().mockImplementation((query: string) => ({
      matches: reduced && query.includes('prefers-reduced-motion: reduce'),
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  );
}

const card = () => screen.getByTestId('card-door-code');
const renderCard = (msg: Message) => render(<MessageCard msg={msg} meId="me" meName="Я" />);

describe('DoorCodeCard', () => {
  beforeEach(() => {
    shutCodes.reset();
    stubReducedMotion(false);
    vi.useFakeTimers();
    vi.setSystemTime(wedMorning);
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    shutCodes.reset();
  });

  it('open while the window runs: code shown with Copy', () => {
    renderCard(makeDoorMessage());
    expect(card().getAttribute('data-state')).toBe('open');
    expect(screen.getByText('4829#')).toBeTruthy();
    expect(screen.getByText('Скопіювати')).toBeTruthy();
  });

  it('folds at the window end, then the digits leave the DOM', () => {
    const msg = makeDoorMessage();
    const end = codeWindowEnd(msg)!;
    const { container } = renderCard(msg);

    act(() => {
      vi.advanceTimersByTime(end - 1 - wedMorning.getTime());
    });
    expect(card().getAttribute('data-state')).toBe('open');

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(card().getAttribute('data-state')).toBe('folding');
    expect(screen.queryByText('Скопіювати')).toBeNull();

    fireEvent.transitionEnd(screen.getByTestId('door-code-body'));
    expect(card().getAttribute('data-state')).toBe('shut');
    expect(container.innerHTML).not.toContain('4829');
    expect(screen.getByText('Код прибрано')).toBeTruthy();
  });

  it('a transitionend bubbling while open does not shut the card', () => {
    renderCard(makeDoorMessage());
    fireEvent.transitionEnd(screen.getByTestId('door-code-body'));
    expect(card().getAttribute('data-state')).toBe('open');
    expect(screen.getByText('4829#')).toBeTruthy();
  });

  it('store purge folds the card and the fold token ends it without transitionend', () => {
    const msg = makeDoorMessage();
    const { rerender, container } = renderCard(msg);

    shutCodes.add(msg.id);
    rerender(<MessageCard msg={{ ...msg, isDeleted: true, text: undefined }} meId="me" meName="Я" />);
    expect(card().getAttribute('data-state')).toBe('folding');

    act(() => {
      vi.advanceTimersByTime(PRIMITIVES.fold.ms);
    });
    expect(card().getAttribute('data-state')).toBe('shut');
    expect(container.innerHTML).not.toContain('4829');
  });

  it('reduced motion: the window end goes straight to shut', () => {
    stubReducedMotion(true);
    const msg = makeDoorMessage();
    const { container } = renderCard(msg);

    act(() => {
      vi.advanceTimersByTime(codeWindowEnd(msg)! - wedMorning.getTime());
    });
    expect(card().getAttribute('data-state')).toBe('shut');
    expect(container.innerHTML).not.toContain('4829');
  });

  it('mounted after the window end renders shut with no digits', () => {
    const msg = makeDoorMessage();
    vi.setSystemTime(codeWindowEnd(msg)! + 60_000);
    const { container } = renderCard(msg);
    expect(card().getAttribute('data-state')).toBe('shut');
    expect(container.innerHTML).not.toContain('4829');
  });

  it('an edit with a later window opens the shut card again', () => {
    const msg = makeDoorMessage({ id: 'edit-1', text: 'code: 1111 until Sun' });
    vi.setSystemTime(codeWindowEnd(msg)! + 1);
    const { rerender } = renderCard(msg);
    expect(card().getAttribute('data-state')).toBe('shut');

    rerender(<MessageCard msg={{ ...msg, text: 'code: 3333 until Mon' }} meId="me" meName="Я" />);
    expect(card().getAttribute('data-state')).toBe('open');
    expect(screen.getByText('3333')).toBeTruthy();
  });

  it('uses no box-shadow, filter or gradient in inline styles', () => {
    const { container } = renderCard(makeDoorMessage());
    container.querySelectorAll('[style]').forEach((el) => {
      const style = (el.getAttribute('style') || '').toLowerCase();
      expect(style).not.toContain('box-shadow');
      expect(style).not.toContain('filter');
      expect(style).not.toContain('gradient');
    });
  });
});

describe('door code in the message feed', () => {
  const actions = new Proxy({}, { get: () => () => {} }) as FeedActions;
  const renderRow = (msg: Message) => {
    const [row] = buildFeedRows([msg], 'me', wedMorning);
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

  beforeEach(() => {
    shutCodes.reset();
    vi.useFakeTimers();
    vi.setSystemTime(wedMorning);
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    shutCodes.reset();
  });

  it('a plain text door code renders as the card, not as a text bubble', () => {
    const { container } = renderRow(makeDoorMessage());
    expect(screen.getByTestId('card-door-code').getAttribute('data-state')).toBe('open');
    expect(container.innerHTML.split('4829#').length - 1).toBe(1);
    expect(container.innerHTML).not.toContain('until Sun');
  });

  it('a purged door code stays the card, not the generic tombstone', () => {
    shutCodes.add('door-msg-1');
    renderRow(makeDoorMessage({ isDeleted: true, text: undefined }));
    expect(screen.getByTestId('card-door-code').getAttribute('data-state')).toBe('shut');
    expect(screen.queryByTestId('message-tombstone')).toBeNull();
  });
});
