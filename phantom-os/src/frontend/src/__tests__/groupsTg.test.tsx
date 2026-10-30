/**
 * Групи як у Telegram, крок G1: налаштування гілки, права адмінів, закріплене
 * над стрічкою, канал лише для читання, передача власності при виході.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { GroupProfileSheet } from '../components/messenger/GroupProfileSheet';
import { MemberActions } from '../components/messenger/GroupRoles';
import { MessengerRoot } from '../components/messenger/MessengerRoot';
import { CreateChatModal } from '../components/messenger/CreateChatModal';
import { Header } from '../components/messenger/Header';
import { messengerApi, settingsFromNode } from '../services/messengerApi';
import type { NodeContact, NodeConversation } from '../services/messengerApi';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, ChatMember } from '../types/messenger';

const SETTINGS = { kind: 'group', slow: '0', hist: 'on', ask: 'off', link: '', talk: 'on', sign: 'off' };

const roster = (myRole: string, powers: Record<string, boolean>) => ({
  id: 'g1',
  title: 'Двір',
  members: [
    { id: 'me', name: 'Я', role: myRole, may_remove: false, may_grant: false, may_own: false, in_roster: true, verified: true, session_ready: true, is_me: true },
    { id: 'p1', name: 'Тарас', role: 'admin', rights: ['pin'], may_rights: myRole === 'owner', may_remove: false, may_grant: myRole === 'owner', may_own: myRole === 'owner', in_roster: true, verified: true, session_ready: true, is_me: false },
    { id: 'p2', name: 'Олена', role: 'member', may_remove: true, may_grant: false, may_own: myRole === 'owner', in_roster: true, verified: true, session_ready: true, is_me: false },
  ],
  addable: [],
  max_members: 32,
  authoritative: false,
  standing: 'in',
  my_role: myRole,
  owner_id: myRole === 'owner' ? 'me' : 'p1',
  powers: { add: true, remove: true, rename: true, card: true, grant: true, transfer: true, pin: true, ...powers },
});

const chat = (over: Partial<Chat> = {}): Chat => ({
  id: 'g1',
  title: 'Двір',
  avatar: '',
  type: 'group',
  circle: 'all',
  unreadCount: 0,
  messages: [],
  groupSettings: settingsFromNode({ settings: SETTINGS }),
  ...over,
});

const Live: React.FC = () => {
  const c = useMessengerStore((s) => s.chats[0]);
  if (!c) return null;
  return <GroupProfileSheet chat={c} onClose={() => {}} onSelectMember={() => {}} onTogglePinChat={() => {}} />;
};

const openSheet = (c: Chat) => {
  useMessengerStore.setState({ chats: [c] });
  return render(<Live />);
};

afterEach(() => {
  vi.restoreAllMocks();
  useMessengerStore.setState({ chats: [], activeChatId: '' });
});

describe('налаштування з вузла', () => {
  it('рядок вузла стає налаштуваннями; без поля — стара збірка, налаштувань немає', () => {
    expect(settingsFromNode({ settings: { ...SETTINGS, kind: 'channel', slow: '30', hist: 'off', ask: 'on' } })).toEqual({
      kind: 'channel',
      slow: 30,
      hist: false,
      ask: true,
      link: '',
      talk: true,
      sign: false,
    });
    expect(settingsFromNode({})).toBeUndefined();
  });
});

describe('люди в групі — справжні люди', () => {
  it('учасник: фото з його розмови, імʼя, спільні групи', async () => {
    vi.spyOn(messengerApi, 'groupMembers').mockResolvedValue(roster('owner', { settings: true }) as never);
    useMessengerStore.setState({
      chats: [
        chat(),
        { id: 'dm-o', title: 'Олена Коваль', avatar: 'data:image/png;base64,AAAA', type: 'dm', peerNodeId: 'p2', circle: 'all', unreadCount: 0, messages: [] } as Chat,
        { id: 'g2', title: 'Сад', avatar: '', type: 'group', circle: 'all', unreadCount: 0, messages: [], members: [{ id: 'p2', name: 'Олена', handle: '', avatar: '', role: 'member' }] } as Chat,
      ],
    });
    render(<Live />);
    const name = await screen.findByText('Олена');
    const row = name.closest('li')!;
    expect(row.querySelector('img')).toBeTruthy();
    expect(row.textContent).toContain('Спільні групи: Сад');
  });
});

describe('налаштування групи', () => {
  it('власник відкриває налаштування і вмикає повільний режим — це їде на вузол', async () => {
    vi.spyOn(messengerApi, 'groupMembers').mockResolvedValue(roster('owner', { settings: true, invite: true }) as never);
    const set = vi.spyOn(messengerApi, 'setGroupSettings').mockResolvedValue({
      id: 'g1',
      title: 'Двір',
      kind: 'group',
      settings: { ...SETTINGS, slow: '30' },
    } as unknown as NodeConversation);
    openSheet(chat());
    fireEvent.click(await screen.findByRole('button', { name: 'Налаштування групи' }));
    expect(screen.queryByTestId('slow-30')).toBeNull();
    fireEvent.click(screen.getByTestId('group-settings-perms'));
    fireEvent.click(screen.getByTestId('slow-30'));
    await waitFor(() => expect(set).toHaveBeenCalledWith('g1', { slow: '30' }));
    await waitFor(() => expect(screen.getByTestId('slow-30').getAttribute('aria-checked')).toBe('true'));
    fireEvent.click(screen.getByTestId('setting-hist'));
    await waitFor(() => expect(set).toHaveBeenCalledWith('g1', { hist: 'off' }));
  });

  it('без права на налаштування рядка немає зовсім', async () => {
    vi.spyOn(messengerApi, 'groupMembers').mockResolvedValue(roster('member', { settings: false }) as never);
    openSheet(chat());
    await screen.findByText('Тарас');
    expect(screen.queryByRole('button', { name: 'Налаштування групи' })).toBeNull();
  });
});

describe('права адміна', () => {
  const admin = (over: Partial<ChatMember> = {}): ChatMember => ({
    id: 'p1',
    name: 'Тарас',
    handle: '',
    avatar: '',
    role: 'admin',
    rights: ['pin'],
    mayRights: true,
    ...over,
  });

  it('власник бачить перемикачі прав і додає право — їде повним набором', async () => {
    const put = vi.spyOn(messengerApi, 'setMemberRights').mockResolvedValue(roster('owner', {}) as never);
    vi.spyOn(messengerApi, 'groupMembers').mockResolvedValue(roster('owner', {}) as never);
    render(<MemberActions chat={chat()} member={admin()} />);
    expect(screen.getByTestId('right-pin').getAttribute('aria-checked')).toBe('true');
    expect(screen.getByTestId('right-ban').getAttribute('aria-checked')).toBe('false');
    fireEvent.click(screen.getByTestId('right-ban'));
    await waitFor(() => expect(put).toHaveBeenCalledWith('g1', 'p1', ['pin', 'ban']));
  });

  it('без права роздавати права перемикачів немає; учаснику прав не буває', () => {
    const { rerender } = render(<MemberActions chat={chat()} member={admin({ mayRights: false, mayRemove: true })} />);
    expect(screen.queryByTestId('member-rights')).toBeNull();
    rerender(<MemberActions chat={chat()} member={admin({ role: 'member', mayRemove: true })} />);
    expect(screen.queryByTestId('member-rights')).toBeNull();
  });
});

describe('власник виходить', () => {
  it('обирає нового власника — спершу передача, потім вихід', async () => {
    vi.spyOn(messengerApi, 'groupMembers').mockResolvedValue(roster('owner', {}) as never);
    const calls: string[] = [];
    vi.spyOn(messengerApi, 'setGroupRole').mockImplementation(async (_c, peer, role) => {
      calls.push(`role:${peer}:${role}`);
      return roster('admin', {}) as never;
    });
    vi.spyOn(messengerApi, 'leaveGroup').mockImplementation(async () => {
      calls.push('leave');
      return { left: true, told: 2 };
    });
    openSheet(chat({ myRole: 'owner' }));
    await screen.findByText('Тарас');
    fireEvent.click(screen.getByTestId('info-more'));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Вийти з групи' }));
    fireEvent.click(await screen.findByRole('radio', { name: 'Олена' }));
    fireEvent.click(screen.getByRole('button', { name: 'Передати й вийти' }));
    await waitFor(() => expect(calls).toEqual(['role:p2:owner', 'leave']));
  });
});

describe('стрічка групи й каналу', () => {
  const ROW: NodeConversation = {
    id: 'g1',
    title: 'Новини двору',
    kind: 'group',
    circle: 'all',
    handle: null,
    avatar: null,
    pinned: false,
    archived: false,
    created_at: '2026-10-19T08:00:00',
    updated_at: '2026-10-19T08:00:00',
    settings: { ...SETTINGS, kind: 'channel' },
    pinned_message: { id: 'm9', author_id: 'p1', author: 'Тарас', at: '2026-10-19T08:00:00', text: 'Збір о 18:00' },
  };

  beforeEach(() => {
    Element.prototype.scrollTo = vi.fn() as unknown as Element['scrollTo'];
    useMessengerStore.setState({ chats: [], activeChatId: '', hydrated: false, isGroupDetailsOpen: false });
    vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([ROW]);
    vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'markRead').mockResolvedValue({ unread_count: 0 });
  });

  it('підписник каналу бачить закріплене і смугу без композера', async () => {
    vi.spyOn(messengerApi, 'groupMembers').mockResolvedValue(roster('member', { pin: false }) as never);
    render(
      <MemoryRouter>
        <MessengerRoot />
      </MemoryRouter>,
    );
    expect((await screen.findByTestId('group-pin-bar')).textContent).toContain('Збір о 18:00');
    expect(await screen.findByTestId('channel-read-bar')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Відкріпити' })).toBeNull();
  });

  it('адмін каналу пише як завжди', async () => {
    vi.spyOn(messengerApi, 'groupMembers').mockResolvedValue(roster('owner', {}) as never);
    render(
      <MemoryRouter>
        <MessengerRoot />
      </MemoryRouter>,
    );
    await screen.findByTestId('group-pin-bar');
    await waitFor(() => expect(useMessengerStore.getState().chats[0]?.myRole).toBe('owner'));
    expect(screen.queryByTestId('channel-read-bar')).toBeNull();
  });
});

describe('новий канал', () => {
  const OKSANA = {
    id: 'ct_o',
    display_name: 'Оксана',
    node_id: 'p2',
    session_ready: true,
    reach: 'mutual',
  } as unknown as NodeContact;

  it('двері «Новий канал» — аркуш каналу, і на вузол їде kind channel', async () => {
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([OKSANA]);
    vi.spyOn(messengerApi, 'groupMembers').mockResolvedValue(roster('owner', {}) as never);
    const create = vi.spyOn(messengerApi, 'createConversation').mockResolvedValue({
      id: 'c1',
      title: 'Новини',
      kind: 'group',
      circle: 'all',
      settings: { ...SETTINGS, kind: 'channel' },
    } as unknown as NodeConversation);
    const ready = vi.fn();
    render(<CreateChatModal isOpen onClose={() => {}} onConversationReady={ready} />);
    fireEvent.click(await screen.findByRole('button', { name: /Новий канал/ }));
    const sheet = await screen.findByTestId('new-group-sheet');
    expect(sheet.textContent).toContain('Новий канал');
    expect(sheet.textContent).toContain('Перші підписники');
    fireEvent.change(screen.getByRole('textbox', { name: 'Назва каналу' }), { target: { value: 'Новини' } });
    fireEvent.click(await screen.findByText('Оксана'));
    fireEvent.click(screen.getByRole('button', { name: 'Створити' }));
    await waitFor(() => expect(ready).toHaveBeenCalledWith('c1'));
    expect(create.mock.calls[0][0]).toMatchObject({ title: 'Новини', kind: 'channel', member_ids: ['ct_o'] });
    expect(useMessengerStore.getState().chats[0]?.groupSettings?.kind).toBe('channel');
  });

  it('у шапці каналу — число підписників, а не імена', () => {
    const members: ChatMember[] = [
      { id: 'me', name: 'Я', handle: '', avatar: '', role: 'owner' },
      { id: 'p1', name: 'Тарас', handle: '', avatar: '', role: 'member' },
      { id: 'p2', name: 'Олена', handle: '', avatar: '', role: 'member' },
    ];
    const c = chat({ groupSettings: settingsFromNode({ settings: { ...SETTINGS, kind: 'channel' } }), members });
    const me = { ...useMessengerStore.getState().currentUser, id: 'me' };
    render(
      <MemoryRouter>
        <Header
          currentChat={c}
          currentUser={me}
          onOpenSettings={() => {}}
          onOpenGroupDetails={() => {}}
          isSoundEnabled
          onToggleSound={() => {}}
          onToggleSearch={() => {}}
          isSearching={false}
          onBack={() => {}}
        />
      </MemoryRouter>,
    );
    expect(screen.getByText('3 підписники')).toBeTruthy();
    expect(screen.queryByText(/Тарас/)).toBeNull();
  });
});
