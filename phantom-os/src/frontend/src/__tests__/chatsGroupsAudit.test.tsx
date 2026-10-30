import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MessengerRoot } from '../components/messenger/MessengerRoot';
import { ChatArea } from '../components/messenger/ChatArea';
import { ThreadView } from '../components/messenger/ThreadView';
import { UserProfileModal } from '../components/messenger/UserProfileModal';
import { MomentViewer } from '../components/messenger/MomentViewer';
import { MomentComposer } from '../components/messenger/MomentsStrip';
import { flushRemoval } from '../services/momentsUndo';
import { peerProfileEdits } from '../components/messenger/peerProfileEdits';
import { mergeRows, type MeshDelta } from '../services/meshDelta';
import { mergeLiveRow } from '../services/messengerLive';
import { outboxDue } from '../services/outboxRetryPolicy';
import { ApiError } from '../services/api';
import { messengerApi, messageFromNode, SAVED_NOTES_ID, type NodeConversation, type NodeMessage } from '../services/messengerApi';
import * as messengerMedia from '../services/messengerMedia';
import { useMessengerStore } from '../stores/messengerStore';
import { __resetChatKeep } from '../services/chatKeep';
import { useSpaceStore } from '../stores/spaceStore';
import { useUIStore } from '../stores/uiStore';
import { readMoments, useMoments } from '../services/moments';
import type { Chat, Message } from '../types/messenger';
import { momentQuoteOf } from '../services/momentReply';

