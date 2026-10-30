/**
 * Простори v2, зріз 1 (sv-home · sv-switch · sv-create): простір відкривається
 * зі списку розмов власною головною, створюється з «Нової розмови» за шаблоном.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import React, { useState } from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { MessengerRoot } from '../components/messenger/MessengerRoot';
import { CreateChatModal } from '../components/messenger/CreateChatModal';
import { SpaceHost } from '../components/messenger/spaces/SpaceHost';
import { messengerApi, type NodeContact } from '../services/messengerApi';
import { useMessengerStore } from '../stores/messengerStore';
import { useProfiles } from '../stores/profilesStore';
import { useChatProfiles } from '../stores/chatProfileStore';
import { useSpaceNav } from '../services/spaces/spaceNav';
import { foldSpace } from '../services/spaces/spaceLog';
import { spaceLog } from '../services/spaces/spaceVault';
import { seedEvents } from '../services/spaces/spaceTemplates';

const ME = 'me-node';
const SP = 'sp-vertical';

type Row = { actor: string; at: number; event: string };
const logs: Record<string, Row[]> = {};
const bridgeLog = (spaceId: string) => (logs[spaceId] ??= []);

const contact = (id: string, name: string): NodeContact => ({
  id,
  peer_node_id: `node-${id}`,
  display_name: name,
  peer_address: null,
  safety_number: '',
  safety_number_pretty: '',
  verified: true,
  session_ready: true,
  created_at: '2026-10-01T10:00:00',
});

const seedBridge = (spaceId: string, name: string) => {
  let n = 0;
  const bodies = seedEvents('course', name, ['node-c1'], (k: string) => ({ 'space.ch.announce': 'Оголошення', 'space.ch.questions': 'Питання', 'space.ch.general': 'Загальна' } as Record<string, string>)[k] ?? k, () => `s${(n += 1)}`);
  bodies.forEach((body, i) => bridgeLog(spaceId).push({ actor: ME, at: 1000 + i, event: JSON.stringify({ id: `${spaceId}-e${i}`, lamport: i + 1, at: 1000 + i, body }) }));
};

const NewChat: React.FC = () => {
  const [open, setOpen] = useState(true);
  return <CreateChatModal isOpen={open} onClose={() => setOpen(false)} onConversationReady={() => {}} />;
};

let prevStore: ReturnType<typeof useMessengerStore.getState>;
beforeEach(() => {
  prevStore = useMessengerStore.getState();
  useMessengerStore.setState({ currentUser: { ...prevStore.currentUser, id: ME } } as never);
  for (const k of Object.keys(logs)) delete logs[k];
  localStorage.clear();
  useSpaceNav.setState({ stack: [], tick: 0 });
  (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = {
    call: () => JSON.stringify({ ok: true, status: 200, data: [] }),
    navigate: () => true,
  };
  vi.spyOn(messengerApi, 'spaceEvents').mockImplementation(async (id) => ({ events: [...bridgeLog(id)], members: [] }));
  vi.spyOn(messengerApi, 'postSpaceEvent').mockImplementation(async (id, ev) => {
    const e = ev as { id: string; lamport: number; at: number; body: unknown };
    bridgeLog(id).push({ actor: ME, at: e.at, event: JSON.stringify(e) });
    return { sent: true };
  });
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact('c1', 'Оксана'), contact('c2', 'Тарас')]);
});
afterEach(() => {
  cleanup();
  useMessengerStore.setState(prevStore, true);
  delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
  vi.restoreAllMocks();
});

describe('простір зі списку розмов', () => {
  it('дотик по простору відкриває його головну: кімнати за видом, одна дія «Запросити»', async () => {
    seedBridge(SP, 'Vertical');
    useMessengerStore.setState({ chats: [], activeChatId: '', hydrated: false } as never);
    vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([
      { id: SP, title: 'Vertical', kind: 'group', circle: 'all', handle: null, avatar: null, pinned: false, archived: false, created_at: '2026-10-01T10:00:00', updated_at: '2026-10-02T11:00:00', settings: { kind: 'space' } },
    ] as never);
    render(
      <MemoryRouter>
        <MessengerRoot />
      </MemoryRouter>,
    );
    fireEvent.click(await screen.findByText('Vertical'));
    const home = await screen.findByTestId('space-home');
    expect(useMessengerStore.getState().activeChatId).not.toBe(SP);
    await waitFor(() => expect(screen.getByTestId('space-rooms')).toBeTruthy());
    const kinds = [...home.querySelectorAll('[data-kind]')].map((n) => n.getAttribute('data-kind'));
    expect(kinds).toEqual(['announce', 'forum', 'chat']);
    expect(home.querySelectorAll('.bg-accent')).toHaveLength(1);
    expect(screen.getByTestId('space-invite').textContent).toContain('Запросити');
    expect(screen.getByTestId('space-kind').textContent).toContain('2 учасники');
  });
});

describe('новий простір', () => {
  it('двері «Новий простір» → шаблон, назва, люди → гілка роду space і журнал шаблону', async () => {
    const createGroup = vi.spyOn(useMessengerStore.getState(), 'createGroup').mockResolvedValue('sp-new');
    useMessengerStore.setState({ createGroup } as never);
    render(
      <MemoryRouter>
        <NewChat />
        <SpaceHost />
      </MemoryRouter>,
    );
    fireEvent.click(await screen.findByTestId('door-space'));
    await screen.findByTestId('space-create');
    const go = screen.getByTestId('space-create-go') as HTMLButtonElement;
    expect(go.disabled).toBe(true);
    fireEvent.change(screen.getByTestId('space-name'), { target: { value: 'Сусіди Липки' } });
    fireEvent.click(screen.getByTestId('space-tpl-home'));
    expect(go.disabled).toBe(true);
    fireEvent.click(screen.getByTestId('space-people-row'));
    const pick = await screen.findByTestId('space-people');
    expect(screen.queryByTestId('door-space')).toBeNull();
    fireEvent.click(await within(pick).findByText('Оксана'));
    fireEvent.click(screen.getByTestId('space-people-done'));
    expect(go.disabled).toBe(false);
    await act(async () => {
      fireEvent.click(go);
    });
    expect(createGroup).toHaveBeenCalledWith('Сусіди Липки', 'all', '', '', ['c1'], 'space');
    await screen.findByTestId('space-home');
    expect(useChatProfiles.getState().map['sp-new']).toBe(useProfiles.getState().activeId);
    const space = foldSpace(spaceLog(useProfiles.getState().activeId, 'sp-new')).space!;
    expect(space.name).toBe('Сусіди Липки');
    expect(space.template).toBe('home');
    expect(space.channels.map((c) => c.type)).toEqual(['announce', 'chat', 'forum']);
    expect(space.members.map((m) => m.id)).toContain('node-c1');
  });

  it('перемикач показує всі простори (вид · лік + непрочитане), і ще не відкриті, й веде в інший без росту стека', async () => {
    seedBridge('sp-a', 'Альфа');
    seedBridge('sp-b', 'Бета');
    const sc = (id: string, title: string, membersCount?: number, unreadCount = 0) => ({
      id,
      title,
      type: 'group',
      unreadCount,
      messages: [],
      members: [{ id: ME, isMe: true }],
      membersCount,
      groupSettings: { kind: 'space' },
    });
    useMessengerStore.setState({ chats: [sc('sp-a', 'Альфа', undefined, 5), sc('sp-b', 'Бета'), sc('sp-c', 'Хор', 7, 12), { id: 'g1', title: 'Двір', type: 'group', messages: [], groupSettings: { kind: 'group' } }] } as never);
    useSpaceNav.getState().push({ s: 'home', chatId: 'sp-b' });
    render(<SpaceHost />);
    await screen.findByTestId('space-rooms');
    act(() => useSpaceNav.getState().swap({ s: 'home', chatId: 'sp-a' }));
    await waitFor(() => expect(screen.getByTestId('space-switch').textContent).toContain('Альфа'));
    act(() => useSpaceNav.getState().swap({ s: 'home', chatId: 'sp-b' }));
    await waitFor(() => expect(screen.getByTestId('space-switch').textContent).toContain('Бета'));
    fireEvent.click(screen.getByTestId('space-switch'));
    expect((await screen.findByTestId('space-pick-sub-sp-a')).textContent).toBe('Навчання · 2 учасники');
    expect(screen.getByTestId('space-pick-unread-sp-a').textContent).toBe('5');
    expect(screen.queryByTestId('space-pick-unread-sp-b')).toBeNull();
    expect(screen.getByTestId('space-pick-sub-sp-c').textContent).toBe('Простір · 7 учасників');
    expect(screen.getByTestId('space-pick-unread-sp-c').textContent).toBe('12');
    expect(screen.queryByTestId('space-pick-g1')).toBeNull();
    fireEvent.click(screen.getByTestId('space-pick-sp-a'));
    await waitFor(() => expect(screen.getByTestId('space-switch').textContent).toContain('Альфа'));
    expect(useSpaceNav.getState().stack).toEqual([{ s: 'home', chatId: 'sp-a' }]);
  });
});

describe('канал простору і рядок ролі (зріз 2)', () => {
  it('головна показує «ви <Роль>», дотик по каналу відкриває стрічку з коментарями й дозволяє читачу коментувати допис', async () => {
    const T0 = Date.UTC(2026, 9, 25, 10, 0);
    const evs = [
      { t: 'space.create', name: 'Vertical', icon: null, template: 'club' },
      { t: 'role.put', role: { id: 'r-coach', name: 'Тренер', color: 1, rank: 20, perms: ['read', 'write'], until: null } },
      { t: 'space.edit', defaultRoleId: 'r-coach' },
      { t: 'category.put', category: { id: 'ci', name: 'Інфо', order: 0, lockedTo: [] } },
      { t: 'channel.put', channel: { id: 'ch-ann', categoryId: 'ci', type: 'announce', name: 'Оголошення', order: 0, overrides: [] } },
      { t: 'member.add', memberId: ME, roleIds: ['r-coach'] },
    ];
    evs.forEach((body, i) =>
      bridgeLog(SP).push({ actor: 'owner-node', at: T0 + i, event: JSON.stringify({ id: `ev-${i}`, lamport: i + 1, at: T0 + i, body }) }),
    );
    const appendSpy = vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({
      id: 'srv-c2',
      conversation_id: SP,
      client_id: 'c_1',
      seq: 3,
      author_id: ME,
      author_name: 'Кирило',
      kind: 'text',
      body: 'Буду о 08:30',
      ciphertext: null,
      channel_id: 'ch-ann',
      transport: 'server',
      reply_to_id: 'p1',
      sent_at: new Date(T0 + 5000).toISOString(),
      edited_at: null,
      deleted_at: null,
      delivery: 'sent',
    } as never);

    useMessengerStore.setState({
      chats: [
        {
          id: SP,
          title: 'Vertical',
          type: 'group',
          unreadCount: 0,
          members: [{ id: ME, name: 'Кирило', role: 'member', isMe: true }],
          groupSettings: { kind: 'space' },
          messages: [
            {
              id: 'p1',
              channelId: 'ch-ann',
              senderId: 'owner-node',
              senderName: 'Софія',
              senderAvatar: '',
              timestamp: '14:05',
              sentAt: new Date(T0 + 1000).toISOString(),
              type: 'text',
              text: 'Виїзд у суботу о 09:00',
            },
            {
              id: 'forged',
              channelId: 'ch-ann',
              senderId: ME,
              senderName: 'Кирило',
              senderAvatar: '',
              timestamp: '14:06',
              sentAt: new Date(T0 + 2000).toISOString(),
              type: 'text',
              text: 'Самовільний допис без права announce.post',
            },
            {
              id: 'c1',
              channelId: 'ch-ann',
              senderId: ME,
              senderName: 'Кирило',
              senderAvatar: '',
              timestamp: '14:07',
              sentAt: new Date(T0 + 3000).toISOString(),
              type: 'text',
              text: 'Беру каску',
              replyTo: { id: 'p1', senderName: 'Софія', text: 'Виїзд у суботу о 09:00' },
            },
          ],
        },
      ],
    } as never);

    useSpaceNav.getState().push({ s: 'home', chatId: SP });
    render(<SpaceHost />);
    await screen.findByTestId('space-rooms');
    expect(screen.getByTestId('space-kind').textContent).toBe('Простір · 2 учасники · ви Тренер');

    fireEvent.click(screen.getByTestId('space-room-ch-ann'));
    const view = await screen.findByTestId('space-channel');
    expect(within(view).getByTestId('space-subtitle').textContent).toBe('Канал · Vertical');
    expect(within(view).getByTestId('space-post-p1').textContent).toContain('Виїзд у суботу о 09:00');
    expect(within(view).queryByTestId('space-post-forged')).toBeNull();
    expect(within(view).getByTestId('space-comment-c1').textContent).toContain('Беру каску');

    // Читач не має announce.post → внизу «Без звуку», доки не натисне «Коментувати» під дописом
    expect(within(view).getByTestId('space-channel-mute')).toBeTruthy();
    expect(within(view).queryByTestId('space-channel-input')).toBeNull();

    fireEvent.click(within(view).getByTestId('space-comment-btn-p1'));
    const input = await within(view).findByTestId('space-channel-input');
    fireEvent.change(input, { target: { value: 'Буду о 08:30' } });
    await act(async () => {
      fireEvent.click(within(view).getByTestId('space-channel-send'));
    });
    expect(appendSpy).toHaveBeenCalledWith(
      SP,
      expect.objectContaining({
        kind: 'text',
        body: 'Буду о 08:30',
        channel_id: 'ch-ann',
        reply_to_id: 'p1',
      }),
    );
    await waitFor(() => expect(within(view).getByTestId('space-comments-p1').textContent).toContain('Буду о 08:30'));
  });
});

describe('форум і тема простору (зріз 3: sv-forum · sv-thread)', () => {
  it('форум відкривається в будь-якому профілі: фільтри, нова тема, відповідь у темі й позначка «Відповідь»', async () => {
    const T0 = Date.UTC(2026, 9, 25, 12, 0);
    const evs = [
      { t: 'space.create', name: 'Vertical', icon: null, template: 'club' },
      { t: 'role.put', role: { id: 'r-member', name: 'Учасник', color: 0, rank: 10, perms: ['read', 'write', 'thread.create'], until: null } },
      { t: 'space.edit', defaultRoleId: 'r-member' },
      { t: 'category.put', category: { id: 'ct', name: 'Розмови', order: 0, lockedTo: [] } },
      { t: 'channel.put', channel: { id: 'f-gear', categoryId: 'ct', type: 'forum', name: 'Спорядження', order: 0, overrides: [] } },
      { t: 'member.add', memberId: ME, roleIds: ['r-member'] },
      { t: 'member.add', memberId: 'kai', roleIds: ['r-member'] },
      { t: 'topic.put', topic: { id: 'tp-rope', channelId: 'f-gear', title: 'Яку мотузку брати на скелі?', tags: [], routedRoleId: null } },
    ];
    evs.forEach((body, i) =>
      bridgeLog(SP).push({
        actor: i === evs.length - 1 ? ME : 'owner-node',
        at: T0 + i,
        event: JSON.stringify({ id: `fev-${i}`, lamport: i + 1, at: T0 + i, body }),
      }),
    );
    vi.spyOn(messengerApi, 'appendMessage').mockImplementation(async (_cid, payload) => ({
      id: `srv-${Date.now()}`,
      conversation_id: SP,
      client_id: payload.client_id ?? 'c_x',
      seq: 10,
      author_id: ME,
      author_name: 'Кирило',
      kind: 'text',
      body: payload.body,
      ciphertext: null,
      channel_id: payload.channel_id ?? null,
      transport: 'server',
      reply_to_id: payload.reply_to_id ?? null,
      sent_at: new Date(T0 + 9000).toISOString(),
      edited_at: null,
      deleted_at: null,
      delivery: 'sent',
    }) as never);

    useMessengerStore.setState({
      chats: [
        {
          id: SP,
          title: 'Vertical',
          type: 'group',
          unreadCount: 0,
          members: [
            { id: ME, name: 'Кирило', role: 'member', isMe: true },
            { id: 'kai', name: 'Кай', role: 'member' },
          ],
          groupSettings: { kind: 'space' },
          messages: [
            {
              id: 'm-op',
              channelId: 'f-gear',
              senderId: ME,
              senderName: 'Кирило',
              senderAvatar: '',
              timestamp: '18:02',
              sentAt: new Date(T0 + 1000).toISOString(),
              type: 'text',
              text: 'Маршрути до 25 м. Мотузка 60 м чи вистачить 50?',
              replyTo: { id: 'tp-rope', senderName: 'Кирило', text: 'Яку мотузку брати на скелі?' },
            },
            {
              id: 'm-r1',
              channelId: 'f-gear',
              senderId: 'kai',
              senderName: 'Кай',
              senderAvatar: '',
              timestamp: '18:10',
              sentAt: new Date(T0 + 2000).toISOString(),
              type: 'text',
              text: 'Бери 60 м: на двох маршрутах спуск 28 м.',
              replyTo: { id: 'tp-rope', senderName: 'Кирило', text: 'Яку мотузку брати на скелі?' },
            },
          ],
        },
      ],
    } as never);

    useSpaceNav.getState().push({ s: 'home', chatId: SP });
    render(<SpaceHost />);
    await screen.findByTestId('space-rooms');
    fireEvent.click(screen.getByTestId('space-room-f-gear'));

    const forum = await screen.findByTestId('space-forum');
    expect(within(forum).getByTestId('space-subtitle').textContent).toBe('Форум · 1 тем');
    expect(within(forum).getByTestId('space-topic-tp-rope').textContent).toContain('Яку мотузку брати на скелі?');
    expect(within(forum).getByTestId('space-topic-open-tp-rope')).toBeTruthy();

    // Фільтр «Без відповіді» приховує тему, де вже є відповідь
    fireEvent.click(within(forum).getByTestId('space-forum-filter-unanswered'));
    expect(within(forum).getByTestId('space-forum-empty')).toBeTruthy();
    fireEvent.click(within(forum).getByTestId('space-forum-filter-all'));

    // Перехід у тему (sv-thread)
    fireEvent.click(within(forum).getByTestId('space-topic-tp-rope'));
    const thread = await screen.findByTestId('space-thread');
    expect(within(thread).getByTestId('space-subtitle').textContent).toBe('Тема · 1 відповідей');
    expect(within(thread).getByTestId('space-thread-head').textContent).toContain('Маршрути до 25 м');
    expect(within(thread).getByTestId('space-thread-reply-m-r1').textContent).toContain('Бери 60 м');

    // Позначити відповідь (автор теми має право)
    await act(async () => {
      fireEvent.click(within(thread).getByTestId('space-thread-solve-m-r1'));
    });
    await waitFor(() => expect(within(thread).getByTestId('space-thread-answer-badge').textContent).toContain('Відповідь'));

    // Написати нову репліку в темі
    fireEvent.change(within(thread).getByTestId('space-thread-input'), { target: { value: 'Дякую! Беру 60' } });
    await act(async () => {
      fireEvent.click(within(thread).getByTestId('space-thread-send'));
    });
    await waitFor(() => expect(within(thread).getByTestId('space-thread-replies').textContent).toContain('Дякую! Беру 60'));

    // Повернутися на форум і створити нову тему
    fireEvent.click(within(thread).getByTestId('space-back'));
    const forumAgain = await screen.findByTestId('space-forum');
    expect(within(forumAgain).getByTestId('space-topic-solved-tp-rope')).toBeTruthy();

    fireEvent.click(within(forumAgain).getByTestId('space-topic-new'));
    await screen.findByTestId('space-topic-sheet');
    fireEvent.change(screen.getByTestId('space-topic-title'), { target: { value: 'Хто лагодить страховку?' } });
    fireEvent.change(screen.getByTestId('space-topic-body'), { target: { value: 'Потрібно перешити петлю' } });
    await act(async () => {
      fireEvent.click(screen.getByTestId('space-topic-publish'));
    });
    const newThread = await screen.findByTestId('space-thread');
    expect(within(newThread).getByTestId('space-thread-head').textContent).toContain('Хто лагодить страховку?');
    expect(within(newThread).getByTestId('space-thread-head').textContent).toContain('Потрібно перешити петлю');
  });
});
