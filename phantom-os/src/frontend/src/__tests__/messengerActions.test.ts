import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { useMessengerStore } from '../stores/messengerStore';
import { useUIStore } from '../stores/uiStore';
import { messengerApi } from '../services/messengerApi';
import { callEngine } from '../services/callEngine';

describe('Messenger Store & Real Messaging / Calling Actions', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  beforeEach(() => {
    // Reset store state
    useMessengerStore.setState({
      chats: [
        {
          id: 'chat_test_1',
          title: 'Олександр',
          avatar: '',
          type: 'dm',
          circle: 'work',
          unreadCount: 0,
          messages: [
            {
              id: 'm1',
              senderId: 'user_alex',
              senderName: 'Олександр',
              senderAvatar: '',
              timestamp: '12:00',
              type: 'text',
              text: 'Привіт! Як стан кластера?',
              isSelf: false,
            },
          ],
        },
      ],
      activeChatId: 'chat_test_1',
      selectedMessageIds: [],
      multiSelectMode: false,
      replyingTo: null,
      editingMessage: null,
    });
  });

  it('sends text message with local transport and optimistic update', () => {
    const store = useMessengerStore.getState();
    store.sendMessage('Все працює стабільно!');

    const updated = useMessengerStore.getState().getActiveChat();
    expect(updated?.messages.length).toBe(2);
    expect(updated?.messages[1].text).toBe('Все працює стабільно!');
    expect(updated?.messages[1].isSelf).toBe(true);
  });

  it('edits existing message text cleanly', async () => {
    vi.spyOn(messengerApi, 'editMessage').mockResolvedValueOnce({
      id: 'm1', body: 'Оновлений текст повідомлення', edited_at: '2026-09-12T00:00:00Z',
    } as never);
    const store = useMessengerStore.getState();
    await store.editMessage('m1', 'Оновлений текст повідомлення');

    const updated = useMessengerStore.getState().getActiveChat();
    const editedMsg = updated?.messages.find((m) => m.id === 'm1');
    expect(editedMsg?.text).toBe('Оновлений текст повідомлення');
    expect(editedMsg?.isEdited).toBe(true);
  });

  it('вузол не прийняв правку — текст повертається, і людина бачить чому', async () => {
    vi.spyOn(messengerApi, 'editMessage').mockRejectedValueOnce(new Error('unknown route'));
    const toast = vi.spyOn(useUIStore.getState(), 'toast').mockImplementation(() => '');
    const store = useMessengerStore.getState();
    await store.editMessage('m1', 'Редакція, якої вузол не взяв');

    const updated = useMessengerStore.getState().getActiveChat();
    const msg = updated?.messages.find((m) => m.id === 'm1');
    expect(msg?.text).toBe('Привіт! Як стан кластера?');
    expect(msg?.isEdited).toBeFalsy();
    expect(toast).toHaveBeenCalledWith(
      expect.objectContaining({ kind: 'error', message: expect.stringContaining('unknown route') }),
    );
  });

  it('deletes message for self (removes item)', async () => {
    const store = useMessengerStore.getState();
    await store.deleteMessage('m1', false);

    const updated = useMessengerStore.getState().getActiveChat();
    expect(updated?.messages.find((m) => m.id === 'm1')).toBeUndefined();
  });

  it('deletes message for everyone (tombstone marker)', async () => {
    const store = useMessengerStore.getState();
    await store.deleteMessage('m1', true);

    const updated = useMessengerStore.getState().getActiveChat();
    const tombstone = updated?.messages.find((m) => m.id === 'm1');
    expect(tombstone).toBeDefined();
    expect(tombstone?.isDeleted).toBe(true);
  });

  it('toggles pin on message', () => {
    const store = useMessengerStore.getState();
    store.togglePinMessage('m1');

    let updated = useMessengerStore.getState().getActiveChat();
    expect(updated?.messages.find((m) => m.id === 'm1')?.isPinned).toBe(true);

    store.togglePinMessage('m1');
    updated = useMessengerStore.getState().getActiveChat();
    expect(updated?.messages.find((m) => m.id === 'm1')?.isPinned).toBe(false);
  });

  it('adds and toggles reaction emojis on message', () => {
    const store = useMessengerStore.getState();
    store.addReaction('m1', '🔥');

    let updated = useMessengerStore.getState().getActiveChat();
    let msg = updated?.messages.find((m) => m.id === 'm1');
    expect(msg?.reactions?.length).toBe(1);
    expect(msg?.reactions?.[0].emoji).toBe('🔥');
    expect(msg?.reactions?.[0].count).toBe(1);

    // Toggle same emoji removes reaction
    store.addReaction('m1', '🔥');
    updated = useMessengerStore.getState().getActiveChat();
    msg = updated?.messages.find((m) => m.id === 'm1');
    expect(msg?.reactions?.length).toBe(0);
  });

  it('sends voice message with calculated duration and transcript', () => {
    const store = useMessengerStore.getState();
    store.sendVoiceMessage(12, 'Голосове повідомлення про статус релізу');

    const updated = useMessengerStore.getState().getActiveChat();
    const voiceMsg = updated?.messages.find((m) => m.type === 'voice');
    expect(voiceMsg).toBeDefined();
    expect(voiceMsg?.voiceData?.duration).toBe(12);
    expect(voiceMsg?.voiceData?.transcript).toBe('Голосове повідомлення про статус релізу');
  });

  it('handles call engine initial state and hangup', () => {
    const snapshot = callEngine.getSnapshot();
    expect(snapshot).toBeDefined();
    expect(snapshot.state).toBe('idle');

    callEngine.hangup();
    expect(callEngine.getSnapshot().state).toBe('idle');
  });

  it('routes incoming message from another user (kiril to kyrylo) and auto-creates chat', () => {
    // Current user in this tab is kyrylo
    useMessengerStore.setState({
      currentUser: {
        ...useMessengerStore.getState().currentUser,
        id: 'u_kyrylo',
        name: 'Kyrylo',
        handle: '@kyrylo',
      },
      chats: [],
    });

    const store = useMessengerStore.getState();
    store.applyNodeMessage({
      id: 'msg_cross_1',
      senderId: 'u_kiril',
      senderName: 'Kiril',
      senderHandle: '@kiril',
      senderAvatar: '',
      timestamp: '14:00',
      type: 'text',
      text: 'Привіт, Кирило! Як перевірка звʼязку?',
    } as any);

    const chats = useMessengerStore.getState().chats;
    expect(chats.length).toBe(1);
    expect(chats[0].title).toBe('Kiril');
    expect(chats[0].messages.length).toBe(1);
    expect(chats[0].messages[0].text).toBe('Привіт, Кирило! Як перевірка звʼязку?');
    expect(chats[0].messages[0].isSelf).toBe(false);
  });

  it('does not create two chats when the same first message from a new peer arrives twice', () => {
    // Live delivery and node sync can both call applyNodeMessage for the
    // exact same incoming message before either has created the chat —
    // each independently saw "no chat for this peer yet" and would create
    // its own, leaving two chats with the same message id duplicated.
    useMessengerStore.setState({
      currentUser: {
        ...useMessengerStore.getState().currentUser,
        id: 'u_kyrylo',
        name: 'Kyrylo',
        handle: '@kyrylo',
      },
      chats: [],
    });

    const store = useMessengerStore.getState();
    const firstMessage = {
      id: 'msg_race_1',
      senderId: 'u_kiril',
      senderName: 'Kiril',
      senderHandle: '@kiril',
      senderAvatar: '',
      timestamp: '14:00',
      type: 'text',
      text: 'перший лист від нового співрозмовника',
    } as any;

    store.applyNodeMessage(firstMessage);
    store.applyNodeMessage(firstMessage);

    const chats = useMessengerStore.getState().chats;
    expect(chats.length).toBe(1);
    expect(chats[0].messages.length).toBe(1);
  });
});

