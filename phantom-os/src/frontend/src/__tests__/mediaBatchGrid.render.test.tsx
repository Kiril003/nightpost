/**
 * Пакет вкладень у стрічці — ОДНА сітка, не N бульбашок підряд.
 *
 * Власник: «файли і фото не по одному, а групуванно можна було надсилати».
 * Composer тепер групує ВІДПРАВКУ (attachBatchPreview.test.tsx); тут — друга
 * половина скарги: як пачка виглядає у вже надісланій стрічці. Кожен елемент
 * пачки й далі окремий Message на дроті (client_id на ретрай не займаний),
 * але ChatArea малює рядок лише для ПЕРШОГО елемента — решта повертає null
 * зі свого .map() і ловиться MediaBatchGrid.
 */
import { beforeAll, describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ChatArea } from '../components/messenger/ChatArea';
import type { Chat, Message, SecureMedia } from '../types/messenger';

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

const media = (name: string): SecureMedia => ({
  name,
  size: 1024,
  mime: 'image/png',
  sha256: 'a'.repeat(64),
  blobId: `blob_${name}`,
  keyHex: 'ab'.repeat(32),
  nonceHex: 'cd'.repeat(12),
});

const batchItem = (idx: number, total: number, id = 'batch_1'): Message =>
  ({
    id: `bm_${idx}`,
    senderId: 'me',
    senderName: 'Я',
    senderAvatar: '',
    timestamp: '14:32',
    type: 'image',
    isSelf: true,
    status: 'sent',
    media: media(`photo_${idx}.png`),
    mediaBatch: { id, index: idx, total },
    ...(idx === 0 ? { text: 'дивись що знайшла' } : {}),
  }) as unknown as Message;

describe('пачка вкладень у стрічці — одна сітка', () => {
  it('три елементи пачки дають ОДНУ .msg-bubble-fluid і одну media-batch-grid', () => {
    const messages = [batchItem(0, 3), batchItem(1, 3), batchItem(2, 3)];
    const { container, getAllByTestId } = conversation(messages);

    // Рядок листа — один на пачку, не три.
    expect(container.querySelectorAll('.msg-bubble-fluid').length).toBe(1);
    expect(getAllByTestId('media-batch-grid')).toHaveLength(1);
  });

  it('одиночне вкладення (без пачки) і далі малює звичайну бульбашку', () => {
    const solo = { ...batchItem(0, 1), mediaBatch: undefined } as Message;
    const { container, queryByTestId } = conversation([solo]);

    expect(container.querySelectorAll('.msg-bubble-fluid').length).toBe(1);
    expect(queryByTestId('media-batch-grid')).not.toBeInTheDocument();
  });

  it('сітка не малює зайвих рядків між елементами пачки й наступним звичайним листом', () => {
    const after: Message = {
      id: 'after_1', senderId: 'peer', senderName: 'Оксана', senderAvatar: '',
      timestamp: '14:40', type: 'text', isSelf: false, text: 'клас!',
    } as unknown as Message;
    const messages = [batchItem(0, 2), batchItem(1, 2), after];
    const { container } = conversation(messages);

    // Пачка (1 рядок) + звичайний текстовий лист (1 рядок) = 2.
    expect(container.querySelectorAll('.msg-bubble-fluid').length).toBe(2);
  });
});
