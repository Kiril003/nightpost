import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useMessengerStore } from '../stores/messengerStore';
import * as peerWireMod from '../services/peerWire';

if (typeof window !== 'undefined' && !window.PointerEvent) {
  class PointerEventPolyfill extends MouseEvent {
    pointerId: number;
    constructor(type: string, params: PointerEventInit = {}) {
      super(type, params);
      this.pointerId = params.pointerId ?? 0;
    }
  }
  window.PointerEvent = PointerEventPolyfill as unknown as typeof PointerEvent;
}

const peerSpy = vi.fn();
const paySheetSpy = vi.fn();
const groupSheetSpy = vi.fn();

vi.mock('../services/solanaActions', () => ({
  solanaApi: {
    peer: (...args: unknown[]) => peerSpy(...args),
  },
  askSolana: vi.fn(),
}));

vi.mock('../components/messenger/solana/PayMoneySheet', () => ({
  PayMoneySheet: (props: Record<string, unknown>) => {
    paySheetSpy(props);
    return <div data-testid="mock-pay-sheet" />;
  },
  default: (props: Record<string, unknown>) => {
    paySheetSpy(props);
    return <div data-testid="mock-pay-sheet" />;
  },
}));

vi.mock('../components/messenger/solana/GroupMoneySheet', () => ({
  GroupMoneySheet: (props: Record<string, unknown>) => {
    groupSheetSpy(props);
    return <div data-testid="mock-group-sheet" />;
  },
  default: (props: Record<string, unknown>) => {
    groupSheetSpy(props);
    return <div data-testid="mock-group-sheet" />;
  },
}));

vi.mock('../components/messenger/PeerWireBubble', () => ({
  PeerWireBubble: () => <div data-testid="mock-peer-wire-bubble" />,
}));

import { FormattedMessageText } from '../components/messenger/FormattedMessageText';

function seedDmChat(peerNodeId = 'peer-olena') {
  useMessengerStore.setState({
    currentUser: { id: 'me', name: 'Я', handle: 'me', status: 'online' } as never,
    activeChatId: 'chat-dm',
    chats: [
      {
        id: 'chat-dm',
        name: 'Олена',
        type: 'direct',
        peerNodeId,
        members: [
          { id: 'me', name: 'Я', isMe: true },
          { id: peerNodeId, name: 'Олена' },
        ],
        messages: [],
        unreadCount: 0,
      } as never,
    ],
  });
}

function seedGroupChat() {
  useMessengerStore.setState({
    currentUser: { id: 'me', name: 'Я', handle: 'me', status: 'online' } as never,
    activeChatId: 'chat-group',
    chats: [
      {
        id: 'chat-group',
        name: 'Вечеря',
        type: 'group',
        members: [
          { id: 'me', name: 'Я', isMe: true },
          { id: 'peer-1', name: 'Олена' },
          { id: 'peer-2', name: 'Тарас' },
        ],
        messages: [],
        unreadCount: 0,
      } as never,
    ],
  });
}