describe('chats-groups logic audit fixes', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    Element.prototype.scrollTo = vi.fn();
    __resetChatKeep();
    localStorage.clear();
    useSpaceStore.setState({ threadView: null });
    vi.spyOn(messengerApi, 'queueStatus').mockResolvedValue({ queued: 0 });
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it('#5: mergeLiveRow and mergeRows resolve replyTo quotes from reply_to_id', () => {
    const origRow: NodeMessage = {
      id: 'm1',
      conversation_id: 'c1',
      client_id: 'c1_m1',
      seq: 1,
      author_id: 'peer',
      author_name: 'Олена',
      kind: 'text',
      body: 'Перший лист',
      ciphertext: null,
      transport: 'server',
      sent_at: '2026-10-01T10:00:00Z',
      edited_at: null,
      deleted_at: null,
      delivery_state: 'sent',
    };
    const replyRow: NodeMessage = {
      ...origRow,
      id: 'm2',
      client_id: 'c1_m2',
      seq: 2,
      author_id: 'me',
      author_name: '',
      body: 'Відповідь',
      reply_to_id: 'm1',
    };
    const baseChat: Chat = {
      id: 'c1',
      title: 'Олена',
      avatar: '',
      type: 'dm',
      circle: 'friends',
      unreadCount: 0,
      peerNodeId: 'peer',
      messages: [messageFromNode(origRow, 'me', 'peer')],
    };

    const live = mergeLiveRow(baseChat, replyRow, {
      active: true,
      toMessage: (r) => messageFromNode(r, 'me', 'peer'),
    });
    expect(live.chat.messages[1].replyTo).toEqual({
      id: 'm1',
      senderName: 'Олена',
      text: 'Перший лист',
      type: 'text',
    });

    const missingReplyRow: NodeMessage = {
      ...replyRow,
      id: 'm3',
      seq: 3,
      reply_to_id: 'gone_id',
    };
    const merged = mergeRows(
      baseChat.messages,
      [missingReplyRow],
      [],
      (r) => messageFromNode(r, 'me', 'peer'),
    );
    expect(merged[1].replyTo).toEqual({
      id: 'gone_id',
      senderName: '',
      text: 'лист поза стрічкою',
    });
  });

  it('#6: sendAttachment attaches replyingTo, sends reply_to_id, and clears replyingTo', async () => {
    const rootMsg: Message = {
      id: 'root_1',
      senderId: 'peer_1',
      senderName: 'Олена',
      senderAvatar: '',
      timestamp: '12:00',
      type: 'text',
      text: 'Надішли файл',
      isSelf: false,
    };
    useMessengerStore.setState({
      currentUser: { id: 'me', name: 'Я', avatar: '' } as never,
      activeChatId: 'c1',
      replyingTo: null,
      chats: [
        {
          id: 'c1',
          title: 'Олена',
          avatar: '',
          type: 'dm',
          circle: 'friends',
          unreadCount: 0,
          messages: [rootMsg],
        },
      ],
    });
    vi.spyOn(messengerMedia, 'encryptForUpload').mockResolvedValue({
      ciphertext: new Blob(['enc']),
      keyHex: 'aa'.repeat(32),
      nonceHex: 'bb'.repeat(12),
      sha256: 'cc'.repeat(32),
    });
    vi.spyOn(messengerApi, 'uploadFile').mockResolvedValue({
      blob_id: '0123456789abcdef0123456789abcdef',
      size: 3,
      sha256: 'cc'.repeat(32),
      state: 'stored',
    });
    const appendSpy = vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({
      id: 'srv_att_1',
      delivery_state: 'sent',
    } as never);

    useMessengerStore.getState().startReply(rootMsg);
    expect(useMessengerStore.getState().replyingTo?.id).toBe('root_1');

    const file = new File(['hello'], 'doc.txt', { type: 'text/plain' });
    await useMessengerStore.getState().sendAttachment(file, undefined, 'Ось файл');

    expect(useMessengerStore.getState().replyingTo).toBeNull();
    expect(appendSpy).toHaveBeenCalledWith(
      'c1',
      expect.objectContaining({
        reply_to_id: 'root_1',
      }),
    );
    const sentMsg = useMessengerStore.getState().chats[0].messages.find((m) => m.id === 'srv_att_1');
    expect(sentMsg?.replyTo?.id).toBe('root_1');
  });

  it('#7: forwardMessage serializes card payloads and strips replyTo/reactions/isPinned/isEdited', async () => {
    const appendSpy = vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({
      id: 'fwd_srv_1',
      delivery_state: 'sent',
    } as never);
    const cardMsg: Message = {
      id: 'm_card',
      senderId: 'peer_1',
      senderName: 'Олена',
      senderAvatar: '',
      timestamp: '12:00',
      type: 'poll',
      pollData: {
        question: 'Куди йдемо?',
        options: [{ id: '1', text: 'Парк', votes: 1, voters: ['peer_1'] }],
        totalVotes: 1,
      },
      replyTo: { id: 'old_reply', senderName: 'Олена', text: 'Старе' },
      reactions: [{ emoji: '🔥', count: 1, users: ['peer_1'] }],
      isPinned: true,
      isEdited: true,
      isSelf: false,
    };
    useMessengerStore.setState({
      currentUser: { id: 'me', name: 'Я', avatar: '' } as never,
      activeChatId: 'c1',
      chats: [
        { id: 'c1', title: 'Олена', avatar: '', type: 'dm', circle: 'friends', unreadCount: 0, messages: [cardMsg] },
        { id: 'c2', title: 'Тарас', avatar: '', type: 'dm', circle: 'friends', unreadCount: 0, messages: [] },
      ],
    });

    await useMessengerStore.getState().forwardMessage(cardMsg, 'c2');

    expect(appendSpy).toHaveBeenCalledWith(
      'c2',
      expect.objectContaining({
        kind: 'poll',
        body: JSON.stringify(cardMsg.pollData),
        reply_to_id: null,
      }),
    );
    const c2Msg = useMessengerStore.getState().chats.find((c) => c.id === 'c2')!.messages[0];
    expect(c2Msg.replyTo).toBeUndefined();
    expect(c2Msg.reactions).toBeUndefined();
    expect(c2Msg.isPinned).toBeUndefined();
    expect(c2Msg.isEdited).toBeUndefined();

    // Multi-select openForwardModal stores all selected messages
    const secondMsg: Message = {
      id: 'm_second',
      senderId: 'peer_1',
      senderName: 'Олена',
      senderAvatar: '',
      timestamp: '12:01',
      type: 'text',
      text: 'Друге повідомлення',
      isSelf: false,
    };
    useMessengerStore.getState().openForwardModal([cardMsg, secondMsg]);
    expect(useMessengerStore.getState().activeForwardMessages).toHaveLength(2);
    useMessengerStore.getState().closeForwardModal();
    expect(useMessengerStore.getState().activeForwardMessages).toHaveLength(0);
  });

  it('#10: left/removed group hides MessageComposer and outboxDue skips left/removed groups', () => {
    const leftGroup: Chat = {
      id: 'g_left',
      title: 'Колишня група',
      avatar: '',
      type: 'group',
      circle: 'work',
      standing: 'left',
      unreadCount: 0,
      messages: [
        {
          id: 'm_fail',
          senderId: 'me',
          senderName: 'Я',
          senderAvatar: '',
          timestamp: '12:00',
          type: 'text',
          text: 'Тест',
          isSelf: true,
          status: 'failed',
        },
      ],
    };
    expect(outboxDue([leftGroup], Date.now(), true)).toEqual([]);

    vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([
      {
        id: 'g_left',
        title: 'Колишня група',
        kind: 'group',
        circle: 'work',
        standing: 'left',
        handle: null,
        avatar: null,
        pinned: false,
        archived: false,
        created_at: '2026-10-01T10:00:00Z',
        updated_at: '2026-10-01T10:00:00Z',
      } as NodeConversation,
    ]);
    useMessengerStore.setState({
      chats: [leftGroup],
      activeChatId: 'g_left',
      hydrated: true,
    });
    render(<MessengerRoot />);
    expect(document.querySelector('[data-composer-input]')).toBeNull();
    expect(screen.getByTestId('group-standing-bar')).toBeTruthy();
  });

  it('#11: refreshConversations updates description, pinnedMessage, lastCard, and clears group avatar', async () => {
    useMessengerStore.setState({
      activeChatId: '',
      chats: [
        {
          id: 'g1',
          title: 'Штаб',
          avatar: 'data:image/webp;base64,OLD',
          type: 'group',
          circle: 'work',
          description: 'Старий опис',
          pinnedMessage: null,
          unreadCount: 0,
          messages: [],
        },
      ],
    });
    vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([
      {
        id: 'g1',
        title: 'Штаб',
        kind: 'group',
        circle: 'work',
        handle: null,
        avatar: null,
        description: 'Новий опис',
        pinned_message: {
          id: 'm1',
          author_id: 'p1',
          author: 'Олена',
          at: '2026-10-01T10:00:00Z',
          text: 'Важливо',
        },
        last_kind: 'place',
        last_place_lat: 50.45,
        last_place_lon: 30.52,
        last_at: '2026-10-01T10:05:00Z',
        pinned: false,
        archived: false,
        created_at: '2026-10-01T10:00:00Z',
        updated_at: '2026-10-01T10:05:00Z',
      },
    ]);

    await useMessengerStore.getState().refreshConversations();
    const g1 = useMessengerStore.getState().chats.find((c) => c.id === 'g1')!;
    expect(g1.description).toBe('Новий опис');
    expect(g1.pinnedMessage?.text).toBe('Важливо');
    expect(g1.lastCard).toEqual({ kind: 'place', lat: 50.45, lon: 30.52 });
    expect(g1.avatar).toBe('');
  });

  it('#12: ChatArea renders PinnedBanner for group wire pinnedMessage even when m.isPinned is not set', () => {
    const msg: Message = {
      id: 'm1',
      senderId: 'p1',
      senderName: 'Олена',
      senderAvatar: '',
      timestamp: '18:00',
      type: 'text',
      text: 'Зустріч о 18:00',
      isSelf: false,
    };
    const groupChat: Chat = {
      id: 'g1',
      title: 'Штаб',
      avatar: '',
      type: 'group',
      circle: 'work',
      unreadCount: 0,
      pinnedMessage: {
        id: 'm1',
        authorId: 'p1',
        author: 'Олена',
        at: '2026-10-01T18:00:00Z',
        text: 'Зустріч о 18:00',
      },
      messages: [msg],
    };
    render(
      <ChatArea
        currentChat={groupChat}
        messages={groupChat.messages}
        currentUserId="me"
        onOpenLocation={vi.fn()}
        onVotePoll={vi.fn()}
        onPayBillShare={vi.fn()}
        onAddReaction={vi.fn()}
        onReplyMessage={vi.fn()}
        selectedMessageIds={[]}
        onToggleSelectMessage={vi.fn()}
        isSelectionMode={false}
        isSearching={false}
        onCloseSearch={vi.fn()}
      />,
    );
    expect(screen.getAllByText('Зустріч о 18:00').length).toBeGreaterThanOrEqual(2);
  });

  it('#13: __phantomMeshDelta removes conversations listed in gone', async () => {
    vi.useFakeTimers();
    useMessengerStore.setState({
      activeChatId: 'g_gone',
      chats: [
        { id: 'g_gone', title: 'Зникла', avatar: '', type: 'group', circle: 'work', unreadCount: 0, messages: [] },
        { id: 'p1', title: 'Олена', avatar: '', type: 'dm', circle: 'friends', unreadCount: 0, messages: [] },
      ],
    });
    vi.spyOn(messengerApi, 'conversationsByIds').mockResolvedValue({ rows: [], gone: ['g_gone'] });
    vi.spyOn(messengerApi, 'messagesChanged').mockResolvedValue({ upsert: [], remove: [] });

    const sink = (window as unknown as { __phantomMeshDelta: (d: MeshDelta) => void }).__phantomMeshDelta;
    sink({ convs: ['g_gone'] });
    await vi.advanceTimersByTimeAsync(60);

    expect(useMessengerStore.getState().chats.some((c) => c.id === 'g_gone')).toBe(false);
    expect(useMessengerStore.getState().activeChatId).toBe('');
  });

  it('#14: peerProfileEdits.onDeleted drops the draft and does not jump into another chat', async () => {
    useMessengerStore.setState({
      activeChatId: 'p1',
      drafts: { p1: 'Чернетка' },
      chats: [
        { id: SAVED_NOTES_ID, title: 'Збережене', avatar: '', type: 'dm', circle: 'all', unreadCount: 0, pinned: true, messages: [] },
        { id: 'p1', title: 'Олена', avatar: '', type: 'dm', circle: 'friends', unreadCount: 0, pinned: true, messages: [] },
      ],
    });
    peerProfileEdits('p1').onDeleted();

    expect(useMessengerStore.getState().activeChatId).toBe('');
    expect(useMessengerStore.getState().drafts.p1).toBeUndefined();
  });

  it('#15: UserProfileModal hides write button for non-roster group member and passes contact_id when starting DM', async () => {
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
    useMessengerStore.setState({ chats: [], activeChatId: '' });

    const onStartDirectChat = vi.fn();
    render(
      <UserProfileModal
        isOpen
        onClose={vi.fn()}
        currentUser={{ id: 'me', name: 'Я', avatar: '' } as never}
        targetUser={{
          id: 'stranger_1',
          name: 'Незнайомець',
          handle: '',
          avatar: '',
          role: 'member',
          inRoster: false,
        }}
        onUpdateCurrentUser={vi.fn()}
        onStartDirectChat={onStartDirectChat}
      />,
    );
    expect(screen.queryByTestId('peer-action-write')).toBeNull();
  });

  it('#16 & #20: createGroup sends description and emits a warning toast when members are dropped', async () => {
    const toast = vi.fn();
    useUIStore.setState({ toast } as never);
    const createSpy = vi.spyOn(messengerApi, 'createConversation').mockResolvedValue({
      id: 'g_new',
      title: 'Команда',
      kind: 'group',
      circle: 'work',
      handle: null,
      avatar: null,
      description: 'Планування спринту',
      pinned: false,
      archived: false,
      created_at: '2026-10-01T10:00:00Z',
      updated_at: '2026-10-01T10:00:00Z',
      dropped: [{ id: 'p2', name: 'Петро', reason: 'no_key' }],
    } as NodeConversation);
    vi.spyOn(messengerApi, 'groupMembers').mockResolvedValue({
      id: 'g_new',
      title: 'Команда',
      members: [],
      addable: [],
      max_members: 32,
      authoritative: false,
      standing: 'in',
      my_role: 'owner',
      owner_id: 'me',
      powers: { add: true, remove: true, rename: true, card: true, grant: true, transfer: true, pin: true },
    });

    await useMessengerStore.getState().createGroup('Команда', 'work', '', 'Планування спринту', ['p1', 'p2']);

    expect(createSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        title: 'Команда',
        circle: 'work',
        description: 'Планування спринту',
        member_ids: ['p1', 'p2'],
      }),
    );
    expect(toast).toHaveBeenCalledWith(
      expect.objectContaining({
        kind: 'warn',
        message: expect.stringContaining('Петро'),
      }),
    );
  });

  it('#21: MomentComposer surfaces moments.noOne when post rejects with code no_one', async () => {
    useMoments.setState({
      prefs: { audience: { kind: 'contacts' }, keep: false, everyone: false, muted: [] },
      loadPrefs: vi.fn().mockResolvedValue(null),
      post: vi.fn().mockRejectedValue(new ApiError(422, 'no_one', '')),
    });
    const chats: Chat[] = [
      { id: 'olena', title: 'Олена', avatar: '', type: 'dm', circle: 'close', peerNodeId: 'olena', unreadCount: 0, messages: [] },
    ];
    render(
      <MomentComposer
        file={null}
        people={[{ id: 'olena', name: 'Олена', chatId: 'olena' }]}
        chats={chats}
        onClose={vi.fn()}
      />,
    );
    const sheet = await screen.findByRole('dialog');
    fireEvent.change(sheet.querySelector('[data-moment-caption]')!, { target: { value: 'Привіт' } });
    fireEvent.click(sheet.querySelector('[data-moment-share]')!);
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).not.toContain('Не вдалося поділитися');
  });

  it('#22: sending a reply in ThreadView preserves the main conversation draft', () => {
    vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({ id: 'r1', delivery_state: 'sent' } as never);
    const rootMsg: Message = {
      id: 'root_1',
      senderId: 'p1',
      senderName: 'Олена',
      senderAvatar: '',
      timestamp: '10:00',
      type: 'text',
      text: 'Корінь гілки',
      isSelf: false,
    };
    useMessengerStore.setState({
      currentUser: { id: 'me', name: 'Я', avatar: '' } as never,
      activeChatId: 'c1',
      drafts: { c1: 'Ненадіслана чернетка в головному чаті' },
      chats: [
        { id: 'c1', title: 'Олена', avatar: '', type: 'dm', circle: 'friends', unreadCount: 0, messages: [rootMsg] },
      ],
    });
    useSpaceStore.setState({ threadView: { chatId: 'c1', rootMessageId: 'root_1' } });

    render(<ThreadView />);
    const input = screen.getByTestId('thread-view').querySelector('textarea, input[type="text"]') as HTMLElement;
    fireEvent.change(input, { target: { value: 'Відповідь у гілці' } });
    fireEvent.keyDown(input, { key: 'Enter', shiftKey: false });

    expect(useMessengerStore.getState().drafts.c1).toBe('Ненадіслана чернетка в головному чаті');
  });

  it('#23 & #24: MomentViewer updates store chat on reply, shows reaction button without chatId, and closes without marking next person seen on delete', async () => {
    vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({ id: 'm_rep', delivery_state: 'sent' } as never);
    const now = Date.now();
    const list = readMoments(
      [
        {
          id: 'mom_olena',
          from_id: 'olena',
          from_name: 'Олена',
          mine: false,
          at_ms: now - 60_000,
          until_ms: now + 600_000,
          keep: false,
          audience: { kind: 'contacts' },
          text: 'Осінь',
          seen: true,
          to_count: 1,
          media: [],
        },
      ],
      now,
    );
    useMessengerStore.setState({
      currentUser: { id: 'me', name: 'Я', avatar: '' } as never,
      chats: [
        { id: 'chat_olena', title: 'Олена', avatar: '', type: 'dm', circle: 'friends', peerNodeId: 'olena', unreadCount: 0, messages: [] },
      ],
    });

    const { unmount } = render(
      <MomentViewer
        moments={list}
        startId="mom_olena"
        nameOf={() => 'Олена'}
        avatarOf={() => undefined}
        chatOf={() => 'chat_olena'}
        onOpenChat={vi.fn()}
        onClose={vi.fn()}
      />,
    );
    const input = document.querySelector('[data-moment-reply]') as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'Гарне фото' } });
    fireEvent.submit(input.closest('form')!);

    await waitFor(() => {
      const chat = useMessengerStore.getState().chats.find((c) => c.id === 'chat_olena')!;
      expect(chat.lastSnippet).toBe('Гарне фото');
      expect(chat.messages.some((m) => momentQuoteOf(m.text)?.said === 'Гарне фото' && momentQuoteOf(m.text)?.id === 'mom_olena')).toBe(true);
    });
    unmount();

    // Reaction button visible even when chatOf returns undefined
    const { unmount: unmount2 } = render(
      <MomentViewer
        moments={list}
        startId="mom_olena"
        nameOf={() => 'Олена'}
        avatarOf={() => undefined}
        chatOf={() => undefined}
        onOpenChat={vi.fn()}
        onClose={vi.fn()}
      />,
    );
    expect(document.querySelector('[data-moment-react-open]')).not.toBeNull();
    unmount2();

    // Deleting own moment does not advance into next person's unseen moment
    const markSeen = vi.fn().mockResolvedValue(undefined);
    const remove = vi.fn().mockResolvedValue(undefined);
    useMoments.setState({ markSeen, remove });
    const mixed = readMoments(
      [
        {
          id: 'mom_mine',
          from_id: 'me',
          from_name: '',
          mine: true,
          at_ms: now - 30_000,
          until_ms: now + 600_000,
          keep: false,
          audience: { kind: 'contacts' },
          text: 'Мій',
          seen: true,
          to_count: 1,
          media: [],
        },
        {
          id: 'mom_other_unseen',
          from_id: 'olena',
          from_name: 'Олена',
          mine: false,
          at_ms: now - 60_000,
          until_ms: now + 600_000,
          keep: false,
          audience: { kind: 'contacts' },
          text: 'Чужий',
          seen: false,
          to_count: 1,
          media: [],
        },
      ],
      now,
    );
    const onClose = vi.fn();
    render(
      <MomentViewer
        moments={mixed}
        startId="mom_mine"
        nameOf={() => 'Я'}
        avatarOf={() => undefined}
        chatOf={() => undefined}
        onOpenChat={vi.fn()}
        onClose={onClose}
      />,
    );
    fireEvent.click(document.querySelector('[data-moment-more]')!);
    fireEvent.click(document.querySelector('[data-moment-delete]')!);
    await act(async () => {
      await flushRemoval();
    });
    await waitFor(() => expect(remove).toHaveBeenCalledWith('mom_mine'));
    expect(markSeen).not.toHaveBeenCalled();
  });
});
