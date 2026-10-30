/**
 * Зникаючі повідомлення у вебі: службовий рядок у стрічці, строк розмови і
 * вибір у «Ще» — з чесною приміткою про знімок екрана.
 */
import { beforeAll, describe, expect, it, vi, afterEach } from 'vitest';
import { render, screen, cleanup, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ChatArea } from '../components/messenger/ChatArea';
import { PhoneChatMenu, phoneChatMenuDoors } from '../components/messenger/PhoneChatMenu';
import { chatFromNode, messageFromNode, messengerApi, type NodeConversation, type NodeMessage } from '../services/messengerApi';
import { useMessengerStore } from '../stores/messengerStore';
import { timerLine } from '../services/disappearWords';
import { tNow } from '../components/chat/tNow';
import type { Chat } from '../types/messenger';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const noop = () => {};
const dm = { id: 'peer_ok', title: 'Оксана', type: 'dm', peerNodeId: 'a1b2c3d4e5f60718', messages: [], unreadCount: 0 } as unknown as Chat;
const row = (over: Partial<NodeMessage>): NodeMessage =>
  ({ id: 't1', conversation_id: 'peer_ok', client_id: 'c', seq: 1, author_id: 'a1b2c3d4e5f60718', author_name: 'Оксана',
    kind: 'timer', body: '', ciphertext: null, transport: null, sent_at: '2026-10-02T10:00:00Z', edited_at: null, deleted_at: null,
    ...over }) as unknown as NodeMessage;

describe('рядок строку', () => {
  it('міст (поле timer_s) і ПК (тіло) — той самий службовий рядок, не бульбашка', () => {
    const fromBridge = messageFromNode(row({ timer_s: 86_400 }), 'me', 'a1b2c3d4e5f60718');
    expect(fromBridge.type).toBe('system');
    expect(fromBridge.timerSeconds).toBe(86_400);
    const fromPc = messageFromNode(row({ body: '604800' }), 'me', 'a1b2c3d4e5f60718');
    expect(fromPc.timerSeconds).toBe(604_800);
    expect(timerLine(86_400, false, 'Оксана')).toBe('Оксана: повідомлення зникають через 1 день');
    expect(timerLine(0, true, 'Я')).toBe('Ви: зникнення повідомлень вимкнено');
  });

  it('у стрічці — рядок посередині без бульбашки', () => {
    const m = messageFromNode(row({ timer_s: 86_400 }), 'me', 'a1b2c3d4e5f60718');
    render(
      <MemoryRouter>
        <ChatArea currentChat={dm} messages={[m]} currentUserId="me" onOpenLocation={noop} onVotePoll={noop}
          onPayBillShare={noop} onAddReaction={noop} onReplyMessage={noop} selectedMessageIds={[]}
          onToggleSelectMessage={noop} isSelectionMode={false} isSearching={false} onCloseSearch={noop} />
      </MemoryRouter>,
    );
    const line = screen.getByTestId('timer-line');
    expect(line.textContent).toBe('Оксана: повідомлення зникають через 1 день');
    expect(line.outerHTML).not.toMatch(/\b(text|bg|rounded)-f-/);
  });

  it('строк розмови приходить із розмовою', () => {
    const chat = chatFromNode({ id: 'x', title: 'Оксана', kind: 'dm', disappear_s: 604_800 } as unknown as NodeConversation);
    expect(chat.disappearSeconds).toBe(604_800);
  });

  it('строк, поставлений співрозмовником, доїжджає з оновленням списку розмов', async () => {
    useMessengerStore.setState({ chats: [{ ...dm, disappearSeconds: 0 }] as never });
    vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([
      { id: 'peer_ok', title: 'Оксана', kind: 'dm', peer_node_id: 'a1b2c3d4e5f60718', disappear_s: 86_400 } as unknown as NodeConversation,
    ]);
    await useMessengerStore.getState().refreshConversations();
    expect(useMessengerStore.getState().chats[0].disappearSeconds).toBe(86_400);
  });
});

describe('«Ще» › Зникаючі повідомлення', () => {
  it('лише в розмові вдвох з людиною', () => {
    expect(phoneChatMenuDoors(dm).timer).toBe(true);
    expect(phoneChatMenuDoors({ ...dm, type: 'group' } as Chat).timer).toBe(false);
    expect(phoneChatMenuDoors({ ...dm, peerNodeId: undefined } as Chat).timer).toBe(false);
  });

  it('вибір строку справді просить вузол, і примітка про знімок екрана — на місці', async () => {
    useMessengerStore.setState({ chats: [{ ...dm, disappearSeconds: 0 }] as never });
    const set = vi.spyOn(messengerApi, 'setTimer').mockResolvedValue({ disappear_s: 604_800 });
    render(<PhoneChatMenu chat={dm} onClose={noop} onOpenCard={noop} onOpenGroupDetails={noop} />);
    fireEvent.click(screen.getByText('Зникаючі повідомлення'));
    expect(screen.getByTestId('timer-screenshot-note').textContent).toBe(tNow('disappear.screenshotNote'));
    fireEvent.click(screen.getByText('1 тиждень'));
    await waitFor(() => expect(set).toHaveBeenCalledWith('peer_ok', 604_800, expect.objectContaining({ author_id: expect.any(String) })));
    await waitFor(() => expect(useMessengerStore.getState().chats[0].disappearSeconds).toBe(604_800));
  });
});
