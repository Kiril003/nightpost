/**
 * Групи їдуть і на телефоні — і зібрати їх можна лише з людей.
 *
 * Досі веб ховав групу саме тоді, коли бачив міст: `!onPhone` знімало вкладку
 * й форму, а під смужкою стояло «Групи на телефоні ще не їдуть». Міст тим
 * часом групи вже віз. Друга, тихіша вада була на ПК: веб слав
 * `createConversation` БЕЗ учасників — ні `member_ids`, ні `contact_ids`, — а
 * міст на порожній склад відповідає 422 `no_members`. Тобто створення груп не
 * працювало ніде.
 *
 * Червоніти вміє: поверніть `!onPhone` на вкладку — падає перший тест;
 * приберіть `member_ids` із виклику — падає тест про склад; поверніть трьох
 * вигаданих учасників у createGroup — падає тест про те, що склад питають у
 * мосту.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { CreateChatModal } from '../components/messenger/CreateChatModal';
import { messengerApi } from '../services/messengerApi';
import { messageFromNode } from '../services/messengerApi';
import type { NodeContact, NodeMessage } from '../services/messengerApi';
import { useMessengerStore } from '../stores/messengerStore';

const setPhone = (on: boolean) => {
  if (on) {
    (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = {
      call: () => JSON.stringify({ ok: true, status: 200, data: [] }),
    };
  } else {
    delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
  }
};

const contact = (id: string, name: string, ready = true): NodeContact => ({
  id,
  peer_node_id: id,
  display_name: name,
  peer_address: null,
  safety_number: '',
  safety_number_pretty: '',
  verified: true,
  session_ready: ready,
  created_at: '2026-09-05T10:00:00',
});

const open = () =>
  render(<CreateChatModal isOpen onClose={() => {}} onConversationReady={() => {}} />);

beforeEach(() => {
  // Склад завжди питають у мосту — хай у тестах він відмовляє мовчки.
  vi.spyOn(messengerApi, 'groupMembers').mockRejectedValue(new Error('не питали'));
});

afterEach(() => {
  setPhone(false);
  vi.restoreAllMocks();
});

describe('вкладка «Група» на телефоні', () => {
  it('вкладка є, і жодного речення про «групи ще не їдуть»', async () => {
    setPhone(true);
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
    open();
    await waitFor(() => expect(messengerApi.listContacts).toHaveBeenCalled());
    expect(screen.getByText('Нова група')).toBeTruthy();
    expect(screen.queryByText(/Групи на телефоні ще не їдуть/)).toBeNull();
  });

  it('на ПК вкладка теж лишається — нічого не помінялось у другий бік', async () => {
    setPhone(false);
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
    open();
    await waitFor(() => expect(messengerApi.listContacts).toHaveBeenCalled());
    expect(screen.getByText('Нова група')).toBeTruthy();
  });

  it('форма досяжна: за вкладкою стоїть назва й перебір людей', async () => {
    setPhone(true);
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact('p1', 'Тарас')]);
    open();
    fireEvent.click(screen.getByText('Нова група'));
    // 24.09 (форма): підпис групи полів — «Люди», стан ключа словом поруч.
    expect(screen.getByText(/^Люди/)).toBeTruthy();
    expect(await screen.findByText('Тарас')).toBeTruthy();
  });
});

describe('група починається з людей', () => {
  beforeEach(() => {
    useMessengerStore.setState({ chats: [], activeChatId: '' });
  });

  /**
   * Групи наодинці не буває. Міст складає групу з першого листа
   * (`WebMessengerBridge.createGroup` → `sendToGroup`), і групі з одного себе
   * слати нікому: 503 `unsent`. Тут стояло речення «створіть зараз, учасників
   * додасте пізніше» — обіцянка, якої міст не тримає. Кнопка чекає на людей.
   */
  it('без людей кнопка не активна — групи з одного себе міст не складе', async () => {
    setPhone(true);
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact('p1', 'Тарас')]);
    const create = vi.spyOn(messengerApi, 'createConversation');
    open();
    fireEvent.click(screen.getByText('Нова група'));
    await screen.findByText('Тарас');

    const button = screen.getByRole('button', { name: /Створити/i });
    expect((button as HTMLButtonElement).disabled).toBe(true);

    fireEvent.change(screen.getByPlaceholderText(/Сусіди/), { target: { value: 'Двір' } });
    expect((button as HTMLButtonElement).disabled).toBe(true);

    fireEvent.click(screen.getByText('Тарас'));
    expect((button as HTMLButtonElement).disabled).toBe(false);
    expect(create).not.toHaveBeenCalled();
  });

  it('у формі лише назва й люди — без кола, опису, аватара й обіцянок', async () => {
    setPhone(true);
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact('p1', 'Тарас')]);
    open();
    fireEvent.click(screen.getByText('Нова група'));
    await screen.findByText('Тарас');
    for (const gone of [/Коло довіри/, /Work OS/, /Опис простору/, /Аватар простору/, /Суверенний/, /Canvas/, /ШІ Простір/]) {
      expect(screen.queryByText(gone)).toBeNull();
    }
  });

  it('обраних людей веб справді шле — інакше міст відмовив би 422', async () => {
    setPhone(true);
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([
      contact('p1', 'Тарас'),
      contact('p2', 'Марта'),
    ]);
    const create = vi.spyOn(messengerApi, 'createConversation').mockResolvedValue({
      id: 'grp_1', title: 'Двір', kind: 'group', circle: 'all',
      handle: null, avatar: null, pinned: false, archived: false,
      created_at: '2026-09-05T10:00:00', updated_at: '2026-09-05T10:00:00',
    });
    open();
    fireEvent.click(screen.getByText('Нова група'));
    fireEvent.click(await screen.findByText('Тарас'));
    fireEvent.change(screen.getByPlaceholderText(/Сусіди/), { target: { value: 'Двір' } });
    fireEvent.click(screen.getByRole('button', { name: /Створити/i }));

    await waitFor(() => expect(create).toHaveBeenCalled());
    expect(create.mock.calls[0][0].member_ids).toEqual(['p1']);
    expect(create.mock.calls[0][0].kind).toBe('group');
  });

  it('людина без ключа не вибирається: міст усе одно викинув би її зі складу', async () => {
    setPhone(true);
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact('p3', 'Без ключа', false)]);
    open();
    fireEvent.click(screen.getByText('Нова група'));
    const row = (await screen.findByText('Без ключа')).closest('button')!;
    expect((row as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText('ще не на звʼязку')).toBeTruthy();
  });
});

