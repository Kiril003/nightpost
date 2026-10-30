import { describe, it, expect, beforeAll, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, act, fireEvent, waitFor, within } from '@testing-library/react';
import { MessengerRoot } from '../components/messenger/MessengerRoot';
import { DeskSidePane } from '../components/messenger/DeskSidePane';
import { useMessengerStore } from '../stores/messengerStore';
import { useUIStore, type DeskPane } from '../stores/uiStore';
import { messengerApi } from '../services/messengerApi';
import { phantomSelfApi } from '../services/phantomSelfApi';
import { deviceApi } from '../services/deviceApi';
import { solanaApi } from '../services/solanaActions';
import type { Chat, LocationData } from '../types/messenger';

const makeChat = (id: string, title: string, lastAt: string, over: Partial<Chat> = {}): Chat => ({
  id,
  title,
  avatar: '',
  type: 'direct',
  circle: 'friends',
  peerNodeId: `peer-${id}`,
  unreadCount: 0,
  isOnline: true,
  lastSnippet: 'Привіт',
  lastAt,
  messages: [
    {
      id: `msg-${id}`,
      senderId: `peer-${id}`,
      senderName: title,
      senderAvatar: '',
      text: 'Привіт',
      timestamp: '12:00',
      sentAt: lastAt,
      isSelf: false,
      type: 'text',
      status: 'sent',
    },
  ],
  ...over,
});

const PLACE: LocationData = {
  name: 'Café X',
  category: 'cafe',
  address: 'Головна 1',
  distance: '',
  walkingTime: '',
  coords: { lat: 49.83, lng: 18.28 },
};

const key = (k: string, code: string, extra: KeyboardEventInit = {}) =>
  fireEvent.keyDown(window, { key: k, code, ctrlKey: true, ...extra });

const openPane = async (pane: Exclude<DeskPane, null>) => {
  act(() => {
    useUIStore.getState().setDeskPane(pane);
  });
  return waitFor(() => screen.getByTestId('desk-side-pane'), { timeout: 10_000 });
};

const seed = (active: string) => {
  useMessengerStore.setState({
    chats: [
      makeChat('chat-1', 'Олена', '2025-10-10T12:00:00.000Z'),
      makeChat('chat-2', 'Тарас', '2025-10-10T11:00:00.000Z'),
      makeChat('grp-1', 'Команда', '2025-10-10T10:00:00.000Z', { type: 'group', circle: 'work', peerNodeId: undefined }),
      makeChat('chat_saved_notes', 'Збережене', '2025-10-10T09:00:00.000Z', { type: 'saved', peerNodeId: undefined }),
      makeChat('node-1', 'Nightpost', '2025-10-10T08:00:00.000Z', { type: 'phantom', peerNodeId: undefined }),
      makeChat('chan-1', 'Новини', '2025-10-10T07:00:00.000Z', { type: 'channel', peerNodeId: undefined }),
    ],
    activeChatId: active,
    searchQuery: '',
    activeLocationData: null,
    hydrateFromNode: async () => {},
  });
};