describe('FormattedMessageText amount tap', () => {
  beforeEach(() => {
    peerSpy.mockReset();
    paySheetSpy.mockReset();
    groupSheetSpy.mockReset();
    peerSpy.mockResolvedValue({
      can_pay: true,
      address: 'So11111111111111111111111111111111111111112',
      cluster: 'devnet',
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    cleanup();
  });

  it('renders a USD amount in a DM and opens PayMoneySheet without bubbling to the parent', async () => {
    seedDmChat('peer-olena');
    const parentClick = vi.fn();

    const { container } = render(
      <div onClick={parentClick}>
        <FormattedMessageText text="ти винен $23" amountsTappable />
      </div>,
    );

    const spans = container.querySelectorAll('[data-amount="USD"]');
    expect(spans).toHaveLength(1);
    const span = spans[0] as HTMLElement;
    expect(span.textContent).toBe('$23');
    expect(span.getAttribute('title')).toBe('Оплатити $23');
    expect(span.style.touchAction).toBe('manipulation');
    expect((span.style as unknown as Record<string, string>).WebkitTouchCallout).toBe('none');
    expect(span.className).toContain('touch-manipulation');
    expect(span.className).toContain('[-webkit-touch-callout:none]');

    fireEvent.click(span);
    expect(parentClick).not.toHaveBeenCalled();
    expect(peerSpy).toHaveBeenCalledWith('peer-olena');

    await waitFor(() => {
      expect(screen.getByTestId('mock-pay-sheet')).toBeTruthy();
    });

    const props = paySheetSpy.mock.calls[0][0] as {
      peer: { id: string; name: string };
      chat: { id: string; ref: string; amount?: string; token?: string; note?: string };
    };
    expect(props.peer.id).toBe('peer-olena');
    expect(props.chat.id).toBe('chat-dm');
    expect(props.chat.amount).toBe('23');
    expect(props.chat.token).toBe('USDC');
    expect(typeof props.chat.ref).toBe('string');
    expect(props.chat.ref.length).toBeGreaterThan(0);
  });

  it('keeps the same text plain without a button role when amountsTappable is omitted', () => {
    seedDmChat('peer-olena');

    const { container } = render(<FormattedMessageText text="ти винен $23" />);
    expect(screen.queryByRole('button')).toBeNull();
    expect(container.querySelector('[data-amount]')).toBeNull();
    expect(container.textContent).toBe('ти винен $23');
  });

  it('prefills EUR as EURC, UAH as note only, and does not open when can_pay is false', async () => {
    seedDmChat('peer-olena');

    const { container, rerender } = render(<FormattedMessageText text="20 €" amountsTappable />);
    const eurSpan = container.querySelector('[data-amount="EUR"]') as HTMLElement;
    expect(eurSpan).toBeTruthy();
    fireEvent.click(eurSpan);

    await waitFor(() => {
      expect(paySheetSpy).toHaveBeenCalledTimes(1);
    });
    expect(paySheetSpy.mock.calls[0][0].chat).toMatchObject({
      id: 'chat-dm',
      token: 'EURC',
      amount: '20',
    });

    cleanup();
    paySheetSpy.mockReset();

    const uahView = render(<FormattedMessageText text="150 грн" amountsTappable />);
    const uahSpan = uahView.container.querySelector('[data-amount="UAH"]') as HTMLElement;
    expect(uahSpan).toBeTruthy();
    fireEvent.click(uahSpan);

    await waitFor(() => {
      expect(paySheetSpy).toHaveBeenCalledTimes(1);
    });
    const uahChat = paySheetSpy.mock.calls[0][0].chat as Record<string, unknown>;
    expect(uahChat.note).toBe('150 грн');
    expect(uahChat.amount).toBeUndefined();
    expect(uahChat.token).toBeUndefined();

    cleanup();
    paySheetSpy.mockReset();
    peerSpy.mockResolvedValueOnce({
      can_pay: false,
      address: null,
      cluster: 'devnet',
    });

    const unpaidView = render(<FormattedMessageText text="ти винен $23" amountsTappable />);
    const unpaidSpan = unpaidView.container.querySelector('[data-amount="USD"]') as HTMLElement;
    fireEvent.click(unpaidSpan);

    await waitFor(() => {
      expect(peerSpy).toHaveBeenCalled();
    });
    expect(paySheetSpy).not.toHaveBeenCalled();
    expect(rerender).toBeTypeOf('function');
  });

  it('fires long-press at 500 ms after a 3 px pointermove and cancels only above 10 px or early release', async () => {
    vi.useFakeTimers();
    seedDmChat('peer-olena');

    const { container } = render(<FormattedMessageText text="ти винен $23" amountsTappable />);
    const span = container.querySelector('[data-amount="USD"]') as HTMLElement;
    expect(span).toBeTruthy();

    fireEvent.pointerDown(span, { clientX: 10, clientY: 10 });
    vi.advanceTimersByTime(200);
    fireEvent.pointerUp(span);
    vi.advanceTimersByTime(500);
    expect(peerSpy).not.toHaveBeenCalled();

    fireEvent.pointerDown(span, { clientX: 10, clientY: 10 });
    fireEvent.pointerMove(span, { clientX: 25, clientY: 10 });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500);
    });
    expect(peerSpy).not.toHaveBeenCalled();

    fireEvent.pointerDown(span, { clientX: 10, clientY: 10 });
    fireEvent.pointerMove(span, { clientX: 13, clientY: 10 });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500);
    });
    expect(peerSpy).toHaveBeenCalledWith('peer-olena');
    expect(paySheetSpy).toHaveBeenCalledTimes(1);
  });

  it('allows only one open at a time across DM and group paths, suppresses click after long-press, and ignores repeated Enter', async () => {
    vi.useFakeTimers();
    seedGroupChat();

    const groupView = render(<FormattedMessageText text="$23" amountsTappable />);
    const groupSpan = groupView.container.querySelector('[data-amount="USD"]') as HTMLElement;

    fireEvent.keyDown(groupSpan, { key: 'Enter', repeat: true });
    expect(groupSheetSpy).not.toHaveBeenCalled();

    fireEvent.pointerDown(groupSpan, { clientX: 5, clientY: 5 });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500);
    });
    fireEvent.pointerUp(groupSpan);
    fireEvent.click(groupSpan);
    fireEvent.click(groupSpan);
    expect(groupSheetSpy).toHaveBeenCalledTimes(1);

    cleanup();
    groupSheetSpy.mockReset();

    seedDmChat('peer-olena');
    peerSpy.mockResolvedValue({
      can_pay: false,
      address: null,
      cluster: 'devnet',
    });

    const dmView = render(<FormattedMessageText text="$23" amountsTappable />);
    const dmSpan = dmView.container.querySelector('[data-amount="USD"]') as HTMLElement;

    fireEvent.pointerDown(dmSpan, { clientX: 0, clientY: 0 });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500);
    });
    fireEvent.pointerUp(dmSpan);
    fireEvent.click(dmSpan);
    expect(peerSpy).toHaveBeenCalledTimes(1);
  });

  it('opens GroupMoneySheet in a group chat with USDC total for USD and note for EUR', () => {
    seedGroupChat();

    const usdView = render(<FormattedMessageText text="$23" amountsTappable />);
    const usdSpan = usdView.container.querySelector('[data-amount="USD"]') as HTMLElement;
    expect(usdSpan).toBeTruthy();
    fireEvent.click(usdSpan);

    expect(groupSheetSpy).toHaveBeenCalledTimes(1);
    expect(groupSheetSpy.mock.calls[0][0]).toMatchObject({
      chatId: 'chat-group',
      prefill: { token: 'USDC', total: '23' },
    });

    cleanup();
    groupSheetSpy.mockReset();

    const eurView = render(<FormattedMessageText text="20 €" amountsTappable />);
    const eurSpan = eurView.container.querySelector('[data-amount="EUR"]') as HTMLElement;
    expect(eurSpan).toBeTruthy();
    fireEvent.click(eurSpan);

    expect(groupSheetSpy).toHaveBeenCalledTimes(1);
    expect(groupSheetSpy.mock.calls[0][0]).toMatchObject({
      chatId: 'chat-group',
      prefill: { note: '20 €' },
    });
  });

  it('does not re-render on unrelated chat message updates and does not set sheet after unmount', async () => {
    seedDmChat('peer-olena');
    const wireSpy = vi.spyOn(peerWireMod, 'readPeerWire');

    let resolvePeer: ((val: unknown) => void) | null = null;
    peerSpy.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolvePeer = resolve;
        }),
    );

    const view = render(<FormattedMessageText text="ти винен $23" amountsTappable />);
    expect(wireSpy).toHaveBeenCalledTimes(1);

    useMessengerStore.setState((s) => ({
      chats: s.chats.map((c) =>
        c.id === 'chat-dm'
          ? ({ ...c, unreadCount: c.unreadCount + 1, messages: [{ id: 'm1', text: 'привіт' }] } as never)
          : c,
      ),
    }));
    expect(wireSpy).toHaveBeenCalledTimes(1);

    const span = view.container.querySelector('[data-amount="USD"]') as HTMLElement;
    fireEvent.click(span);
    expect(peerSpy).toHaveBeenCalledTimes(1);

    view.unmount();
    (resolvePeer as ((val: unknown) => void) | null)?.({
      can_pay: true,
      address: 'So11111111111111111111111111111111111111112',
      cluster: 'devnet',
    });
    await Promise.resolve();
    expect(paySheetSpy).not.toHaveBeenCalled();

    wireSpy.mockRestore();
  });

  it('skips amount spans outside an active chat, in formatted or mesh text, and keeps search <mark> inside the span', () => {
    useMessengerStore.setState({ activeChatId: undefined, chats: [] });
    const noChatView = render(<FormattedMessageText text="$23" amountsTappable />);
    expect(noChatView.container.querySelector('[data-amount]')).toBeNull();
    cleanup();

    seedDmChat('peer-olena');
    const boldView = render(<FormattedMessageText text="**$23**" amountsTappable />);
    expect(boldView.container.querySelector('[data-amount]')).toBeNull();
    cleanup();

    const spy = vi.spyOn(peerWireMod, 'readPeerWire').mockReturnValue({ kind: 'control' } as never);
    try {
      const meshView = render(<FormattedMessageText text="$23" amountsTappable />);
      expect(meshView.getByTestId('mock-peer-wire-bubble')).toBeTruthy();
      expect(meshView.container.querySelector('[data-amount]')).toBeNull();
    } finally {
      spy.mockRestore();
    }
    cleanup();

    const searchView = render(<FormattedMessageText text="ти винен $23" searchQuery="23" amountsTappable />);
    const amountSpan = searchView.container.querySelector('[data-amount="USD"]');
    expect(amountSpan).toBeTruthy();
    const mark = amountSpan?.querySelector('mark');
    expect(mark?.textContent).toBe('23');
  });
});
