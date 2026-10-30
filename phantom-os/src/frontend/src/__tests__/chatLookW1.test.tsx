/**
 * W1 critic on the conversation: the long-press stack has no slab of its own;
 * the feed lies on flat bg with no relief seeded from where the person is;
 * the bill reads at 300 without the token on every line; the plan card is
 * compact (one people line, one chip row) and speaks of hiding only in a
 * group made for the plan; a card's time sits under the card, not the avatar.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { MessageActionSheet, type MessageSheetHandlers } from '../components/messenger/feed/MessageActionSheet';
import { BillCard } from '../components/messenger/meetup/BillCard';
import { MeetupCard } from '../components/messenger/meetup/MeetupCard';
import { ChatArea } from '../components/messenger/ChatArea';
import { useMessengerStore } from '../stores/messengerStore';
import { sweepMeetupGroups } from '../services/meetupExpirySweep';
import { messageFromNode, type NodeMessage } from '../services/messengerApi';
import type { Chat, Message } from '../types/messenger';
import { AT, ME, OLENA, PETRO, billMsg, installBridge, meetupMsg, removeBridge } from './meetupFixtures';

const src = (p: string) => readFileSync(join(__dirname, '..', p), 'utf8');
let prev: ReturnType<typeof useMessengerStore.getState>;

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

beforeEach(() => {
  prev = useMessengerStore.getState();
});

afterEach(() => {
  cleanup();
  removeBridge();
  vi.useRealTimers();
  useMessengerStore.setState(prev, true);
  localStorage.clear();
});

const seed = (messages: Message[], me = ME, extra: Partial<Chat> = {}) =>
  useMessengerStore.setState({
    chats: [{ id: 'grp', type: 'group', title: 'Cafe X · 19:00', messages, unreadCount: 0, ...extra } as never],
    activeChatId: 'grp',
    currentUser: { ...useMessengerStore.getState().currentUser, id: me, name: me },
  });

describe('long-press: the finger stays down', () => {
  const theirs = { id: 'm1', senderId: 'oksana', senderName: 'Оксана', senderAvatar: '', timestamp: '16:58', type: 'text', text: 'booked' } as Message;
  const on = new Proxy({}, { get: () => () => {} }) as MessageSheetHandlers;

  it('the menu lives outside the root, so it bars text selection and the system callout itself', () => {
    render(<MessageActionSheet msg={theirs} currentUserId="me" speaking={false} onClose={() => {}} on={on} />);
    const layer = screen.getByRole('dialog').parentElement as HTMLElement;
    expect(layer.className).toContain('select-none');
    const ev = new MouseEvent('contextmenu', { bubbles: true, cancelable: true });
    screen.getByText(/Reply|Відповісти/).dispatchEvent(ev);
    expect(ev.defaultPrevented).toBe(true);
  });
});

describe('long-press: no slab', () => {
  const theirs = { id: 'm1', senderId: 'oksana', senderName: 'Оксана', senderAvatar: '', timestamp: '16:58', type: 'text', text: 'booked' } as Message;
  const on = new Proxy({}, { get: () => () => {} }) as MessageSheetHandlers;

  it('the stack is transparent and flat: only the scrim behind it', () => {
    render(<MessageActionSheet msg={theirs} currentUserId="me" speaking={false} onClose={() => {}} on={on} />);
    const stack = screen.getByRole('dialog');
    expect(stack.className).not.toMatch(/(^|\s)f-dialog(\s|$)/);
    expect(stack.className).toContain('bg-transparent');
    expect(stack.className).toContain('shadow-none');
    expect(stack.className).toContain('border-0');
  });

  it('Delete is text-1 as in mockup 05, not danger red', () => {
    render(<MessageActionSheet msg={theirs} currentUserId="me" speaking={false} onClose={() => {}} on={on} />);
    const del = within(screen.getByRole('dialog')).getByText('Видалити').closest('button')!;
    expect(del.className).toMatch(/(^|\s)text-ink(\s|$)/);
    expect(del.outerHTML).not.toContain('text-danger');
  });
});

describe('conversation background', () => {
  it('no relief behind the feed or the sign-in screen, nothing seeded from my place', () => {
    expect(src('components/messenger/MessengerRoot.tsx')).not.toMatch(/TerrainLayer/);
    expect(src('components/auth/LoginScreen.tsx')).not.toMatch(/TerrainLayer/);
    const layer = src('components/messenger/TerrainLayer.tsx');
    expect(layer).not.toMatch(/export const TerrainLayer\b/);
    expect(layer).not.toMatch(/currentSunPlace/);
    expect(src('components/messenger/feed/VirtualFeed.tsx')).not.toMatch(/data-terrain/);
  });
});

describe('bill at 300', () => {
  const MARKS = { 'dish:latte': [OLENA], 'dish:cake': [OLENA, PETRO], 'dish:soup': [ME] };

  it('amounts without the token; the payer line shows its share; nothing truncated', () => {
    installBridge(() => undefined);
    seed([billMsg({ cardMarks: MARKS })], PETRO);
    render(<BillCard msg={useMessengerStore.getState().chats[0].messages[0]} meId={PETRO} />);
    const due = (id: string) => screen.getByTestId(`bill-person-${id}`).querySelector('[data-bill-due]')?.textContent;
    expect(due(OLENA)).toBe('5,50');
    expect(due(ME)).toBe('6,00');
    expect(screen.getByTestId('bill-card-pay').textContent).toBe('Сплатити 2,00');
    expect(screen.getByTestId('bill-card-share').textContent).toBe('Ваша частка 2,00');
    expect(screen.getByTestId('card-status').textContent).toContain('USDC');
    expect(screen.getByTestId('card-bill').querySelector('li .truncate, [data-testid="bill-card-share"].truncate')).toBeNull();
  });
});

describe('plan card, compact', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(AT - 2 * 3600_000));
  });

  const draw = (msgs: Message[], extra: Partial<Chat> = {}) => {
    seed(msgs, ME, extra);
    const m = msgs.find((x) => x.type === 'widget:meetup')!;
    return render(<MeetupCard msg={m} meId={ME} />);
  };

  it('one people line with a facepile, no per-person rows until it is opened', () => {
    draw([meetupMsg({ cardMarks: { 'rsvp:yes': [ME, OLENA, PETRO] } })]);
    const line = screen.getByTestId('meetup-people');
    expect(line.querySelectorAll('[aria-label]').length).toBe(3);
    expect(line.textContent).toContain('Ви, Олена, Петро');
    expect(screen.queryByTestId(`meetup-person-${OLENA}`)).toBeNull();
    fireEvent.click(line);
    expect(screen.getByTestId(`meetup-person-${OLENA}`).textContent).toContain('іде');
  });

  it('going: "✓ Іду" selected in the segment, one icon action row that never scrolls', () => {
    draw([meetupMsg({ cardMarks: { 'rsvp:yes': [ME] } })]);
    const mine = screen.getByTestId('meetup-rsvp-yes');
    expect(mine.textContent).toBe('Іду');
    expect(mine.className).toMatch(/(^|\s)text-ink(\s|$)/);
    expect(mine.querySelector('svg')).toBeTruthy();
    const rows = screen.getAllByTestId('meetup-actions');
    expect(rows).toHaveLength(1);
    expect(rows[0].className).not.toMatch(/overflow-x-auto/);
    expect(rows[0].querySelectorAll('button').length).toBeLessThanOrEqual(5);
  });

  it('meta starts with a capital: "Сьогодні", not "сьогодні"', () => {
    draw([meetupMsg()]);
    const meta = screen.getByTestId('card-status').textContent ?? '';
    expect(meta[0]).toBe(meta[0].toLocaleUpperCase('uk-UA'));
    expect(meta).toContain('19:00');
  });

  it('a permanent group with older talk never says it will be hidden', () => {
    const older = { id: 'old', senderId: OLENA, senderName: 'Олена', senderAvatar: '', timestamp: '10:00', type: 'text', text: 'hi', sentAt: new Date(AT - 86400_000).toISOString() } as Message;
    draw([older, meetupMsg()]);
    expect(screen.queryByTestId('meetup-expiry')).toBeNull();
  });

  it('the group made for the plan still offers Keep', () => {
    draw([meetupMsg()]);
    expect(screen.getByTestId('meetup-expiry')).toBeTruthy();
  });

  it('the sweep never hides a permanent group either', () => {
    vi.setSystemTime(new Date(AT + 25 * 3600_000));
    const older = { id: 'old', senderId: OLENA, senderName: 'Олена', senderAvatar: '', timestamp: '10:00', type: 'text', text: 'hi', sentAt: new Date(AT - 86400_000).toISOString() } as Message;
    seed([older, meetupMsg()]);
    expect(sweepMeetupGroups(Date.now())).toEqual([]);
  });
});

describe('card time in the feed', () => {
  const noop = () => {};
  const group = { id: 'grp', title: 'Friday pho', avatar: '', type: 'group', circle: 'friends', unreadCount: 0, messages: [] } as unknown as Chat;
  const feed = (messages: Message[]) => {
    seed(messages);
    return render(
      <MemoryRouter>
        <ChatArea
          currentChat={group}
          messages={messages}
          currentUserId={ME}
          onOpenLocation={noop}
          onVotePoll={noop}
          onPayBillShare={noop}
          onAddReaction={noop}
          onReplyMessage={noop}
          selectedMessageIds={[]}
          onToggleSelectMessage={noop}
          isSelectionMode={false}
          isSearching={false}
          onCloseSearch={noop}
        />
      </MemoryRouter>,
    );
  };

  it("someone else's card: the time sits inside the card's meta row (MSG-SYS anatomy), nothing under it", () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(AT - 2 * 3600_000));
    const { container } = feed([meetupMsg({ senderId: OLENA, senderName: 'Олена', isSelf: false, meetupData: { ...useMeetup(), organizer: OLENA } })]);
    const meta = container.querySelector('#message-row-mu [data-card-meta]') as HTMLElement;
    expect(meta.textContent).toContain('18:00');
    expect(container.querySelector('#message-row-mu [data-msg-meta-chip]')).toBeNull();
  });

  it("T45: someone else's card thread pill stands under the card too, not under the avatar", () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(AT - 2 * 3600_000));
    const card = meetupMsg({ senderId: OLENA, senderName: 'Олена', isSelf: false, meetupData: { ...useMeetup(), organizer: OLENA } });
    const reply = { id: 'r1', senderId: ME, senderName: 'Я', senderAvatar: '', timestamp: '18:02', type: 'text', text: 'ok', isSelf: true, status: 'sent', replyTo: { id: card.id, senderName: 'Олена', text: '' } } as unknown as Message;
    const { container } = feed([card, reply]);
    const pill = container.querySelector(`[data-testid="thread-pill-${card.id}"]`) as HTMLElement;
    expect(pill).toBeTruthy();
    expect((pill.closest('[data-msg-thread-line]') as HTMLElement | null)?.className).toMatch(/(^|\s)pl-9(\s|$)/);
  });

  it('my card followed by my text still shows its time and ticks', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(AT - 2 * 3600_000));
    const after = { id: 'm2', senderId: ME, senderName: 'Я', senderAvatar: '', timestamp: '18:01', type: 'text', text: 'cash for the tip', isSelf: true, status: 'sent' } as Message;
    const { container } = feed([meetupMsg({ status: 'sent' }), after]);
    const meta = container.querySelector('#message-row-mu [data-card-meta]') as HTMLElement;
    expect(meta.textContent).toContain('18:00');
    expect(meta.querySelector('svg')).toBeTruthy();
  });
});

describe('day pill over the feed', () => {
  it('the stuck day is a solid surface-2 pill, 4×10, never see-through over a card', () => {
    const css = src('styles/identity.css').replace(/\/\*[\s\S]*?\*\//g, '');
    const at = css.indexOf('.ph-day-pill-float {');
    const rule = css.slice(at, css.indexOf('}', at));
    expect(rule).toContain('background-color: var(--ph-raised)');
    expect(rule).toContain('border-radius: 9999px');
    expect(rule).toContain('padding: 4px 10px');
  });
});

describe('ticks on my own group lines', () => {
  const row = (over: Partial<NodeMessage>): NodeMessage => ({
    id: 'g1', conversation_id: 'grp', client_id: 'c1', seq: 1,
    author_id: 'me', author_name: 'Я', kind: 'text', body: 'cash for the tip',
    ciphertext: null, transport: null, sent_at: '2026-10-09T23:59:00',
    edited_at: null, deleted_at: null, ...over,
  });

  it('a group line from the PC node (no fan numbers) still carries its delivery state', () => {
    expect(messageFromNode(row({ delivery_state: 'sent' }), 'me').status).toBe('sent');
    expect(messageFromNode(row({ delivery_state: 'queued' }), 'me').status).toBe('queued');
  });

  it('a local-only line (saved, assistant) and anyone else\'s line get no tick', () => {
    expect(messageFromNode(row({ delivery_state: 'local' }), 'me').status).toBeUndefined();
    expect(messageFromNode(row({}), 'me').status).toBeUndefined();
    expect(messageFromNode(row({ author_id: 'p1', delivery_state: 'sent' }), 'me').status).toBeUndefined();
  });
});

function useMeetup() {
  return meetupMsg().meetupData!;
}
