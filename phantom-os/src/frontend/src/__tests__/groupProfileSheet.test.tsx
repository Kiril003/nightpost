/**
 * Профіль групи — аркуш мовою форми, що замінив пʼятивкладковий шар.
 *
 * Червоніти вміє: поверніть фільтр `m.imageData` замість `indexShared` —
 * падає «зашифроване фото в Спільному»; поверніть `GroupDetailsDrawer` у
 * MessengerRoot — падає «дотик до назви групи»; приберіть крок згоди перед
 * `leaveGroup` — падає «вихід питає згоди».
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../services/messengerMedia', async (orig) => ({
  ...(await orig<typeof import('../services/messengerMedia')>()),
  cachedMediaUrl: () => undefined,
  loadMedia: vi.fn().mockResolvedValue('blob:group-photo'),
}));

import { GroupProfileSheet } from '../components/messenger/GroupProfileSheet';
import { MessengerRoot } from '../components/messenger/MessengerRoot';
import { messengerApi } from '../services/messengerApi';
import type { NodeConversation } from '../services/messengerApi';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message } from '../types/messenger';

const ROSTER = {
  id: 'g1',
  title: 'Двір',
  members: [
    { id: 'me', name: 'Я', role: 'owner', may_remove: false, may_grant: false, may_own: false, in_roster: true, verified: true, session_ready: true, is_me: true },
    { id: 'p1', name: 'Тарас', role: 'member', may_remove: true, may_grant: false, may_own: false, in_roster: true, verified: true, session_ready: true, is_me: false },
    { id: 'p2', name: 'Олена', role: 'member', may_remove: true, may_grant: false, may_own: false, in_roster: true, verified: false, session_ready: true, is_me: false },
  ],
  addable: [],
  max_members: 32,
  authoritative: false,
  standing: 'in',
  my_role: 'owner',
  owner_id: 'me',
  powers: { add: true, remove: true, rename: true, card: true, grant: true, transfer: true, pin: true },
};

const chat = (over: Partial<Chat> = {}): Chat => ({
  id: 'g1',
  title: 'Двір',
  avatar: '',
  type: 'group',
  circle: 'all',
  unreadCount: 0,
  messages: [],
  ...over,
});

const photo = {
  id: 'm1',
  senderId: 'p1',
  senderName: 'Тарас',
  senderAvatar: '',
  timestamp: '10:00',
  sentAt: '2026-09-25T10:00:00Z',
  type: 'image',
  text: '',
  media: { name: 'двір.jpg', size: 2048, mime: 'image/jpeg', sha256: 'ab', blobId: 'blob_1', keyHex: '00', nonceHex: '00' },
} as unknown as Message;

beforeEach(() => {
  vi.spyOn(messengerApi, 'groupMembers').mockResolvedValue(ROSTER as never);
});

afterEach(() => {
  vi.restoreAllMocks();
  useMessengerStore.setState({ chats: [], activeChatId: '' });
});

/** Як у MessengerRoot: розмова — зі стору, тож склад із вузла доходить до аркуша. */
const Live: React.FC = () => {
  const c = useMessengerStore((s) => s.chats[0]);
  // afterEach чистить стор раніше, ніж RTL знімає дерево; MessengerRoot так
  // само не монтує аркуш без розмови.
  if (!c) return null;
  return <GroupProfileSheet chat={c} onClose={() => {}} onSelectMember={() => {}} onTogglePinChat={() => {}} />;
};

const openSheet = (c: Chat) => {
  useMessengerStore.setState({ chats: [c] });
  return render(<Live />);
};

describe('«Спільне» групи', () => {
  it('зашифроване фото (SecureMedia) стоїть у Спільному, а не лише старе imageData', async () => {
    openSheet(chat({ messages: [photo] }));
    const tab = await screen.findByTestId('info-tab-media');
    expect(tab.textContent).toContain('1');
    fireEvent.click(tab);
    const cell = screen.getByTestId('shared-media-cell');
    await waitFor(() => expect(cell.querySelector('img')?.getAttribute('src')).toBe('blob:group-photo'));
  });

  it('нічого спільного — лише вкладка складу, без вкладок із нулем', async () => {
    openSheet(chat());
    await screen.findByText('Тарас');
    expect(screen.getAllByRole('tab').map((x) => x.getAttribute('data-testid'))).toEqual(['info-tab-members']);
  });
});

