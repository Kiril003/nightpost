/**
 * Поразка помічника (UI-audit 14.10 №2): причина — ярликом на бульбашці ПИТАННЯ людини
 * («Помічник недоступний · Повторити»), а не листом від імені помічника; «Повторити» шле те саме
 * питання з тим самим id, тож нової бульбашки питання не з'являється.
 */
import { beforeAll, describe, it, expect, vi, afterEach } from 'vitest';
import { render, fireEvent, cleanup } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ChatArea } from '../components/messenger/ChatArea';
import { useMessengerStore } from '../stores/messengerStore';
import { messageFromNode, messengerApi, type NodeMessage } from '../services/messengerApi';
import type { Chat, Message } from '../types/messenger';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

const realRetry = useMessengerStore.getState().retrySend;

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  useMessengerStore.setState({ retrySend: realRetry } as never);
});

const noop = () => {};
const PHANTOM = 'peer_phantom_agent';

const assistantChat = (messages: Message[] = []): Chat =>
  ({
    id: PHANTOM,
    title: 'Nightpost',
    avatar: '',
    type: 'phantom',
    circle: 'all',
    pinned: true,
    unreadCount: 0,
    messages,
    peerNodeId: PHANTOM,
  }) as unknown as Chat;

const nodeRow = (over: Record<string, unknown> = {}): NodeMessage =>
  ({
    id: 'msg_q1', conversation_id: PHANTOM, client_id: 'msg_q1', seq: 1, author_id: 'me', author_name: 'Я',
    kind: 'text', body: 'Why is the sky blue?', ciphertext: null, transport: 'p2p',
    sent_at: '2026-10-15T01:00:00Z', edited_at: null, deleted_at: null, delivery_state: 'failed',
    ...over,
  }) as unknown as NodeMessage;

const asked = (over: Record<string, unknown> = {}) => messageFromNode(nodeRow(over), 'me', PHANTOM);

const feed = (messages: Message[]) =>
  render(
    <MemoryRouter>
      <ChatArea
        currentChat={assistantChat(messages)}
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

describe('причина на бульбашці питання', () => {
  it('міст кладе причину в рядок, веб читає її як failReason', () => {
    const m = asked({ fail_reason: 'Помічник недоступний' });
    expect(m.status).toBe('failed');
    expect(m.failReason).toBe('Помічник недоступний');
    expect(asked().failReason).toBeUndefined();
    expect(asked({ fail_reason: '' }).failReason).toBeUndefined();
  });

  it('ChatArea: «Помічник недоступний · Повторити» на питанні, і жодного листа від помічника', () => {
    const retrySend = vi.fn().mockResolvedValue(undefined);
    useMessengerStore.setState({ retrySend } as never);
    const { getAllByTestId, container } = feed([asked({ fail_reason: 'Помічник недоступний' })]);
    const marks = getAllByTestId('msg-failed');
    expect(marks).toHaveLength(1);
    expect(marks[0].textContent).toBe('Помічник недоступний · Повторити');
    expect(marks[0].closest('#message-msg_q1')).not.toBeNull();
    expect(container.querySelectorAll('[id^="message-"]')).toHaveLength(1);
    fireEvent.click(marks[0]);
    expect(retrySend).toHaveBeenCalledWith('msg_q1');
  });

  it('без причини — як було: «Не надіслано · Повторити»', () => {
    const { getByTestId } = feed([asked()]);
    expect(getByTestId('msg-failed').textContent).toBe('Не надіслано · Повторити');
  });
});

describe('«Повторити» шле те саме питання', () => {
  it('append з тим самим client_id, число питань у розмові не росте', async () => {
    const q = asked({ fail_reason: 'Без відповіді' });
    const append = vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue(nodeRow({ delivery_state: 'delivered' }));
    useMessengerStore.setState({
      chats: [assistantChat([q])],
      currentUser: { id: 'me', name: 'Я' },
    } as never);

    await useMessengerStore.getState().retrySend('msg_q1');

    expect(append).toHaveBeenCalledTimes(1);
    expect(append.mock.calls[0][0]).toBe(PHANTOM);
    expect(append.mock.calls[0][1]).toMatchObject({ client_id: 'msg_q1', body: 'Why is the sky blue?', kind: 'text' });
    const chat = useMessengerStore.getState().chats.find((c) => c.id === PHANTOM)!;
    expect(chat.messages.map((m) => m.id)).toEqual(['msg_q1']);
    expect(chat.messages[0].status).not.toBe('failed');
    expect(chat.messages[0].failReason).toBeUndefined();
  });
});
