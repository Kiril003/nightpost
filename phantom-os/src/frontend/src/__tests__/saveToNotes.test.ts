import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useMessengerStore } from '../stores/messengerStore';
import { useUIStore } from '../stores/uiStore';
import { messengerApi, SAVED_NOTES_ID } from '../services/messengerApi';
import { tNow } from '../components/chat/tNow';
import type { Chat, Message } from '../types/messenger';

const baseMsg: Message = {
  id: 'm_1',
  senderId: 'peer_1',
  senderName: 'Олена',
  senderAvatar: '',
  timestamp: '12:00',
  type: 'text',
  text: 'Зустріч о 15:00',
  isSelf: false,
};

const makeChats = (): Chat[] => [
  {
    id: 'chat_dm_1',
    title: 'Олена',
    avatar: '',
    type: 'dm',
    circle: 'friends',
    unreadCount: 0,
    messages: [baseMsg],
  },
  {
    id: SAVED_NOTES_ID,
    title: tNow('chatList.saved'),
    avatar: '',
    type: 'dm',
    circle: 'all',
    unreadCount: 0,
    messages: [],
  },
];

describe('save to Saved notes via forwardMessage', () => {
  const toast = vi.fn();

  beforeEach(() => {
    vi.restoreAllMocks();
    toast.mockReset();
    useUIStore.setState({ toast } as never);
    useMessengerStore.setState({
      activeChatId: 'chat_dm_1',
      chats: makeChats(),
    });
    vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({ id: 'n1' } as never);
    vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
  });

  it('appends to SAVED_NOTES_ID and toasts saveNotes.done with open action', async () => {
    const appendSpy = vi.mocked(messengerApi.appendMessage);

    await useMessengerStore.getState().forwardMessage(baseMsg, SAVED_NOTES_ID);

    expect(appendSpy).toHaveBeenCalledWith(
      SAVED_NOTES_ID,
      expect.objectContaining({
        kind: 'text',
        body: 'Зустріч о 15:00',
      }),
    );

    const saved = useMessengerStore.getState().chats.find((c) => c.id === SAVED_NOTES_ID);
    expect(saved?.messages).toHaveLength(1);

    expect(toast).toHaveBeenCalledTimes(1);
    const call = toast.mock.calls[0][0] as {
      kind: string;
      message: string;
      action?: { label: string; run: () => void };
    };
    expect(call.kind).toBe('success');
    expect(call.message).toBe(tNow('saveNotes.done'));
    expect(call.action?.label).toBe(tNow('saveNotes.open'));

    call.action?.run();
    expect(useMessengerStore.getState().activeChatId).toBe(SAVED_NOTES_ID);
  });

  it('keeps ordinary forward toast without action for non-Saved chats', async () => {
    await useMessengerStore.getState().forwardMessage(baseMsg, 'chat_dm_1');

    expect(toast).toHaveBeenCalledTimes(1);
    const call = toast.mock.calls[0][0] as {
      kind: string;
      message: string;
      action?: { label: string; run: () => void };
    };
    expect(call.kind).toBe('success');
    expect(call.message).toBe(tNow('store.forwarded', { title: 'Олена' }));
    expect(call.action).toBeUndefined();
  });

  it('open action opens Saved once the node first sends its row', async () => {
    useMessengerStore.setState({
      chats: makeChats().filter((c) => c.id !== SAVED_NOTES_ID),
      refreshConversations: async () => {
        useMessengerStore.setState({ chats: makeChats() });
      },
    });
    useMessengerStore.getState().openSavedNotes();
    await vi.waitFor(() => expect(useMessengerStore.getState().activeChatId).toBe(SAVED_NOTES_ID));
  });
});
