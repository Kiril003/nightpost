/**
 * Полиця отриманих подарунків: складається лише з листів-подарунків, що
 * прийшли мені (свої не рахуються), найновіші першими, без повторів. В «Я» —
 * усі, з чесним порожнім станом; у профілі людини — лише від неї, а без
 * подарунків розділу немає. Дотик веде до листа в розмові.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { receivedGifts } from '../components/messenger/gifts/giftCatalogue';
import { PeerProfileSheet } from '../components/messenger/PeerProfileSheet';
import { PhoneSettingsSheet } from '../components/messenger/PhoneSettingsSheet';
import { messengerApi } from '../services/messengerApi';
import { phantomSelfApi } from '../services/phantomSelfApi';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message } from '../types/messenger';

const OKS = 'node_oksana';
const AND = 'node_andriy';

const g = (id: string, from: string, gift: string, sentAt: string, extra: Record<string, unknown> = {}): Message =>
  ({
    id,
    senderId: from,
    senderName: from === OKS ? 'Оксана' : from === AND ? 'Андрій' : 'Кирило',
    senderAvatar: '',
    timestamp: '10:00',
    sentAt,
    type: 'widget:gift',
    isSelf: from === 'me',
    giftData: { v: 1, id: `g_${id}`, gift, ...extra },
  }) as unknown as Message;

const text = (id: string): Message =>
  ({ id, senderId: OKS, senderName: 'Оксана', senderAvatar: '', timestamp: '10:00', type: 'text', text: 'привіт', isSelf: false }) as unknown as Message;

const CHATS = (): Chat[] =>
  [
    {
      id: 'c_oks', title: 'Оксана', type: 'dm', peerNodeId: OKS, muted: false,
      messages: [
        text('t1'),
        g('a', OKS, 'flowers', '2026-10-20T10:00:00Z', { note: 'З днем' }),
        g('b', 'me', 'coffee', '2026-10-21T10:00:00Z'),
        g('c', OKS, 'tea', '2026-10-27T10:00:00Z'),
        g('bad', OKS, '<x>', '2026-10-28T10:00:00Z'),
      ],
    },
    {
      id: 'c_and', title: 'Андрій', type: 'dm', peerNodeId: AND, muted: false,
      messages: [g('d', AND, 'book', '2026-10-25T10:00:00Z'), g('d2', AND, 'book', '2026-10-25T10:00:00Z', {})],
    },
  ] as unknown as Chat[];

let prev: ReturnType<typeof useMessengerStore.getState>;
beforeEach(() => {
  prev = useMessengerStore.getState();
  vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'listBlocked').mockResolvedValue({ blocked: [] });
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'privacy').mockResolvedValue({ read_receipts: true });
  vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
  useMessengerStore.setState({ currentUser: { ...prev.currentUser, id: 'me', name: 'Кирило', avatar: '' }, chats: CHATS() });
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  useMessengerStore.setState(prev, true);
});

describe('що лежить на полиці', () => {
  it('лише отримані, найновіші першими, криві й свої — ні', () => {
    const all = receivedGifts(CHATS(), 'me');
    expect(all.map((x) => x.msgId)).toEqual(['c', 'd', 'd2', 'a']);
    expect(all[3]).toMatchObject({ chatId: 'c_oks', gift: 'flowers', note: 'З днем', from: 'Оксана' });
  });

  it('той самий подарунок двічі (повтор доставки) — один раз', () => {
    const chats = CHATS();
    chats[1].messages.push(g('d', AND, 'book', '2026-10-25T10:00:00Z'));
    expect(receivedGifts(chats, 'me').filter((x) => x.msgId === 'd')).toHaveLength(1);
  });

  it('від однієї людини — лише її', () => {
    expect(receivedGifts(CHATS(), 'me', 'c_oks').map((x) => x.msgId)).toEqual(['c', 'a']);
  });
});

describe('«Я»', () => {
  const openMe = async () => {
    await act(async () => {
      render(<PhoneSettingsSheet isOpen onClose={() => {}} isSoundEnabled onToggleSound={() => {}} onExportAllData={() => {}} />);
    });
    return within(screen.getByTestId('me-screen')).getByTestId('me-gifts');
  };

  it('усі отримані — полицею три в ряд із числом; дотик пружно відкриває один, звідти — до листа', async () => {
    const jump = vi.spyOn(useMessengerStore.getState(), 'openChatAtMessage').mockImplementation(() => {});
    useMessengerStore.setState({ openChatAtMessage: jump } as never);
    const animate = vi.fn(() => ({ finished: Promise.resolve(), cancel: () => {} }));
    (HTMLElement.prototype as unknown as { animate: unknown }).animate = animate;
    try {
      const shelf = await openMe();
      expect(within(shelf).getByTestId('me-gifts-title').textContent).toBe('Подарунки · 4');
      expect(within(shelf).getByTestId('gift-shelf').className).toContain('grid-cols-3');
      const items = within(shelf).getAllByTestId('gift-shelf-item');
      expect(items).toHaveLength(4);
      expect(items[0].getAttribute('aria-label')).toContain('Чай');
      expect(items[0].getAttribute('aria-label')).toContain('Оксана');
      fireEvent.click(items[3]);
      expect(jump).not.toHaveBeenCalled();
      const view = await waitFor(() => screen.getByTestId('gift-view'));
      expect(within(view).getByTestId('gift-view-from').textContent).toBe('Оксана · 20 жовт.');
      expect(within(view).getByTestId('gift-view-note').textContent).toBe('З днем');
      const art = within(view).getByTestId('gift-view-art');
      const call = animate.mock.calls.find((_, i) => animate.mock.contexts[i] === art) as unknown as [Keyframe[]];
      expect(JSON.stringify(call?.[0])).toContain('scale(0.6)');
      fireEvent.click(within(view).getByTestId('gift-view-chat'));
      expect(jump).toHaveBeenCalledWith('c_oks', 'a');
    } finally {
      delete (HTMLElement.prototype as unknown as { animate?: unknown }).animate;
    }
  });

  it('тихий рух — розкриття лише прозорістю', async () => {
    document.documentElement.classList.add('reduce-motion');
    const animate = vi.fn(() => ({ finished: Promise.resolve(), cancel: () => {} }));
    (HTMLElement.prototype as unknown as { animate: unknown }).animate = animate;
    try {
      const shelf = await openMe();
      fireEvent.click(within(shelf).getAllByTestId('gift-shelf-item')[0]);
      await waitFor(() => screen.getByTestId('gift-view'));
      expect(animate).toHaveBeenCalled();
      for (const [frames] of animate.mock.calls as unknown as [Keyframe[]][]) expect(JSON.stringify(frames)).not.toContain('transform');
    } finally {
      document.documentElement.classList.remove('reduce-motion');
      delete (HTMLElement.prototype as unknown as { animate?: unknown }).animate;
    }
  });

  it('подарунків немає — так і сказано', async () => {
    useMessengerStore.setState({ chats: [] });
    const shelf = await openMe();
    expect(within(shelf).getByTestId('gift-shelf-empty').textContent).toBe('Подарунків ще немає');
    expect(within(shelf).queryByTestId('gift-shelf-item')).toBeNull();
  });
});

describe('профіль людини', () => {
  const open = (chatId: string, peer: string) =>
    act(async () => {
      render(<PeerProfileSheet peerNodeId={peer} name="Оксана" chatId={chatId} onClose={() => {}} onWhere={() => {}} />);
    });

  it('лише подарунки від неї', async () => {
    await open('c_oks', OKS);
    const shelf = await waitFor(() => screen.getByTestId('peer-profile-gifts'));
    expect(within(shelf).getAllByTestId('gift-shelf-item').map((el) => el.getAttribute('aria-label'))).toEqual([
      expect.stringContaining('Чай'),
      expect.stringContaining('Квіти'),
    ]);
    expect(shelf.textContent).toContain('Подарунки · 2');
    const jump = vi.fn();
    useMessengerStore.setState({ openChatAtMessage: jump } as never);
    fireEvent.click(within(shelf).getAllByTestId('gift-shelf-item')[1]);
    const view = await waitFor(() => screen.getByTestId('gift-view'));
    expect(within(view).getByTestId('gift-view-note').textContent).toBe('З днем');
    fireEvent.click(within(view).getByTestId('gift-view-chat'));
    expect(jump).toHaveBeenCalledWith('c_oks', 'a');
  });

  it('без подарунків розділу немає', async () => {
    useMessengerStore.setState({ chats: [{ ...CHATS()[0], messages: [text('t1')] }] as unknown as Chat[] });
    await open('c_oks', OKS);
    expect(screen.queryByTestId('peer-profile-gifts')).toBeNull();
  });
});
