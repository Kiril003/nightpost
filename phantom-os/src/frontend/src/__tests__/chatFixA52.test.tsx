/**
 * A52 sweep 11.10: a card's time floated on the wallpaper; the assistant's
 * "Drafted: Daniel · 5 USDC" had nothing to press once the transfer screen
 * was gone; a short line put its time on a line of its own under a gap.
 */
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ChatArea } from '../components/messenger/ChatArea';
import { fitWidth } from '../components/messenger/feed/bubbleFit';
import { messageFromNode, type NodeMessage } from '../services/messengerApi';
import { solanaApi } from '../services/solanaActions';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message } from '../types/messenger';
import { AT, ME, meetupMsg } from './meetupFixtures';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

const noop = () => {};
const feed = (chat: Chat, messages: Message[], me = 'me') =>
  render(
    <MemoryRouter>
      <ChatArea currentChat={chat} messages={messages} currentUserId={me} onOpenLocation={noop} onVotePoll={noop}
        onPayBillShare={noop} onAddReaction={noop} onReplyMessage={noop} selectedMessageIds={[]}
        onToggleSelectMessage={noop} isSelectionMode={false} isSearching={false} onCloseSearch={noop} />
    </MemoryRouter>,
  );

describe('a card keeps its time on a solid surface', () => {
  it('group plan card: time and ticks sit inside the card, not bare on the wallpaper', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(AT - 2 * 3600_000));
    const group = { id: 'grp', title: 'Friday pho', type: 'group', unreadCount: 0, messages: [] } as unknown as Chat;
    const prev = useMessengerStore.getState();
    useMessengerStore.setState({ chats: [{ ...group, messages: [] } as never], activeChatId: 'grp' });
    const { container } = feed(group, [meetupMsg({ status: 'sent' })], ME);
    const meta = container.querySelector('#message-row-mu [data-card-meta]') as HTMLElement;
    expect(meta).toBeTruthy();
    expect(meta.textContent).toContain('18:00');
    expect(meta.querySelector('svg')).toBeTruthy();
    expect(meta.closest('[data-kit="ticket-card"]')!.className).toMatch(/(^|\s)bg-surface(\s|$)/);
    expect(container.querySelector('#message-row-mu [data-msg-meta-chip]')).toBeNull();
    useMessengerStore.setState(prev, true);
  });
});

describe('the assistant draft never dead-ends', () => {
  const node = (over: Partial<NodeMessage> = {}) =>
    ({
      id: 'd1', conversation_id: 'chat_phantom_assistant', client_id: 'c', seq: 1, author_id: 'phantom_agent', author_name: 'Nightpost',
      kind: 'text', body: 'Drafted: Daniel · 5 USDC. Confirm with your fingerprint — I never send anything myself.',
      ciphertext: null, transport: null, sent_at: '2026-10-11T10:00:00Z', edited_at: null, deleted_at: null, ...over,
    }) as NodeMessage;
  const assistant = { id: 'chat_phantom_assistant', title: 'Nightpost', type: 'dm', messages: [], unreadCount: 0 } as unknown as Chat;

  it('the bridge row carries the draft; without it the bubble stays plain', () => {
    const m = messageFromNode(node({ pay_draft: { peer_id: 'daniel', token: 'USDC', amount: '5', sent: false } }), 'me');
    expect(m.payDraft).toEqual({ peerId: 'daniel', token: 'USDC', amount: '5', sent: false });
    expect(messageFromNode(node(), 'me').payDraft).toBeUndefined();
  });

  it('a draft not sent says so and "Draft again" reopens the fingerprint screen with the same person, coin and amount', () => {
    const pay = vi.spyOn(solanaApi, 'pay').mockResolvedValue({ opened: true });
    const m = messageFromNode(node({ pay_draft: { peer_id: 'daniel', token: 'USDC', amount: '5', sent: false } }), 'me');
    const { container } = feed(assistant, [m]);
    const block = container.querySelector('[data-pay-draft]') as HTMLElement;
    expect(block.textContent).toContain('Ще не надіслано');
    fireEvent.click(block.querySelector('[data-pay-draft-again]') as HTMLElement);
    expect(pay).toHaveBeenCalledWith({ peer_id: 'daniel', token: 'USDC', amount: '5' });
  });

  it('a draft already paid says "Already sent" instead of pretending it waits', () => {
    const m = messageFromNode(node({ pay_draft: { peer_id: 'daniel', token: 'USDC', amount: '5', sent: true } }), 'me');
    const { container } = feed(assistant, [m]);
    expect((container.querySelector('[data-pay-draft]') as HTMLElement).textContent).toContain('Уже надіслано');
  });
});

describe('a short line keeps its time beside it', () => {
  const box = (left: number, right: number, top: number) => ({ left, right, top, bottom: top + 20 });

  it('fits to the widest text line only when the time fell onto its own line', () => {
    expect(fitWidth([box(10, 190, 0)], box(200, 240, 0), 24)).toBeNull();
    expect(fitWidth([box(10, 190, 0)], box(10, 50, 22), 24)).toBe(204);
    expect(fitWidth([box(10, 250, 0), box(10, 120, 22)], box(130, 170, 22), 24)).toBeNull();
    expect(fitWidth([], box(10, 50, 22), 24)).toBeNull();
    expect(fitWidth([box(10, 190, 0)], null, 24)).toBeNull();
  });

  it('the feed shrinks the bubble to the text when the time wrapped', () => {
    const range = Range.prototype as Range & { getClientRects?: () => DOMRectList };
    const had = range.getClientRects;
    range.getClientRects = () => [{ left: 60, right: 260, top: 100, bottom: 120, width: 200 }] as unknown as DOMRectList;
    const real = HTMLElement.prototype.getBoundingClientRect;
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      return this.hasAttribute('data-meta-space')
        ? ({ left: 60, right: 110, top: 124, bottom: 144, width: 50, height: 20 } as DOMRect)
        : real.call(this);
    });
    try {
      const chat = { id: 'grp', title: 'Friday pho', type: 'group', unreadCount: 0, messages: [] } as unknown as Chat;
      const m = { id: 's1', senderId: 'marko', senderName: 'Marko', senderAvatar: '', timestamp: '07:03', type: 'text', text: 'In! Let us ask Marko and Iva too', isSelf: false } as Message;
      const { container } = feed(chat, [m]);
      const spacer = container.querySelector('[data-meta-space]') as HTMLElement;
      expect(spacer).toBeTruthy();
      const bubble = spacer.closest('.msg-bubble-fluid') as HTMLElement;
      expect(bubble.style.width).toBe('200px');
    } finally {
      if (had) range.getClientRects = had;
      else Reflect.deleteProperty(range, 'getClientRects');
    }
  });
});
