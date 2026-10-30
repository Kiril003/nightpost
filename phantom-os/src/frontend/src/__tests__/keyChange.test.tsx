/**
 * «Ключ безпеки змінився»: спокійний службовий рядок із «Перевірити» у стрічці,
 * а для звіреного контакту — пауза листів до «Продовжити».
 */
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ChatArea } from '../components/messenger/ChatArea';
import { MessengerRoot } from '../components/messenger/MessengerRoot';
import { chatFromNode, messengerApi, type NodeConversation } from '../services/messengerApi';
import { keyChangeLine, keyChangeRow, withKeyChange, keyHoldNote } from '../services/keyChange';
import { onPeerCheck } from '../services/peerWhere';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message } from '../types/messenger';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const PEER = 'f0e1d2c3b4a59687';
const noop = () => {};
const said = (id: string, at: string): Message =>
  ({ id, senderId: PEER, senderName: 'Марта', senderAvatar: '', timestamp: at, sentAt: at, type: 'text', text: id }) as Message;

const CONV: NodeConversation = {
  id: 'conv_marta', title: 'Марта', kind: 'dm', circle: 'friends', handle: null, avatar: null, pinned: false,
  archived: false, created_at: '2026-10-01T10:00:00', updated_at: '2026-10-02T10:00:00', peer_node_id: PEER,
  contact_verified: false, contact_key_changed: true, contact_key_changed_at: '2026-10-02T09:00:00Z',
};

describe('рядок у стрічці', () => {
  it('приходить із розмовою і стає між листами за часом', () => {
    const chat = chatFromNode(CONV);
    expect(chat.contactKeyChangedAt).toBe('2026-10-02T09:00:00Z');
    expect(chat.contactKeyHold).toBe(false);
    const row = keyChangeRow(chat)!;
    const feed = withKeyChange([said('a', '2026-10-02T08:00:00Z'), said('b', '2026-10-02T10:00:00Z')], row);
    expect(feed.map((m) => m.id)).toEqual(['a', `key-change-${chat.id}`, 'b']);
    expect(keyChangeRow({ ...chat, contactKeyChanged: false })).toBeNull();
  });

  it('сірий рядок без бульбашки, «Перевірити» відкриває звірку саме цієї людини', () => {
    const chat = chatFromNode(CONV);
    const asked = vi.fn();
    const off = onPeerCheck(asked);
    render(
      <MemoryRouter>
        <ChatArea currentChat={chat} messages={[said('a', '2026-10-02T08:00:00Z')]} currentUserId="me" onOpenLocation={noop}
          onVotePoll={noop} onPayBillShare={noop} onAddReaction={noop} onReplyMessage={noop} selectedMessageIds={[]}
          onToggleSelectMessage={noop} isSelectionMode={false} isSearching={false} onCloseSearch={noop} />
      </MemoryRouter>,
    );
    const line = screen.getByTestId('key-change-line');
    expect(line.textContent).toContain(keyChangeLine('Марта'));
    expect(line.outerHTML).not.toMatch(/\b(text|bg|rounded)-f-|text-danger/);
    fireEvent.click(screen.getByTestId('key-change-check'));
    expect(asked).toHaveBeenCalledWith(PEER);
    off();
  });
});

describe('пауза для звіреного контакту', () => {
  const open = async (hold: boolean) => {
    useMessengerStore.setState({ chats: [], activeChatId: CONV.id, hydrated: false });
    vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([{ ...CONV, contact_key_hold: hold }]);
    vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
    render(
      <MemoryRouter>
        <MessengerRoot />
      </MemoryRouter>,
    );
  };

  it('замість композера — пояснення і дві двері; «Продовжити» знімає паузу на вузлі', async () => {
    const carry = vi.spyOn(messengerApi, 'continueAfterKeyChange').mockResolvedValue({} as never);
    await open(true);
    const bar = await screen.findByTestId('key-hold-bar');
    expect(bar.textContent).toContain(keyHoldNote());
    expect(screen.queryByPlaceholderText(/Повідомлення|Напишіть/i)).toBeNull();
    fireEvent.click(screen.getByTestId('key-hold-continue'));
    await waitFor(() => expect(carry).toHaveBeenCalledWith(PEER));
    await waitFor(() => expect(screen.queryByTestId('key-hold-bar')).toBeNull());
    expect((useMessengerStore.getState().chats.find((c) => c.id === CONV.id) as Chat).contactKeyHold).toBe(false);
  });

  it('незвірений контакт паузи не має — лише рядок', async () => {
    await open(false);
    await screen.findByTestId('key-change-line');
    expect(screen.queryByTestId('key-hold-bar')).toBeNull();
  });
});
