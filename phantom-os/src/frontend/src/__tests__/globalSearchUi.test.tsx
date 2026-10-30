/**
 * Пошук у шапці списку телефона: групи, порожній стан, стрибок до листа.
 *
 * Стрибок доводиться в ДВОХ місцях, бо ламається він у двох: список мусить
 * передати id листа (а не лише розмову), а стрічка мусить дочекатися, доки
 * лист доїде з вузла, і лише тоді прокрутити й підсвітити.
 */
import { beforeAll, describe, it, expect, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { ChatArea } from '../components/messenger/ChatArea';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message, UserProfile } from '../types/messenger';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;

const msg = (id: string, text: string, over: Partial<Message> = {}): Message =>
  ({
    id, senderId: 'p1', senderName: 'Петро', senderAvatar: '', timestamp: '12:00',
    type: 'text', text, isSelf: false, sentAt: '2026-09-20T10:00:00Z', ...over,
  }) as Message;

const chat = (id: string, title: string, messages: Message[] = [], extra: Partial<Chat> = {}): Chat =>
  ({
    id, title, type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false,
    messages, lastAt: '2026-09-20T10:00:00Z', ...extra,
  }) as unknown as Chat;

const CHATS = [
  chat('c1', 'Оксана', [msg('m1', 'Так, чекаю біля входу')], { peerNodeId: 'p-oks' }),
  chat('g1', 'Двір', [msg('m2', 'Збори о шостій біля третього підʼїзду'), msg('m3', 'ок')], { type: 'group' }),
  chat('chat_saved_notes', 'Збережене', [msg('n1', 'Код від під\'їзду 4712', { isSelf: true })], { type: 'saved', pinned: true }),
];

const openSearch = (onOpenMessage = vi.fn(), onSelectChat = vi.fn()) => {
  render(
    <PhoneChatList
      chats={CHATS}
      currentUser={USER}
     
      onSelectChat={onSelectChat}
      onNewChat={vi.fn()}
      onOpenChatActions={vi.fn()}
      onOpenMessage={onOpenMessage}
    />,
  );
  fireEvent.click(screen.getByLabelText('Пошук у розмовах'));
  // Індекс будується в простої — у тесті даємо йому дорости.
  return { onOpenMessage, onSelectChat };
};

const type = async (text: string) => {
  fireEvent.change(screen.getByLabelText('Пошук по всіх розмовах'), { target: { value: text } });
  await act(async () => {
    await new Promise((r) => setTimeout(r, 30));
  });
};

describe('пошук по всіх розмовах — список телефона', () => {
  it('порожній запит: без прози, лише місце під результати', async () => {
    openSearch();
    await type('');
    const hint = document.querySelector('[data-search-empty]')!;
    expect(hint).toBeTruthy();
    expect(hint.textContent).toBe('');
  });

  it('листи з усіх розмов, разом зі «Збереженим», з апострофом будь-якого роду', async () => {
    openSearch();
    await type("підʼїзд");
    const letters = [...document.querySelectorAll('[data-search-letter]')].map((e) => e.getAttribute('data-search-letter'));
    expect(letters.sort()).toEqual(['m2', 'n1']);
    // Збіг підсвічено ОРИГІНАЛОМ, а не запитом.
    const marks = [...document.querySelectorAll('mark[data-search-hit]')].map((m) => m.textContent);
    expect(marks).toContain('підʼїзд');
    expect(screen.getByRole('region', { name: 'Листи' })).toBeTruthy();
  });

  it('«Люди» — окремою групою над «Листами»', async () => {
    openSearch();
    await type('оксан');
    expect(document.querySelector('[data-search-person="c:c1"]')).toBeTruthy();
    expect(screen.getByRole('region', { name: 'Люди' })).toBeTruthy();
  });

  it('нічого не знайдено: мітка з тим, що шукалось, без речення', async () => {
    openSearch();
    await type('ґанок');
    const none = document.querySelector('[data-search-none]')!;
    expect(none.querySelector('p')!.textContent).toBe('Нічого за «ґанок»');
  });

  it('видача без лічильників і службових слів', async () => {
    openSearch();
    await type('шост');
    expect(document.querySelector('[data-search-results]')!.textContent).not.toMatch(/Переглянуто|телефоні|\d+ лист/);
  });

  it('дотик по листу передає розмову І лист', async () => {
    const { onOpenMessage } = openSearch();
    await type('шост');
    fireEvent.click(document.querySelector('[data-search-letter="m2"]')!);
    expect(onOpenMessage).toHaveBeenCalledWith('g1', 'm2');
  });

  it('без пропа — стрибок іде через стор: розмова відкрита, намір записано', async () => {
    const setActive = vi.fn();
    useMessengerStore.setState({ setActiveChat: setActive, pendingJump: null });
    render(
      <PhoneChatList chats={CHATS} currentUser={USER} onSelectChat={vi.fn()}
        onNewChat={vi.fn()} onOpenChatActions={vi.fn()} />,
    );
    fireEvent.click(screen.getByLabelText('Пошук у розмовах'));
    await type('шост');
    fireEvent.click(document.querySelector('[data-search-letter="m2"]')!);
    expect(useMessengerStore.getState().pendingJump).toEqual({ chatId: 'g1', messageId: 'm2' });
    expect(setActive).toHaveBeenCalledWith('g1');
  });
});

describe('стрічка стрибає до знайденого листа', () => {
  const noop = () => {};
  const area = (messages: Message[], jump: string | undefined, onJumped: () => void) => (
    <MemoryRouter>
      <ChatArea
        currentChat={CHATS[1]}
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
        jumpToMessageId={jump}
        onJumped={onJumped}
      />
    </MemoryRouter>
  );

  const long = Array.from({ length: 400 }, (_, i) => msg(`k${i}`, `лист ${i}`));

  it('чекає, доки стрічка доїде, а тоді прокручує до листа за межею вікна й підсвічує', async () => {
    const onJumped = vi.fn();
    const spy = vi.spyOn(Element.prototype, 'scrollIntoView');
    const { rerender } = render(area([], 'k3', onJumped));
    // Стрічки ще немає — намір не згорає.
    expect(onJumped).not.toHaveBeenCalled();
    rerender(area(long, 'k3', onJumped));
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
    expect(onJumped).toHaveBeenCalledTimes(1);
    const el = document.getElementById('message-k3');
    expect(el).toBeTruthy();
    expect(spy.mock.instances).toContain(el);
    spy.mockRestore();
  });
});