describe('склад і двері', () => {
  it('склад із вузла: щабель і «ви», без механіки довіри; пошуку для трьох людей немає', async () => {
    openSheet(chat());
    expect(await screen.findByText('Тарас')).toBeTruthy();
    expect(screen.getAllByText(/^власник/i).length).toBeGreaterThan(0);
    const text = screen.getByTestId('group-profile').textContent ?? '';
    expect(text).not.toMatch(/звірено|ключ|зі слів|остаточний список/i);
    expect(screen.queryByPlaceholderText('Шукати серед цих учасників')).toBeNull();
  });

  it('T44: рядок учасника має висоту з відступу — на телефоні min-h-[…] поруч із min-w-0 перебиває підлога дотику', async () => {
    openSheet(chat());
    const row = (await screen.findByText('Тарас')).closest('button')!;
    expect(row.className).toMatch(/(^|\s)py-1\.5(\s|$)/);
    expect(row.className).not.toMatch(/min-h-\[/);
  });

  it('«Додати людей» веде в підекран — без технічних приміток про дріт', async () => {
    openSheet(chat());
    await screen.findByText('Тарас');
    fireEvent.click(screen.getByRole('button', { name: 'Запросити' }));
    expect(screen.getByRole('dialog', { name: 'Додати людей' })).toBeTruthy();
    expect(screen.queryByText(/дріт|ключем|мосту/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Назад' }));
    expect(screen.getByRole('dialog', { name: 'Група: Двір' })).toBeTruthy();
  });
});

describe('вихід питає згоди', () => {
  it('перший дотик не виходить; згода кличе вузол і каже, скільком сказано', async () => {
    const leave = vi.spyOn(messengerApi, 'leaveGroup').mockResolvedValue({ left: true, told: 2 });
    openSheet(chat());
    fireEvent.click(await screen.findByTestId('info-more'));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Вийти з групи' }));
    expect(leave).not.toHaveBeenCalled();
    const confirm = screen.getByTestId('group-leave-confirm');
    fireEvent.click(confirm.querySelector('button')!);
    await waitFor(() => expect(leave).toHaveBeenCalledWith('g1'));
    expect((await screen.findByTestId('group-leave-said')).textContent).toContain('сказати 2 людям');
  });
});

describe('двері в профіль групи', () => {
  const GROUP: NodeConversation = {
    id: 'g1',
    title: 'Двір',
    kind: 'group',
    circle: 'all',
    handle: null,
    avatar: null,
    pinned: false,
    archived: false,
    created_at: '2026-09-04T10:00:00',
    updated_at: '2026-09-04T10:00:00',
  };

  it('дотик до назви групи в шапці відкриває новий аркуш зі складом', async () => {
    Element.prototype.scrollTo = vi.fn() as unknown as Element['scrollTo'];
    useMessengerStore.setState({ chats: [], activeChatId: '', hydrated: false, isGroupDetailsOpen: false });
    vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([GROUP]);
    vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'markRead').mockResolvedValue({ unread_count: 0 });
    // Аркуш — лінивий чанк; перше перетворення модулів у vitest довше за
    // 1 с findBy, тож тест падав на часі, а не на поведінці. Гріємо чанк наперед.
    await import('../components/messenger/modals/rootModals');
    render(
      <MemoryRouter>
        <MessengerRoot />
      </MemoryRouter>,
    );
    fireEvent.click(await screen.findByTitle('Про розмову'));
    // На ПК це колонка Інфо, а не діалог: лінивий чанк колонки довший за 1 с findBy.
    const aside = await screen.findByTestId('desk-side-pane', undefined, { timeout: 10_000 });
    expect(await within(aside).findByRole('region', { name: 'Група: Двір' })).toBeTruthy();
    expect(await within(aside).findByText('Тарас')).toBeTruthy();
    expect(within(aside).getByTestId('info-kind').textContent).toBe('Група · 3 учасники');
  });
});

/**
 * 03.10, v1.2: обличчя групи — без сяйва тону (DS §7: жодних світінь), а дотик
 * до фото групи відкриває той самий переглядач, що й фото людини: з рядком
 * правди про мініатюру, а не лайтбокс вкладень з «оригіналом».
 * Поверніть `radial-gradient` або `openLightbox` — тести червоніють.
 */
describe('обличчя групи — мовою v1.2', () => {
  const FACE = 'data:image/webp;base64,UklGRgAAAABXRUJQ';

  it('без сяйва тону за аватаром', async () => {
    openSheet(chat());
    await screen.findByText('Тарас');
    const sheet = screen.getByTestId('group-profile');
    const glowing = [...sheet.querySelectorAll<HTMLElement>('*')].filter((el) => /gradient/.test(el.getAttribute('style') ?? ''));
    expect(glowing).toEqual([]);
  });

  it('дотик до фото групи — переглядач обличчя, не лайтбокс вкладень', async () => {
    const lightbox = vi.fn();
    const prev = useMessengerStore.getState().openLightbox;
    useMessengerStore.setState({ openLightbox: lightbox });
    try {
      openSheet(chat({ avatar: FACE }));
      fireEvent.click(await screen.findByTestId('group-profile-photo'));
      expect(lightbox).not.toHaveBeenCalled();
      const viewer = await screen.findByTestId('avatar-viewer');
      expect(viewer.querySelector('img')?.getAttribute('src')).toBe(FACE);
      expect(viewer.textContent).toContain('Двір');
      expect(viewer.textContent).not.toMatch(/мініатюра/i);
      // Удень імʼя `text-ink` на чорному тлі зникало: тло — полотно теми.
      expect(viewer.className).not.toMatch(/bg-black|animate-in/);
      expect(viewer.className).toContain('bg-canvas');
      fireEvent.click(within(viewer).getByRole('button', { name: 'Закрити' }));
      expect(screen.queryByTestId('avatar-viewer')).toBeNull();
    } finally {
      useMessengerStore.setState({ openLightbox: prev });
    }
  });
});

describe('учасники — людськими іменами (A52, 02.10)', () => {
  it('себе — своїм імʼям, а не «747a·a842»; безіменного — «Без імені», а не «Вузол 4d839df7»', async () => {
    vi.spyOn(messengerApi, 'groupMembers').mockResolvedValue({
      ...ROSTER,
      members: [
        { ...ROSTER.members[0], name: '747a·a842' },
        { ...ROSTER.members[1], name: 'Вузол 4d839df7' },
        ROSTER.members[2],
      ],
    } as never);
    useMessengerStore.setState({ currentUser: { ...useMessengerStore.getState().currentUser, name: 'Nightpost-A52' } } as never);
    openSheet(chat());
    await waitFor(() => expect(screen.getByText('Олена')).toBeTruthy());
    expect(screen.getByText('Nightpost-A52')).toBeTruthy();
    expect(screen.getByText('Без імені')).toBeTruthy();
    expect(document.body.textContent).not.toMatch(/747a·a842|Вузол 4d839df7/);
  });
});

/**
 * L4 зріз 2: група на каркасі InfoView (рендер g-info, g-settings).
 * Червоніти вміє: покажіть «Запросити» без права додавати — падає «лише з
 * правом»; поверніть кнопку «Закріпити» в швидкі дії — падає «не більше трьох,
 * закріплення в ⋮»; приберіть «Керування» з налаштувань — падає останній.
 */
describe('інфо-вигляд групи (L4)', () => {
  const settings = { kind: 'group' as const, slow: 0, hist: true, ask: false, link: '', talk: true, sign: false };

  it('«Запросити» — лише з правом додавати; без нього бурштину немає', async () => {
    vi.spyOn(messengerApi, 'groupMembers').mockResolvedValue({ ...ROSTER, my_role: 'member', powers: { ...ROSTER.powers, add: false } } as never);
    openSheet(chat());
    await screen.findByText('Тарас');
    expect(screen.queryByTestId('group-action-add')).toBeNull();
    expect(screen.queryByText('Додати учасників')).toBeNull();
  });

  it('швидкі дії — звук і пошук, закріплення і вихід у ⋮', async () => {
    const WithSearch: React.FC = () => {
      const c = useMessengerStore((st) => st.chats[0]);
      return c ? <GroupProfileSheet chat={c} onClose={() => {}} onSelectMember={() => {}} onTogglePinChat={() => {}} onSearch={() => {}} /> : null;
    };
    useMessengerStore.setState({ chats: [chat()] });
    render(<WithSearch />);
    await screen.findByText('Тарас');
    const acts = screen.getByTestId('info-actions');
    expect(within(acts).getAllByRole('button').map((b) => b.getAttribute('data-testid'))).toEqual(['group-action-add', 'group-action-mute', 'group-action-search']);
    fireEvent.click(screen.getByTestId('info-more'));
    const items = screen.getAllByRole('menuitem').map((m) => m.textContent);
    expect(items).toEqual(['Закріпити розмову', 'Керування групою', 'Журнал', 'Поскаржитися на групу', 'Вийти з групи']);
  });

  it('налаштування — рядок лише з правом; всередині «Керування»: адміністратори й журнал', async () => {
    vi.spyOn(messengerApi, 'groupMembers').mockResolvedValue({ ...ROSTER, powers: { ...ROSTER.powers, settings: true } } as never);
    openSheet(chat({ groupSettings: settings }));
    await screen.findByText('Тарас');
    fireEvent.click(await screen.findByTestId('group-row-settings'));
    expect(screen.getByTestId('group-settings-admins').textContent).toContain('1');
    fireEvent.click(screen.getByTestId('group-settings-log'));
    expect(screen.getByTestId('group-profile-log')).toBeTruthy();
  });
});