describe('createGroup: складу не вигадуємо', () => {
  beforeEach(() => {
    useMessengerStore.setState({ chats: [], activeChatId: '' });
  });

  it('склад приходить із мосту, а не з нашої уяви', async () => {
    vi.spyOn(messengerApi, 'createConversation').mockResolvedValue({
      id: 'conv_from_node', title: 'Двір', kind: 'group', circle: 'all',
      handle: null, avatar: null, pinned: false, archived: false,
      created_at: '2026-09-04T10:00:00', updated_at: '2026-09-04T10:00:00',
    });
    (messengerApi.groupMembers as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      id: 'conv_from_node',
      title: 'Двір',
      members: [
        {
          id: 'me', name: 'Я', in_roster: true, verified: true, session_ready: true, is_me: true,
          role: 'owner', may_remove: false, may_grant: false, may_own: false,
        },
        {
          id: 'p1', name: 'Тарас', in_roster: true, verified: false, session_ready: true, is_me: false,
          role: 'mod', may_remove: true, may_grant: true, may_own: true,
        },
        // Імʼя з чужого заголовка: у власному списку такого немає.
        {
          id: 'p9', name: 'Невідомий', in_roster: false, verified: false, session_ready: false, is_me: false,
          role: 'member', may_remove: true, may_grant: true, may_own: true,
        },
      ],
      addable: [],
      max_members: 32,
      authoritative: false,
      standing: 'in',
      my_role: 'owner',
      owner_id: 'me',
      powers: {
        add: true, remove: true, rename: true, card: true,
        grant: true, transfer: true, pin: true,
      },
    });

    const id = await useMessengerStore.getState().createGroup('Двір', 'work', '', '', ['p1']);
    const chat = useMessengerStore.getState().chats.find((c) => c.id === id)!;

    expect(chat.members?.length).toBe(3);
    expect(chat.membersCount).toBe(3);
    // Склад засвідчений, не авторитетний — і це доїхало до стору.
    expect(chat.membersAuthoritative).toBe(false);
    expect(chat.members?.find((m) => m.id === 'p9')?.inRoster).toBe(false);
    // Щабель — рівно той, що назвав міст. До 16.09 тут стояло «жодного
    // вигаданого власника чи адміна» й хардкод `'member'` у сторі: на дроті
    // ролей не існувало. Тепер вони існують (PeerGroupRoles), і перевірка
    // змінилась із «ролей немає» на «роль НЕ ВИГАДАНА»: беремо ту, що приїхала.
    expect(chat.members?.map((m) => m.role)).toEqual(['owner', 'mod', 'member']);
    expect(chat.myRole).toBe('owner');
    expect(chat.ownerId).toBe('me');
    // Право поімённе: над собою кнопок немає, бо міст сказав false.
    expect(chat.members?.find((m) => m.isMe)?.mayRemove).toBe(false);
    expect(chat.members?.find((m) => m.id === 'p1')?.mayGrant).toBe(true);
    expect(chat.powers?.grant).toBe(true);
    // «В мережі» ніхто не міряв — навіть про себе.
    expect(chat.members?.every((m) => m.isOnline === undefined)).toBe(true);

    const names = (chat.members ?? []).map((m) => m.name).join(' ');
    for (const ghost of ['Олександр', 'DevOps Node', 'Nightpost Copilot', 'olexandr_lead']) {
      expect(names).not.toContain(ghost);
    }
    expect(chat.messages.length).toBe(0);
    const feed = JSON.stringify(chat.messages);
    expect(feed).not.toContain('Canvas');
    expect(feed).not.toContain('Work OS');
  });

  it('міст не сказав складу — лишається «не питали», а не «нікого»', async () => {
    vi.spyOn(messengerApi, 'createConversation').mockResolvedValue({
      id: 'conv2', title: 'Двір', kind: 'group', circle: 'all',
      handle: null, avatar: null, pinned: false, archived: false,
      created_at: '2026-09-04T10:00:00', updated_at: '2026-09-04T10:00:00',
    });
    const id = await useMessengerStore.getState().createGroup('Двір', 'work', '', '', ['p1']);
    const chat = useMessengerStore.getState().chats.find((c) => c.id === id)!;
    expect(chat.members).toBeUndefined();
    expect(chat.membersCount).toBeUndefined();
  });

  /**
   * Відмова вузла — це відмова.
   *
   * Тут стояв `console.warn('node offline / demo mode')`: група лишалась у
   * памʼяті вкладки з ідентифікатором `chat_grp_<мс>`, якого вузол не знає,
   * і кожен лист у неї повертався помилкою.
   */
  it('вузол відмовив — нічого не створено, речення каже причину', async () => {
    vi.spyOn(messengerApi, 'createConversation').mockRejectedValue(
      Object.assign(new Error('Місця на вузлі немає'), { code: 'no_space' }),
    );
    await expect(
      useMessengerStore.getState().createGroup('Двір', 'work', '', '', ['p1']),
    ).rejects.toThrow('Місця на вузлі немає');
    expect(useMessengerStore.getState().chats).toEqual([]);
    expect(useMessengerStore.getState().activeChatId).toBe('');
  });

  it('обрив без коду — наше речення, і теж без групи', async () => {
    vi.spyOn(messengerApi, 'createConversation').mockRejectedValue(new TypeError('Failed to fetch'));
    await expect(
      useMessengerStore.getState().createGroup('Двір', 'work', '', '', ['p1']),
    ).rejects.toThrow(/не малюємо її замість нього/);
    expect(useMessengerStore.getState().chats).toEqual([]);
  });
});

