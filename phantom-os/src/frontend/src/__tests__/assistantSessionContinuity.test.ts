import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { chatApi } from '../services/api';
import { messengerApi } from '../services/messengerApi';
import { storagePersistence } from '../services/storagePersistence';
import { useMessengerStore } from '../stores/messengerStore';
import { useUIStore } from '../stores/uiStore';
import type { Chat } from '../types/messenger';

const CHAT = 'chat_phantom_assistant';

const chat = (): Chat => ({
  id: CHAT,
  title: 'Nightpost',
  avatar: '',
  type: 'dm',
  circle: 'personal',
  unreadCount: 0,
  messages: [],
});

const reply = (content: string, session_id = 's1') => ({ message: { content }, session_id }) as never;

beforeEach(() => {
  localStorage.clear();
  useMessengerStore.setState({ chats: [chat()], activeChatId: CHAT, storageFailure: null, replyingTo: null, editingMessage: null });
  let n = 0;
  vi.spyOn(messengerApi, 'appendMessage').mockImplementation(async () => ({ id: `row_q${++n}` }) as never);
});

afterEach(() => {
  vi.restoreAllMocks();
});


// Перезавантаження: вузол знає розмову й питання, але не сесію й не відповідь помічника.
const reload = async (me: string, rows: Array<{ id: string; body: string }>) => {
  vi.spyOn(messengerApi, 'identity').mockRejectedValue(new Error('offline'));
  vi.spyOn(messengerApi, 'markRead').mockResolvedValue({ unread_count: 0 } as never);
  vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([
    { id: CHAT, title: 'Nightpost', kind: 'dm', circle: 'personal', handle: null, avatar: null, pinned: false, archived: false, created_at: '2026-10-22T08:00:00Z', updated_at: '2026-10-22T08:00:00Z' },
  ] as never);
  vi.spyOn(messengerApi, 'listMessages').mockResolvedValue(
    rows.map((r, i) => ({ id: r.id, conversation_id: CHAT, client_id: `c_${i}`, seq: i + 1, author_id: me, author_name: 'Я', kind: 'text', body: r.body, ciphertext: null, transport: null, sent_at: '2026-10-22T08:00:00Z' })) as never,
  );
  await useMessengerStore.getState().hydrateFromNode();
};

const thread = () => useMessengerStore.getState().chats.find((c) => c.id === CHAT)!;

describe('помічник на ПК памʼятає розмову', () => {
  it('другий хід несе session_id, який сервер дав на першому', async () => {
    const send = vi.spyOn(chatApi, 'sendMessage').mockResolvedValueOnce(reply('Привіт.')).mockResolvedValueOnce(reply('Так, памʼятаю.'));
    await useMessengerStore.getState().sendMessage('привіт');
    await useMessengerStore.getState().sendMessage('памʼятаєш, про що ми?');
    expect((send.mock.calls[1][0] as { session_id?: string }).session_id).toBe('s1');
  });

  it('після перезавантаження розмова й сесія на місці', async () => {
    const send = vi.spyOn(chatApi, 'sendMessage').mockResolvedValueOnce(reply('Привіт.')).mockResolvedValueOnce(reply('Далі.'));
    await useMessengerStore.getState().sendMessage('привіт');
    await vi.waitFor(() => expect(storagePersistence.getChatsSync()?.find((c) => c.id === CHAT)?.sessionId).toBe('s1'));

    await reload(useMessengerStore.getState().currentUser.id, [{ id: 'row_q1', body: 'привіт' }]);

    expect(thread().sessionId).toBe('s1');
    expect(thread().messages.map((m) => m.text)).toEqual(['привіт', 'Привіт.']);
    await useMessengerStore.getState().sendMessage('і далі?');
    expect((send.mock.calls[1][0] as { session_id?: string }).session_id).toBe('s1');
  });

  it('поразка — короткий рядок поверх, а не лист від помічника і не «не надіслано»', async () => {
    useUIStore.setState({ toasts: [] });
    vi.spyOn(chatApi, 'sendMessage').mockRejectedValueOnce(new Error('HTTP 500: Internal Server Error'));
    await useMessengerStore.getState().sendMessage('привіт');
    const msgs = thread().messages;
    expect(msgs.filter((m) => m.senderId === 'assistant_phantom')).toEqual([]);
    expect(msgs[0].status).not.toBe('failed');
    const toasts = useUIStore.getState().toasts.map((t) => t.message);
    expect(toasts).toHaveLength(1);
    expect(toasts[0]).toBeTruthy();
    expect(toasts[0]).not.toMatch(/HTTP|500/);
  });

  it('відповідь, чийого питання на вузлі вже немає, зникає разом із ним', async () => {
    vi.spyOn(chatApi, 'sendMessage').mockResolvedValueOnce(reply('Привіт.'));
    await useMessengerStore.getState().sendMessage('привіт');
    await reload(useMessengerStore.getState().currentUser.id, [{ id: 'row_other', body: 'інше' }]);
    expect(thread().messages.map((m) => m.text)).toEqual(['інше']);
  });

  it('старі рядки помічника без позначки не воскресають після перезавантаження', async () => {
    useMessengerStore.setState({
      chats: [{ ...chat(), messages: [
        { id: 'row_q1', senderId: 'me', senderName: 'Я', senderAvatar: '', timestamp: '10:00', type: 'text', text: 'привіт', isSelf: true },
        { id: 'msg_ai_old', senderId: 'assistant_phantom', senderName: 'Nightpost', senderAvatar: '', timestamp: '10:00', type: 'text', text: 'Немає відповіді' },
      ] }],
    });
    await reload(useMessengerStore.getState().currentUser.id, [{ id: 'row_q1', body: 'привіт' }]);
    expect(thread().messages.map((m) => m.text)).toEqual(['привіт']);
  });

  it('очищена розмова починає з моделлю нову сесію', async () => {
    const send = vi.spyOn(chatApi, 'sendMessage').mockResolvedValueOnce(reply('Привіт.')).mockResolvedValueOnce(reply('Ок.', 's2'));
    await useMessengerStore.getState().sendMessage('привіт');
    useMessengerStore.getState().updateChat(CHAT, { messages: [] });
    await useMessengerStore.getState().sendMessage('про що ми?');
    expect((send.mock.calls[1][0] as { session_id?: string }).session_id).toBeUndefined();
  });
});
