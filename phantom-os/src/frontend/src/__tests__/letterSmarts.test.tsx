/**
 * Дія, справді виведена з листа (час, номер посилки, телефон), живе на
 * довгому дотику по цьому листу — «Нагадати о 19:40», «Скопіювати номер»
 * (дизайн-система §7.4). Карток-підказок у стрічці, карток «Чекають на вас»
 * над списком і відповідей-здогадок над композером більше немає (AUDIT №2).
 * Усе з тексту листа, без мережі.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ChatArea } from '../components/messenger/ChatArea';
import { resetHintsDone } from '../services/letterActions';
import { disarmReminders, pendingReminders } from '../services/letterReminders';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message } from '../types/messenger';

// Неділя, 27.09.2026, 19:24 місцевого.
const NOW = new Date(2026, 8, 27, 19, 24);
const SENT = new Date(2026, 8, 27, 19, 20).toISOString();

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  localStorage.clear();
  resetHintsDone();
  useMessengerStore.setState({ typingStatus: {} });
});

afterEach(() => {
  disarmReminders();
  vi.useRealTimers();
});

const noop = () => {};

const letter = (id: string, text: string, over: Partial<Message> = {}): Message =>
  ({
    id,
    senderId: 'oksana',
    senderName: 'Оксана',
    senderAvatar: '',
    timestamp: '19:20',
    sentAt: SENT,
    type: 'text',
    text,
    ...over,
  }) as Message;

const chat = (messages: Message[], over: Partial<Chat> = {}): Chat =>
  ({
    id: 'chat_oksana',
    title: 'Оксана',
    avatar: '',
    type: 'dm',
    circle: 'all',
    unreadCount: 1,
    messages,
    peerNodeId: 'a1b2c3d4e5f60718',
    lastAt: SENT,
    ...over,
  }) as unknown as Chat;

const talk = (messages: Message[]) =>
  render(
    <MemoryRouter>
      <ChatArea
        currentChat={chat(messages)}
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

const longPress = (r: ReturnType<typeof talk>, id: string) => {
  const bubble = r.container.querySelector(`#message-${id} .msg-bubble-fluid`) as HTMLElement;
  fireEvent.contextMenu(bubble);
};

describe('дія з листа — на довгому дотику, не карткою в стрічці', () => {
  it('у стрічці карток-підказок немає зовсім', () => {
    const r = talk([letter('m1', 'Посилка чекає до 20:00'), letter('m2', 'Номер 2045 1188 3302')]);
    expect(r.container.querySelector('[data-letter-hint]')).toBeNull();
    expect(r.container.textContent).not.toMatch(/Нагадати о|Номер посилки 2045/);
  });

  it('«до 20:00» → у меню листа «Нагадати о 19:40»; дотик ставить нагадування', async () => {
    const r = talk([letter('m1', 'Посилка чекає до 20:00')]);
    longPress(r, 'm1');
    await act(async () => {
      fireEvent.click(r.getByText('Нагадати о 19:40'));
    });
    expect(pendingReminders()).toEqual([
      expect.objectContaining({ at: new Date(2026, 8, 27, 19, 40).getTime(), who: 'Оксана', chatId: 'chat_oksana' }),
    ]);
  });

  it('голосове: номер із розшифровки → «Скопіювати номер»', () => {
    const r = talk([
      letter('v1', '', { type: 'voice', voiceData: { duration: 12, transcript: 'Номер 2045 1188 3302, скажеш на касі' } } as Partial<Message>),
    ]);
    longPress(r, 'v1');
    expect(r.getByText('Скопіювати номер')).toBeTruthy();
  });

  it('лист без дії — у меню зайвого рядка немає', () => {
    const r = talk([letter('m1', 'Сиджу в кавʼярні навпроти пошти')]);
    longPress(r, 'm1');
    expect(r.queryByText(/Нагадати|Скопіювати номер|Скопіювати телефон/)).toBeNull();
  });

  it('ні на ПК, ні на телефоні під листом немає «AI: Доручення» чи «В Kanban»', () => {
    const q = [letter('m1', 'Номер 2045 1188 3302. Встигнеш до 20:00?')];
    const pc = talk(q);
    expect(pc.queryByText(/AI: Доручення|В Kanban/)).toBeNull();
    pc.unmount();
    (window as unknown as { PhantomBridge: unknown }).PhantomBridge = {};
    const phone = talk(q);
    expect(phone.queryByText(/AI: Доручення|В Kanban/)).toBeNull();
    delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
  });
});