describe('«кому дійшло» — лише про власні рядки', () => {
  const row = (over: Partial<NodeMessage>): NodeMessage => ({
    id: 'm1', conversation_id: 'g1', client_id: 'c1', seq: 1,
    author_id: 'me', author_name: 'Я', kind: 'text', body: 'привіт',
    ciphertext: null, transport: null, sent_at: '2026-09-05T10:00:00',
    edited_at: null, deleted_at: null,
    ...over,
  });

  it('під власним груповим рядком стоять числа віяра', () => {
    const m = messageFromNode(
      row({ delivery_state: 'queued', fan_delivered: 1, fan_total: 3 }),
      'me',
    );
    expect(m.fanDelivered).toBe(1);
    expect(m.fanTotal).toBe(3);
    // Значок бере НАЙМЕНШ просунутий стан — його склав міст.
    expect(m.status).toBe('queued');
  });

  it('під чужим рядком — мовчання, а не число з одного свідчення', () => {
    const m = messageFromNode(
      row({ author_id: 'p1', delivery_state: 'sent', fan_delivered: 1, fan_total: 3 }),
      'me',
    );
    expect(m.fanDelivered).toBeUndefined();
    expect(m.fanTotal).toBeUndefined();
    expect(m.status).toBeUndefined();
  });

  it('у розмові вдвох чисел немає взагалі — доля там одна', () => {
    const m = messageFromNode(
      row({ conversation_id: 'dm1', delivery_state: 'sent' }),
      'me',
      'peer1',
    );
    expect(m.fanTotal).toBeUndefined();
    expect(m.status).toBe('sent');
  });
});

describe('перебір людей для групи', () => {
  it('рядок — аватарка й імʼя, без відбитків і стану ключа', async () => {
    setPhone(true);
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact('a1b2c3d4ee', 'Марта'), contact('f9e8d7c6aa', 'Марта')]);
    open();
    fireEvent.click(await screen.findByText('Нова група'));
    await screen.findAllByText('Марта');
    const body = document.body.textContent ?? '';
    expect(body).not.toMatch(/a1b2|f9e8|звірено|ключ/);
  });
});