describe('pcSidePane: 4-та колонка на >=1024px (day | map | wallet | info)', () => {
  beforeAll(() => {
    Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
    Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
  });

  beforeEach(() => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: 1440 });
    Object.defineProperty(window, 'innerHeight', { configurable: true, writable: true, value: 900 });
    useUIStore.setState({ deskPane: null, toasts: [] });
    seed('');
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
  });

  afterEach(() => {
    delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
    vi.restoreAllMocks();
  });

  it('рендерить desk-side-pane поруч із chat-list і talk-root; вкладки клацаються й кожна показує своє тіло в колонці, activeChatId цілий', async () => {
    render(<MessengerRoot />);

    act(() => {
      useMessengerStore.getState().setActiveChat('chat-1');
    });

    expect(screen.getByTestId('chat-list')).toBeInTheDocument();
    expect(screen.getByTestId('talk-root')).toBeInTheDocument();
    expect(screen.queryByTestId('desk-side-pane')).toBeNull();

    const aside = await openPane('day');
    const bodies: Array<[Exclude<DeskPane, null>, string]> = [
      ['map', 'desk-pane-map'],
      ['wallet', 'desk-pane-wallet-empty'],
      ['info', 'peer-profile'],
      ['day', 'desk-pane-day-empty'],
    ];
    for (const [pane, bodyId] of bodies) {
      fireEvent.click(screen.getByTestId(`desk-pane-tab-${pane}`));
      expect(aside).toHaveAttribute('data-pane', pane);
      expect(screen.getByTestId(`desk-pane-tab-${pane}`)).toHaveAttribute('aria-selected', 'true');
      expect(await within(aside).findByTestId(bodyId)).toBeInTheDocument();
      expect(screen.getByTestId('chat-list')).toBeInTheDocument();
      expect(screen.getByTestId('talk-root')).toBeInTheDocument();
      expect(useMessengerStore.getState().activeChatId).toBe('chat-1');
    }

    fireEvent.click(screen.getByTestId('desk-pane-close'));
    expect(useUIStore.getState().deskPane).toBeNull();
    expect(screen.queryByTestId('desk-side-pane')).toBeNull();
    expect(useMessengerStore.getState().activeChatId).toBe('chat-1');
  });

  it('вкладки не мають горизонтального відступу більше 4px: «Гаманець» (60px) інакше обрізається до «Гамане…»', async () => {
    render(<MessengerRoot />);
    act(() => {
      useMessengerStore.getState().setActiveChat('chat-1');
    });
    await openPane('day');
    for (const pane of ['day', 'map', 'wallet', 'info'] as const) {
      const cls = screen.getByTestId(`desk-pane-tab-${pane}`).className;
      expect(cls).not.toMatch(/(^|\s)px-(2|3|4|5|6)(\s|$)/);
    }
  });

  describe('Інфо: колонка, а не вікно поверх усього', () => {
    it('розмова вдвох: профіль лежить усередині колонки, без порталу й модального діалогу, вкладки доступні', async () => {
      render(<MessengerRoot />);
      act(() => {
        useMessengerStore.getState().setActiveChat('chat-1');
      });
      const aside = await openPane('info');
      const profile = await within(aside).findByTestId('peer-profile');
      expect(aside).toContainElement(profile);
      expect(document.body.querySelector(':scope > [data-testid="peer-profile"]')).toBeNull();
      expect(document.querySelector('[aria-modal="true"]')).toBeNull();
      expect(screen.getByTestId('desk-pane-tab-day')).toBeInTheDocument();
      expect(screen.getByTestId('desk-pane-close')).toBeInTheDocument();
    });

    it('група: профіль групи теж у колонці, без заслінки', async () => {
      render(<MessengerRoot />);
      act(() => {
        useMessengerStore.getState().setActiveChat('grp-1');
      });
      const aside = await openPane('info');
      const sheet = await within(aside).findByTestId('group-profile');
      expect(aside).toContainElement(sheet);
      expect(document.querySelector('.f-scrim')).toBeNull();
      expect(document.querySelector('[aria-modal="true"]')).toBeNull();
    });

    it.each([
      ['chat_saved_notes'],
      ['node-1'],
      ['chan-1'],
    ])('%s без людини за ключем: лист «про розмову», а не профіль вигаданої людини з «Написати»', async (id) => {
      const create = vi.spyOn(messengerApi, 'createConversation').mockResolvedValue({ id: 'new' } as never);
      render(<MessengerRoot />);
      act(() => {
        useMessengerStore.getState().setActiveChat(id);
      });
      const aside = await openPane('info');
      expect(await within(aside).findByTestId('group-profile')).toBeInTheDocument();
      expect(within(aside).queryByTestId('peer-profile')).toBeNull();
      expect(within(aside).queryByTestId('peer-action-write')).toBeNull();
      expect(create).not.toHaveBeenCalled();
    });

    it('«Видалити розмову» з колонки прибирає розмову зі стору й списку; «Очистити історію» чистить стрічку', async () => {
      vi.spyOn(messengerApi, 'deleteConversation').mockResolvedValue(undefined as never);
      vi.spyOn(messengerApi, 'clearConversation').mockResolvedValue(undefined as never);
      render(<MessengerRoot />);
      act(() => {
        useMessengerStore.getState().setActiveChat('chat-2');
      });
      const aside = await openPane('info');
      await within(aside).findByTestId('peer-profile');

      fireEvent.click(within(aside).getByText('Очистити історію'));
      fireEvent.click(within(aside).getByRole('button', { name: 'Очистити' }));
      await waitFor(() => {
        expect(useMessengerStore.getState().chats.find((c) => c.id === 'chat-2')?.messages).toEqual([]);
      });

      act(() => {
        useUIStore.getState().setDeskPane('info');
      });
      const again = await screen.findByTestId('desk-side-pane');
      await within(again).findByTestId('peer-profile');
      fireEvent.click(within(again).getByText('Видалити розмову'));
      fireEvent.click(within(again).getByRole('button', { name: 'Видалити' }));
      await waitFor(() => {
        expect(useMessengerStore.getState().chats.some((c) => c.id === 'chat-2')).toBe(false);
      });
      expect(useMessengerStore.getState().activeChatId).not.toBe('chat-2');
    });
  });

  describe('Інфо: стан листа не переходить на іншу розмову', () => {
    const switchTo = (id: string) =>
      act(() => {
        useMessengerStore.getState().setActiveChat(id);
      });

    it('підтвердження «Видалити розмову» на Олені зникає, коли в списку обрано Тараса, і ніщо не видаляється', async () => {
      const del = vi.spyOn(messengerApi, 'deleteConversation').mockResolvedValue(undefined as never);
      render(<MessengerRoot />);
      switchTo('chat-1');
      const aside = await openPane('info');
      await within(aside).findByTestId('peer-profile');
      fireEvent.click(within(aside).getByText('Видалити розмову'));
      expect(within(aside).getByTestId('peer-profile-confirm')).toBeInTheDocument();

      switchTo('chat-2');
      await waitFor(() => expect(within(aside).queryByTestId('peer-profile-confirm')).toBeNull());
      expect(within(aside).getByTestId('peer-profile-name')).toHaveTextContent('Тарас');
      expect(del).not.toHaveBeenCalled();
    });

    it('набране перейменування Олени не потрапляє в Тараса', async () => {
      const rename = vi.spyOn(messengerApi, 'renameConversation').mockResolvedValue(undefined as never);
      render(<MessengerRoot />);
      switchTo('chat-1');
      const aside = await openPane('info');
      await within(aside).findByTestId('peer-profile');
      fireEvent.click(within(aside).getByTestId('info-more'));
      fireEvent.click(within(aside).getByRole('menuitem', { name: 'Змінити' }));
      fireEvent.change(within(aside).getByLabelText('Як ви називаєте цю людину'), { target: { value: 'Мама' } });

      switchTo('chat-2');
      await waitFor(() => expect(within(aside).queryByLabelText('Як ви називаєте цю людину')).toBeNull());
      expect(within(aside).getByTestId('peer-profile-name')).toHaveTextContent('Тарас');
      expect(rename).not.toHaveBeenCalled();
    });

    it('вихід із групи «Команда» не пише «Ви вийшли» у групі «Родина»; відкрите «Керування» теж скидається', async () => {
      useMessengerStore.setState({
        chats: [
          ...useMessengerStore.getState().chats,
          makeChat('grp-2', 'Родина', '2025-10-10T06:00:00.000Z', { type: 'group', circle: 'friends', peerNodeId: undefined }),
        ],
      });
      render(<MessengerRoot />);
      switchTo('grp-1');
      const aside = await openPane('info');
      await within(aside).findByTestId('group-profile');
      fireEvent.click(within(aside).getByTestId('info-more'));
      fireEvent.click(within(aside).getByText('Вийти з групи'));
      expect(within(aside).getByTestId('group-leave-confirm')).toBeInTheDocument();

      switchTo('grp-2');
      await waitFor(() => expect(within(aside).queryByTestId('group-leave-confirm')).toBeNull());
      fireEvent.click(within(aside).getByTestId('info-more'));
      expect(within(aside).getByText('Вийти з групи')).toBeInTheDocument();

      fireEvent.click(within(aside).getByText('Керування групою'));
      await within(aside).findByTestId('group-profile-manage');
      switchTo('grp-1');
      await waitFor(() => expect(within(aside).queryByTestId('group-profile-manage')).toBeNull());
      expect(within(aside).getByTestId('group-profile')).toBeInTheDocument();
    });
  });

  describe('Інфо: Escape робить один крок назад усередині колонки', () => {
    const esc = () => fireEvent.keyDown(document.body, { key: 'Escape', code: 'Escape' });

    it('«Керування групою»: перший Escape повертає на групу, другий закриває колонку', async () => {
      render(<MessengerRoot />);
      act(() => {
        useMessengerStore.getState().setActiveChat('grp-1');
      });
      const aside = await openPane('info');
      await within(aside).findByTestId('group-profile');
      fireEvent.click(within(aside).getByTestId('info-more'));
      fireEvent.click(within(aside).getByText('Керування групою'));
      await within(aside).findByTestId('group-profile-manage');

      esc();
      expect(useUIStore.getState().deskPane).toBe('info');
      expect(within(aside).queryByTestId('group-profile-manage')).toBeNull();
      expect(within(aside).getByTestId('group-profile')).toBeInTheDocument();

      esc();
      expect(useUIStore.getState().deskPane).toBeNull();
    });

    it('підтвердження виходу з групи: Escape скасовує його, колонка лишається', async () => {
      render(<MessengerRoot />);
      act(() => {
        useMessengerStore.getState().setActiveChat('grp-1');
      });
      const aside = await openPane('info');
      await within(aside).findByTestId('group-profile');
      fireEvent.click(within(aside).getByTestId('info-more'));
      fireEvent.click(within(aside).getByText('Вийти з групи'));
      expect(within(aside).getByTestId('group-leave-confirm')).toBeInTheDocument();

      esc();
      expect(within(aside).queryByTestId('group-leave-confirm')).toBeNull();
      expect(useUIStore.getState().deskPane).toBe('info');
    });

    it('перейменування людини: Escape закриває поле, колонка й імʼя на місці; наступний Escape закриває колонку', async () => {
      render(<MessengerRoot />);
      act(() => {
        useMessengerStore.getState().setActiveChat('chat-1');
      });
      const aside = await openPane('info');
      await within(aside).findByTestId('peer-profile');
      fireEvent.click(within(aside).getByTestId('info-more'));
      fireEvent.click(within(aside).getByRole('menuitem', { name: 'Змінити' }));
      const input = within(aside).getByLabelText('Як ви називаєте цю людину');
      fireEvent.change(input, { target: { value: 'Мама' } });

      fireEvent.keyDown(input, { key: 'Escape', code: 'Escape' });
      expect(within(aside).queryByLabelText('Як ви називаєте цю людину')).toBeNull();
      expect(useUIStore.getState().deskPane).toBe('info');
      expect(within(aside).getByTestId('peer-profile-name')).toHaveTextContent('Олена');

      esc();
      expect(useUIStore.getState().deskPane).toBeNull();
    });

    it('підтвердження «Видалити розмову»: Escape скасовує його, колонка лишається', async () => {
      const del = vi.spyOn(messengerApi, 'deleteConversation').mockResolvedValue(undefined as never);
      render(<MessengerRoot />);
      act(() => {
        useMessengerStore.getState().setActiveChat('chat-1');
      });
      const aside = await openPane('info');
      await within(aside).findByTestId('peer-profile');
      fireEvent.click(within(aside).getByText('Видалити розмову'));
      expect(within(aside).getByTestId('peer-profile-confirm')).toBeInTheDocument();

      esc();
      expect(within(aside).queryByTestId('peer-profile-confirm')).toBeNull();
      expect(useUIStore.getState().deskPane).toBe('info');
      expect(del).not.toHaveBeenCalled();
    });
  });

  describe('Інфо: свій пристрій — не людина', () => {
    it('розмова з власним телефоном показує «Мій пристрій» у колонці, а не профіль з «Заблокувати» й «Поскаржитися»', async () => {
      vi.spyOn(deviceApi, 'list').mockResolvedValue({ stopped: false, devices: [] });
      useMessengerStore.setState({
        chats: [
          ...useMessengerStore.getState().chats,
          makeChat('dev-1', 'Мій Pixel', '2025-10-10T05:00:00.000Z', { mine: true, peerNodeId: 'peer-dev-1' }),
        ],
      });
      render(<MessengerRoot />);
      act(() => {
        useMessengerStore.getState().setActiveChat('dev-1');
      });
      const aside = await openPane('info');
      const sheet = await within(aside).findByTestId('device-sheet');
      expect(aside).toContainElement(sheet);
      expect(document.querySelector('[aria-modal="true"]')).toBeNull();
      expect(within(aside).queryByTestId('peer-profile')).toBeNull();
      expect(within(aside).queryByText('Заблокувати')).toBeNull();
      expect(screen.getByTestId('desk-pane-tab-day')).toBeInTheDocument();
    });
  });

  describe('Гаманець на ПК: один чесний рядок, без запрошення вибрати розмову', () => {
    it.each([[''], ['chat-1'], ['grp-1'], ['chat_saved_notes'], ['node-1']])(
      'активна розмова «%s»: рядок «Переказ недоступний», кнопки й мережі немає',
      async (id) => {
        seed(id);
        render(<MessengerRoot />);
        const aside = await openPane('wallet');
        expect(within(aside).getByTestId('desk-pane-wallet-empty')).toHaveTextContent('Переказ недоступний');
        expect(within(aside).queryByText('Оберіть розмову')).toBeNull();
        expect(within(aside).queryByTestId('desk-pane-wallet-pay')).toBeNull();
        expect(within(aside).queryByText(/Solana/)).toBeNull();
      },
    );
  });

  describe('Гаманець з мостом: лише те, що телефон справді може переказати', () => {
    it('з мостом: переказ — лише людині, мережа береться з відповіді телефона; групі — ні', async () => {
      (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = { call: () => '{}' };
      const peer = vi.spyOn(solanaApi, 'peer').mockResolvedValue({ address: 'Adr1', can_pay: true, cluster: 'mainnet', seeker: false });
      useUIStore.setState({ deskPane: 'wallet' });
      const chats = useMessengerStore.getState().chats;

      const { unmount } = render(<DeskSidePane activeChat={chats.find((c) => c.id === 'chat-1')} />);
      expect(await screen.findByTestId('desk-pane-wallet-pay')).toBeInTheDocument();
      expect(screen.getByText('Solana Mainnet')).toBeInTheDocument();
      expect(peer).toHaveBeenCalledWith('peer-chat-1');
      unmount();

      peer.mockClear();
      render(<DeskSidePane activeChat={chats.find((c) => c.id === 'grp-1')} />);
      await screen.findByTestId('desk-pane-wallet-empty');
      expect(screen.queryByTestId('desk-pane-wallet-pay')).toBeNull();
      expect(peer).not.toHaveBeenCalled();
    });

    it('з мостом і без вибраної розмови колонка кличе обрати розмову, а не каже «недоступно»', () => {
      (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = { call: () => '{}' };
      useUIStore.setState({ deskPane: 'wallet' });
      render(<DeskSidePane activeChat={undefined} />);
      expect(screen.getByTestId('desk-pane-wallet-empty')).toHaveTextContent('Оберіть розмову');
      expect(screen.queryByTestId('desk-pane-wallet-pay')).toBeNull();
    });
  });

  describe('Мапа: місце не переживає ні розмову, ні закриту колонку', () => {
    it('місце з будь-якого входу (картка пристрою, баббл) саме відкриває колонку Мапа з назвою місця', async () => {
      render(<MessengerRoot />);
      act(() => {
        useMessengerStore.getState().setActiveChat('chat-1');
      });
      act(() => {
        useMessengerStore.getState().openLocationSheet(PLACE);
      });
      await waitFor(() => expect(useUIStore.getState().deskPane).toBe('map'));
      const aside = await screen.findByTestId('desk-side-pane');
      expect(within(aside).getByText('Café X')).toBeInTheDocument();
    });

    it('перехід до іншої розмови скидає місце попередньої', async () => {
      render(<MessengerRoot />);
      act(() => {
        useMessengerStore.getState().setActiveChat('chat-1');
      });
      await openPane('map');
      act(() => {
        useMessengerStore.getState().openLocationSheet(PLACE);
      });
      act(() => {
        useMessengerStore.getState().setActiveChat('chat-2');
      });
      await waitFor(() => expect(useMessengerStore.getState().activeLocationData).toBeNull());
      expect(screen.queryByText('Café X')).toBeNull();
    });

    it('Ctrl+2 закрив колонку — місце скинуте, і наступне Ctrl+2 не показує вчорашню кавʼярню', async () => {
      render(<MessengerRoot />);
      act(() => {
        useMessengerStore.getState().setActiveChat('chat-1');
      });
      await openPane('map');
      act(() => {
        useMessengerStore.getState().openLocationSheet(PLACE);
      });
      key('2', 'Digit2');
      expect(useUIStore.getState().deskPane).toBeNull();
      expect(useMessengerStore.getState().activeLocationData).toBeNull();
      key('2', 'Digit2');
      await screen.findByTestId('desk-side-pane');
      expect(screen.queryByText('Café X')).toBeNull();
    });

    it('перемикання на іншу вкладку теж скидає місце', async () => {
      render(<MessengerRoot />);
      act(() => {
        useMessengerStore.getState().setActiveChat('chat-1');
      });
      await openPane('map');
      act(() => {
        useMessengerStore.getState().openLocationSheet(PLACE);
      });
      fireEvent.click(screen.getByTestId('desk-pane-tab-day'));
      await waitFor(() => expect(useMessengerStore.getState().activeLocationData).toBeNull());
    });
  });
});
