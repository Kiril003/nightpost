/**
 * Скарга власника: «повідомлення розтягує». Справжня причина — не відсутність
 * `break-words` (він уже стояв на тексті), а класична халепа флекс-контейнера:
 * бульбашка (`.msg-bubble-fluid`) — елемент РЯДКА «аватар + бульбашка», і без
 * `min-w-0` браузер рахує мінімальну ширину елемента від НЕРОЗБИТОГО токена
 * (посилання, хеш, імʼя файла без пробілів), а не від зламаного правилом
 * `overflow-wrap`. Рядок росте під довгий токен, а з ним і вся стрічка —
 * `max-w-full`/`break-words` тут безсилі: то стеля згори, рветься підлога.
 *
 * jsdom не рахує реальний layout (усі ширини — 0), тож пиксельне
 * непереповнення тут не довести юніт-тестом; те, що можна й треба довести —
 * що потрібний клас справді СТОЇТЬ на елементі, який браузер трактує як
 * флекс-елемент. Реальний вимір без переносу вбік — окремим Playwright-тестом
 * `e2e/phone-fitness.spec.ts` (той самий сценарій із довгим посиланням і
 * довгим рядком уже в його фікстурі).
 */
import { beforeAll, describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ChatArea } from '../components/messenger/ChatArea';
import type { Chat, Message } from '../types/messenger';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

const CHAT = {
  id: 'chat_dm_peer',
  title: 'Оксана',
  avatar: '',
  type: 'dm',
  circle: 'all',
  pinned: false,
  unreadCount: 0,
  messages: [],
} as unknown as Chat;

const noop = () => {};

function conversation(messages: Message[]) {
  return render(
    <MemoryRouter>
      <ChatArea
        currentChat={CHAT}
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
}

const letter = (over: Partial<Message>, id = 'm1'): Message =>
  ({
    id,
    senderId: 'peer',
    senderName: 'Оксана',
    senderAvatar: '',
    timestamp: '14:32',
    type: 'text',
    isSelf: false,
    ...over,
  }) as unknown as Message;

describe('бульбашка листа не розтягує рядок під нерозбитий токен', () => {
  it('.msg-bubble-fluid несе min-w-0 — без цього флекс-рядок росте під довге слово', () => {
    const longToken = 'a'.repeat(400); // без пробілів і слешів: жодної природної точки переносу
    const { container } = conversation([letter({ text: longToken })]);

    const bubble = container.querySelector('.msg-bubble-fluid');
    expect(bubble).not.toBeNull();
    expect(bubble!.className).toMatch(/\bmin-w-0\b/);
  });

  it('те саме для листа з довгим посиланням без пробілів', () => {
    const url = `https://example.org/${'x'.repeat(300)}`;
    const { container } = conversation([letter({ text: url })]);

    const bubble = container.querySelector('.msg-bubble-fluid');
    expect(bubble).not.toBeNull();
    expect(bubble!.className).toMatch(/\bmin-w-0\b/);

    // Посилання й далі несе свій власний break-all — фіксом його не знімали.
    const link = container.querySelector('a[href]');
    expect(link?.className).toMatch(/break-all/);
  });

  it('безпосередній флекс-батько бульбашки (аватар + бульбашка) не тягне ширину від контенту', () => {
    const { container } = conversation([letter({ text: 'a'.repeat(400) })]);
    const bubble = container.querySelector('.msg-bubble-fluid')!;
    const row = bubble.parentElement!;
    expect(row.className).toMatch(/\bflex\b/);
    // Це вже стояло до фіксу: рядок не розтягувався сам — розтягувалась
    // бульбашка всередині нього.
    expect(row.className).toMatch(/\bmin-w-0\b/);
  });
});
