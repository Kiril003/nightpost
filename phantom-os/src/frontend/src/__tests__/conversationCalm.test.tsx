/**
 * Екран розмови говорить людською мовою.
 *
 * Власник 26.09: «навіщо мені як користувачу щось чути про відбитки або
 * решту? … людині треба показувати щось гарне а не цю нісенітницю». Тут
 * стережемо дві поверхні, де технічне жило щодня: рядок під іменем у шапці
 * («ключ не звірено» бурштином у кожного незвіреного) і слова дороги під
 * власними бульбашками («лежить у сховку», «у дорозі через хмару», «чекає
 * передачі»). Безпечна частина лишається: невдала відправка й далі видна —
 * одним спокійним рядком із «Повторити».
 */
import { beforeAll, beforeEach, describe, it, expect, vi } from 'vitest';
import { render, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ChatArea } from '../components/messenger/ChatArea';
import { Header } from '../components/messenger/Header';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message, SecureMedia, UserProfile } from '../types/messenger';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

beforeEach(() => {
  useMessengerStore.setState({ typingStatus: {} });
});

const TECH = /ключ|звір|відбит|шифр|вузл|relay|ретранс|напряму|сховк|сховок|хмар|передачі|відправки|у дорозі|не пішло/i;

const noop = () => {};

const dm = (over: Partial<Chat> = {}): Chat =>
  ({
    id: 'chat_dm_peer',
    title: 'Оксана',
    avatar: '',
    type: 'dm',
    circle: 'all',
    pinned: false,
    unreadCount: 0,
    messages: [],
    peerNodeId: 'a1b2c3d4e5f60718',
    ...over,
  }) as unknown as Chat;

const header = (chat: Chat) =>
  render(
    <MemoryRouter>
      <Header
        currentChat={chat}
        currentUser={{ id: 'me', name: 'Я' } as unknown as UserProfile}
        onOpenSettings={noop}
        onOpenGroupDetails={noop}
        isSoundEnabled
        onToggleSound={noop}
        onToggleSearch={noop}
        isSearching={false}
      />
    </MemoryRouter>,
  );

describe('шапка розмови', () => {
  it('незвірений і звірений контакт — жодного слова про ключ', () => {
    for (const contactVerified of [false, true]) {
      const { container, unmount } = header(dm({ contactVerified }));
      expect(container.querySelector('header')!.textContent).not.toMatch(TECH);
      unmount();
    }
  });

  it('друкує — словом під іменем', () => {
    useMessengerStore.setState({ typingStatus: { chat_dm_peer: 'Оксана друкує' } });
    const { getByTestId } = header(dm({ contactVerified: false }));
    expect(getByTestId('header-typing').textContent).toBe('друкує…');
  });

  it('група — скільки людей, і нічого більше', () => {
    const { container } = header(
      dm({ type: 'group', peerNodeId: undefined, members: [{}, {}, {}] as never }),
    );
    expect(container.textContent).toContain('3 учасники');
  });
});

const media = (): SecureMedia => ({
  name: 'photo.png',
  size: 1024,
  mime: 'image/png',
  sha256: 'a'.repeat(64),
  blobId: 'blob_photo',
  keyHex: 'ab'.repeat(32),
  nonceHex: 'cd'.repeat(12),
});

const own = (id: string, over: Partial<Message>): Message =>
  ({
    id,
    senderId: 'me',
    senderName: 'Я',
    senderAvatar: '',
    timestamp: '14:32',
    type: 'text',
    text: `лист ${id}`,
    isSelf: true,
    status: 'sent',
    ...over,
  }) as unknown as Message;

const feed = (messages: Message[], chat: Chat = dm()) =>
  render(
    <MemoryRouter>
      <ChatArea
        currentChat={chat}
        messages={messages}
        currentUserId="me"
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

describe('бульбашки — без слів дороги', () => {
  const stuck = [
    own('m1', { status: 'sending' }),
    own('m2', { status: 'queued', relayState: 'local' } as Partial<Message>),
    own('m3', { status: 'queued' }),
    own('m4', { status: 'queued', type: 'file', media: media(), attachmentState: 'parked' } as Partial<Message>),
    own('m5', { status: 'queued', type: 'file', media: media(), attachmentState: 'queued' } as Partial<Message>),
  ];

  it('жоден стан доставки не каже, ЯКОЮ дорогою їде лист', () => {
    const { container } = feed(stuck);
    expect(container.textContent).not.toMatch(TECH);
    expect(container.querySelectorAll('[data-msg-status-note]')).toHaveLength(0);
  });

  it('те, що ще не доставлено, позначене годинником біля часу', () => {
    const { container } = feed(stuck);
    expect(container.querySelectorAll('[data-msg-pending]').length).toBeGreaterThan(0);
  });

  it('невдала відправка — `circle-alert` і «Не надіслано · Повторити» біля часу (§7.4); дотик повторює', () => {
    const retrySend = vi.fn().mockResolvedValue(undefined);
    useMessengerStore.setState({ retrySend } as never);
    const { getByTestId, container } = feed([own('f1', { status: 'failed' })]);
    const mark = getByTestId('msg-failed');
    expect(mark.closest('.msg-meta-row, [data-msg-status-line]')).not.toBeNull();
    expect(mark.className).toContain('text-danger');
    expect(mark.textContent).toBe('Не надіслано · Повторити');
    expect(container.textContent).not.toMatch(TECH);
    fireEvent.click(mark);
    expect(retrySend).toHaveBeenCalledWith('f1');
  });

  it('доставлено — одна галочка, прочитано — дві, знаками, а не словами', () => {
    const { container } = feed([own('s1', { status: 'sent' }), own('s2', { status: 'sent', readAt: new Date().toISOString() } as Partial<Message>)]);
    const ticks = [...container.querySelectorAll('[data-msg-tick]')].map((t) => t.getAttribute('data-msg-tick'));
    expect(ticks).toContain('read');
    for (const t of container.querySelectorAll('[data-msg-tick]')) {
      expect(t.querySelector('svg')).not.toBeNull();
      expect(t.textContent).not.toMatch(/доставлено|прочитано/);
    }
  });

  it('у групі «N відповідь» сидить у самій бульбашці; у розмові вдвох відповідь — лише цитата, без лічильника', () => {
    const root = own('r1', { senderId: 'peer', isSelf: false, text: 'питання' });
    const reply = own('r2', { replyTo: { id: 'r1', senderName: 'Оксана', text: 'питання' } } as Partial<Message>);
    const g = feed([root, reply], dm({ id: 'g1', type: 'group', peerNodeId: undefined } as Partial<Chat>));
    const pill = g.getByTestId('thread-pill-r1');
    expect(pill.closest('.msg-bubble-fluid')).not.toBeNull();
    g.unmount();
    const d = feed([root, reply]);
    expect(d.queryByTestId('thread-pill-r1')).toBeNull();
  });
});