describe('опитування: один голос на особу', () => {
  const poll = (allowMultiple: boolean) => ({
    id: 'p1', senderId: 'user_alex', senderName: 'Олександр', senderAvatar: '', timestamp: '12:00', type: 'poll', isSelf: false,
    pollData: {
      question: 'Куди?', allowMultiple, isClosed: false, totalVotes: 0,
      options: [
        { id: 'a', text: 'Ліс', votes: 0, voters: [] },
        { id: 'b', text: 'Море', votes: 0, voters: [] },
        { id: 'c', text: 'Гори', votes: 0, voters: [] },
      ],
    },
  });
  const votes = () => {
    const m = useMessengerStore.getState().getActiveChat()?.messages.find((x) => x.id === 'p1');
    return m?.pollData?.options.map((o) => [o.id, o.votes, o.voters.length]);
  };

  it('зміна голосу замінює попередній, а не додає третій', () => {
    useMessengerStore.setState({
      currentUser: { id: 'me', name: 'Я', avatar: '' } as never,
      chats: [{ id: 'chat_poll', title: 'Гурт', avatar: '', type: 'group', circle: 'all', unreadCount: 0, messages: [poll(false)] }] as never,
      activeChatId: 'chat_poll',
    });
    const store = useMessengerStore.getState();
    store.votePoll('p1', 'a');
    store.votePoll('p1', 'b');
    store.votePoll('p1', 'c');
    expect(votes()).toEqual([['a', 0, 0], ['b', 0, 0], ['c', 1, 1]]);
    store.votePoll('p1', 'c');
    expect(votes()).toEqual([['a', 0, 0], ['b', 0, 0], ['c', 0, 0]]);
  });

  it('allowMultiple: кілька голосів лишаються', () => {
    useMessengerStore.setState({
      currentUser: { id: 'me', name: 'Я', avatar: '' } as never,
      chats: [{ id: 'chat_poll', title: 'Гурт', avatar: '', type: 'group', circle: 'all', unreadCount: 0, messages: [poll(true)] }] as never,
      activeChatId: 'chat_poll',
    });
    const store = useMessengerStore.getState();
    store.votePoll('p1', 'a');
    store.votePoll('p1', 'b');
    expect(votes()).toEqual([['a', 1, 1], ['b', 1, 1], ['c', 0, 0]]);
  });
});

