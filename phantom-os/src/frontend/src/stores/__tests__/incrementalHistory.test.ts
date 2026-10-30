/**
 * Повторне відкриття розмови дочитує лише хвіст (`after_seq`), а не всю
 * стрічку. Червоне на попередньому сторі: там кожен виклик
 * `loadMessagesForChat` ішов без `after_seq` і будував кожен лист наново.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useMessengerStore, __resetTailMarks } from '../messengerStore';
import { messengerApi, type NodeMessage } from '../../services/messengerApi';

const row = (seq: number, over: Partial<NodeMessage> = {}): NodeMessage => ({
  id: `n${seq}`, conversation_id: 'c1', client_id: `cl${seq}`, seq, author_id: 'peer', author_name: 'Олена',
  kind: 'text', body: `лист ${seq}`, ciphertext: null, transport: 'server', sent_at: '2026-10-01T10:00:00Z',
  edited_at: null, deleted_at: null, delivery_state: 'sent', ...over,
});

describe('історія розмови: повна лише вперше', () => {
  beforeEach(() => {
    __resetTailMarks();
    vi.spyOn(messengerApi, 'markRead').mockResolvedValue(undefined as never);
    useMessengerStore.setState({
      currentUser: { id: 'me', name: 'Я', avatar: '' } as never,
      chats: [{ id: 'c1', title: 'Олена', avatar: '', type: 'dm', circle: 'friends', unreadCount: 0, peerNodeId: 'peer', messages: [] }] as never,
      activeChatId: 'c1',
    });
  });
  afterEach(() => vi.restoreAllMocks());

  const ids = () => useMessengerStore.getState().chats[0].messages.map((m) => m.id);

  it('перше відкриття — цілком; далі — after_seq від найбільшого seq, злиття без дублів', async () => {
    const list = vi.spyOn(messengerApi, 'listMessages')
      .mockResolvedValueOnce([row(1), row(2)])
      .mockResolvedValueOnce([row(2), row(3)]) // вузол віддав і межу — не дубль
      .mockResolvedValueOnce([]);
    const load = useMessengerStore.getState().loadMessagesForChat;

    await load('c1');
    expect(list).toHaveBeenLastCalledWith('c1');
    const first = useMessengerStore.getState().chats[0].messages;

    await load('c1');
    expect(list).toHaveBeenLastCalledWith('c1', 2);
    expect(ids()).toEqual(['n1', 'n2', 'n3']);
    // Старі листи — ті самі обʼєкти: рядки стрічки не перемальовуються.
    expect(useMessengerStore.getState().chats[0].messages[0]).toBe(first[0]);

    await load('c1');
    expect(list).toHaveBeenLastCalledWith('c1', 3);
    expect(ids()).toEqual(['n1', 'n2', 'n3']);
  });

  it('full: true і власний лист у дорозі — знову цілком (стан старого рядка зміниться)', async () => {
    const list = vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([row(1)]);
    const load = useMessengerStore.getState().loadMessagesForChat;
    await load('c1');
    await load('c1', { full: true });
    expect(list).toHaveBeenLastCalledWith('c1');

    list.mockResolvedValue([row(1), row(2, { author_id: 'me', delivery_state: 'queued' })]);
    await load('c1');
    expect(useMessengerStore.getState().chats[0].messages[1].status).toBe('queued');
    await load('c1');
    expect(list).toHaveBeenLastCalledWith('c1');
  });

  it('стрічку перенумеровано (рядок строку ліг раніше межі) — хвіст не пропускає вставленого, читаємо цілком', async () => {
    const timer = row(2, { id: 'ttl', client_id: 'ttl', kind: 'timer', body: '', timer_s: 86_400 } as Partial<NodeMessage>);
    const list = vi.spyOn(messengerApi, 'listMessages')
      .mockResolvedValueOnce([row(1), row(2)])
      .mockResolvedValueOnce([row(3, { id: 'n2', client_id: 'cl2' })])
      .mockResolvedValueOnce([row(1), timer, row(3, { id: 'n2', client_id: 'cl2' })])
      .mockResolvedValueOnce([]);
    const load = useMessengerStore.getState().loadMessagesForChat;
    await load('c1');
    await load('c1');
    expect(list).toHaveBeenNthCalledWith(2, 'c1', 2);
    expect(list).toHaveBeenNthCalledWith(3, 'c1');
    expect(ids()).toEqual(['n1', 'ttl', 'n2']);
    await load('c1');
    expect(list).toHaveBeenLastCalledWith('c1', 3);
  });

  it('повне перечитування не губить свій лист, якого вузол ще не бачив', async () => {
    vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([row(1)]);
    useMessengerStore.setState((s) => ({
      chats: s.chats.map((c) => ({
        ...c,
        messages: [{ id: 'c_local', senderId: 'me', senderName: 'Я', senderAvatar: '', timestamp: '', type: 'image', isSelf: true, status: 'sending', uploadProgress: 0.4 } as never],
      })),
    }));
    await useMessengerStore.getState().loadMessagesForChat('c1', { full: true });
    expect(ids()).toEqual(['n1', 'c_local']);
  });

  /*
   * Власник 20.10: «змінено» не видно в того, кому писали. Правка старого рядка
   * приходить, поки розмова закрита; при відкритті хвіст після межі її не
   * приносив, і в стрічці лишався старий текст без позначки.
   */
  it('правка відомого рядка в закритій розмові — наступне відкриття читає цілком', async () => {
    const sink = (window as unknown as { __phantomMeshDelta: (d: { convs: string[]; lines?: Record<string, string[]> }) => void }).__phantomMeshDelta;
    vi.spyOn(messengerApi, 'conversationsByIds').mockResolvedValue({ rows: [], gone: [] });
    const edited = row(2, { body: '**нове**', edited_at: '2026-10-01T10:05:00Z' });
    const list = vi.spyOn(messengerApi, 'listMessages')
      .mockResolvedValueOnce([row(1), row(2)])
      .mockResolvedValueOnce([row(1), edited])
      .mockResolvedValueOnce([row(3)]);
    const load = useMessengerStore.getState().loadMessagesForChat;
    await load('c1');
    useMessengerStore.setState({ activeChatId: 'other' });

    sink({ convs: ['c1'], lines: { c1: ['n2'] } });
    await new Promise((r) => setTimeout(r, 80));
    await load('c1');
    expect(list).toHaveBeenNthCalledWith(2, 'c1');
    const m = useMessengerStore.getState().chats[0].messages.find((x) => x.id === 'n2')!;
    expect([m.text, m.isEdited]).toEqual(['**нове**', true]);

    // Лише новий рядок — хвіст лишається хвостом.
    sink({ convs: ['c1'], lines: { c1: ['n3'] } });
    await new Promise((r) => setTimeout(r, 80));
    await load('c1');
    expect(list).toHaveBeenNthCalledWith(3, 'c1', 2);
  });

  it('стара дзвінка без переліку рядків — усі межі забуто', async () => {
    const list = vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([row(1)]);
    vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
    const load = useMessengerStore.getState().loadMessagesForChat;
    await load('c1');
    useMessengerStore.setState({ activeChatId: '' });
    (window as unknown as { __phantomMeshChanged: () => void }).__phantomMeshChanged();
    await load('c1');
    expect(list).toHaveBeenLastCalledWith('c1');
  });
});