// ─── Тиха вада вкладень ──────────────────────────────────────────────────────
//
// Раніше `sendAttachment` ловив помилку вивантаження шифротексту й ішов ДАЛІ
// з вигаданим `blob_${Date.now()}…` та порожніми keyHex/nonceHex, а лист
// реєструвався на вузлі й показувався «надісланим». Людина бачила галочку,
// йшла — файла не існувало НІДЕ. Тести нижче тримають зворотне: без байтів
// на вузлі лист не сміє вдавати надісланий, і вузол узагалі не дізнається
// про лист, чиї байти нема чим наповнити.
describe('sendAttachment — вивантаження вкладення', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  beforeEach(() => {
    useMessengerStore.setState({
      currentUser: { id: 'me', name: 'Я', avatar: '' } as never,
      chats: [{ id: 'chat_attach', title: 'Олена', avatar: '', type: 'dm', circle: 'work', unreadCount: 0, messages: [] }] as never,
      activeChatId: 'chat_attach',
    });
  });

  // jsdom-івський File у цій версії не має arrayBuffer() — encryptForUpload
  // його читає першим рядком, тож доповнюємо, як зробив би реальний браузер.
  const attachFile = () => {
    const bytes = new Uint8Array([1, 2, 3, 4]);
    const f = new File([bytes], 'photo.png', { type: 'image/png' });
    if (typeof f.arrayBuffer !== 'function') {
      Object.defineProperty(f, 'arrayBuffer', { value: async () => bytes.buffer });
    }
    return f;
  };

  it('вивантаження впало — лист позначено failed одразу, і вузол НЕ отримує запис у нікуди', async () => {
    const uploadSpy = vi.spyOn(messengerApi, 'uploadFile').mockRejectedValueOnce(new Error('звʼязок із вузлом обірвався'));
    const appendSpy = vi.spyOn(messengerApi, 'appendMessage');

    await useMessengerStore.getState().sendAttachment(attachFile());

    const msg = useMessengerStore.getState().getActiveChat()?.messages[0];
    expect(msg?.status).toBe('failed');
    expect(msg?.attachmentState).toBe('upload_failed');
    expect(msg?.attachmentError).toContain('звʼязок');
    // Порожній рядок — чесне «нічого немає». Вигаданого blob_... тут нема.
    expect(msg?.media?.blobId).toBe('');
    expect(msg?.media?.keyHex).toBe('');
    expect(msg?.media?.nonceHex).toBe('');
    expect(uploadSpy).toHaveBeenCalledTimes(1);
    expect(appendSpy).not.toHaveBeenCalled();
  });

  it('«Повторити» вивантажує файл заново і лише тоді реєструє лист на вузлі', async () => {
    vi.spyOn(messengerApi, 'uploadFile').mockRejectedValueOnce(new Error('звʼязок із вузлом обірвався'));
    await useMessengerStore.getState().sendAttachment(attachFile());
    const failedMsg = useMessengerStore.getState().getActiveChat()?.messages[0];
    expect(failedMsg?.status).toBe('failed');

    vi.spyOn(messengerApi, 'uploadFile').mockResolvedValueOnce({ blob_id: 'blob_real_1', sha256: 'deadbeef' } as never);
    const appendSpy = vi.spyOn(messengerApi, 'appendMessage').mockResolvedValueOnce({
      id: failedMsg!.id,
      delivery: 'sent',
    } as never);

    await useMessengerStore.getState().retrySend(failedMsg!.id);

    const retried = useMessengerStore.getState().getActiveChat()?.messages.find((m) => m.id === failedMsg!.id);
    expect(retried?.status).toBe('sent');
    expect(retried?.attachmentState).not.toBe('upload_failed');
    expect(retried?.media?.blobId).toBe('blob_real_1');
    expect(appendSpy).toHaveBeenCalledTimes(1);
  });

  it('файл не пережив перезавантаження вкладки — «Повторити» каже чому, а не крутить спінер', async () => {
    useMessengerStore.setState({
      chats: [
        {
          id: 'chat_attach', title: 'Олена', avatar: '', type: 'dm', circle: 'work', unreadCount: 0,
          messages: [
            {
              id: 'orphan_1', senderId: 'me', senderName: 'Я', senderAvatar: '', timestamp: '12:00',
              type: 'image', isSelf: true, status: 'failed', attachmentState: 'upload_failed',
              media: { name: 'x.png', size: 10, mime: 'image/png', sha256: '', blobId: '', keyHex: '', nonceHex: '' },
            },
          ],
        },
      ] as never,
      activeChatId: 'chat_attach',
    });
    const appendSpy = vi.spyOn(messengerApi, 'appendMessage');
    const uploadSpy = vi.spyOn(messengerApi, 'uploadFile');

    await useMessengerStore.getState().retrySend('orphan_1');

    const msg = useMessengerStore.getState().getActiveChat()?.messages.find((m) => m.id === 'orphan_1');
    expect(msg?.status).toBe('failed');
    expect(msg?.attachmentError).toContain('прикріпіть');
    expect(uploadSpy).not.toHaveBeenCalled();
    expect(appendSpy).not.toHaveBeenCalled();
  });
});
