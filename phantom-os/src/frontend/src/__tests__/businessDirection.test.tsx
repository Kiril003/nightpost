/**
 * Напрямок «Бізнес»: Вхідні (клієнти окремо від особистого, Нові · В роботі · Готово),
 * Замовлення, Послуги; замовлення в розмові; вхід із «Я».
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { loadProfiles, useProfiles } from '../stores/profilesStore';
import { useMessengerStore } from '../stores/messengerStore';
import { INBOX_KEY, clientChats, statusOfChat, useBizInbox } from '../services/business/inbox';
import { encodeOrder, encodeStatus, type Order } from '../services/business/orders';
import { BusinessEntry } from '../components/messenger/directions/BusinessEntry';
import { BusinessChatBar } from '../components/messenger/directions/BusinessChatBar';
import { OrderCard, OrderStatusLine } from '../components/messenger/directions/OrderCard';
import { OrderSheet } from '../components/messenger/directions/OrderSheet';
import direction, { quickActions, screens, tabs } from '../components/messenger/directions/business';
import { buildFeedRows } from '../components/messenger/feed/feedModel';
import type { Chat, Message } from '../types/messenger';

const ORDER: Order = {
  v: 1,
  id: 'A-217',
  items: [{ name: 'Flat white', qty: 2, cents: 340 }],
  currency: 'USDC',
  pay: 'devnet',
  pickup: '09:15',
  code: '4817',
};
const label = (k: 'order' | 'total' | 'pickup') => ({ order: 'Order', total: 'Total', pickup: 'Pickup' })[k];

const chat = (id: string, title: string, over: Partial<Chat> = {}): Chat =>
  ({
    id,
    title,
    type: 'dm',
    circle: 'work',
    peerNodeId: `p_${id}`,
    messages: [],
    unreadCount: 0,
    lastSnippet: 'hi',
    lastAt: new Date(Date.now() - 60_000).toISOString(),
    ...over,
  }) as unknown as Chat;

const orderMsg = (id = 'm1', isSelf = true): Message =>
  ({ id, type: 'text', text: encodeOrder(ORDER, label), isSelf, senderId: 'me', timestamp: '08:41' }) as Message;

const sendMessage = vi.fn();

function goBusiness() {
  const p = useProfiles.getState().addFromTemplate('business', 'Corner Roastery');
  useProfiles.getState().setActive(p.id);
  return p;
}

beforeEach(() => {
  localStorage.clear();
  useProfiles.setState(loadProfiles());
  useBizInbox.setState({ entries: {} });
  sendMessage.mockReset();
  useMessengerStore.setState({
    sendMessage,
    chats: [
      chat('c_new', 'Aisha', { unreadCount: 2, lastSnippet: 'Table for 6?' }),
      chat('c_done', 'Tereza'),
      chat('c_private', 'Mama', { circle: 'close' }),
      chat('c_group', 'Team', { type: 'group' }),
    ],
  } as never);
});
afterEach(cleanup);

describe('вхід: рядок «Бізнес» у «Я»', () => {
  it('в особистому профілі рядка немає; у бізнесовому є і відкриває чотири вкладки', () => {
    const { unmount } = render(<BusinessEntry />);
    expect(screen.queryByTestId('me-business')).toBeNull();
    unmount();
    act(() => void goBusiness());
    render(<BusinessEntry />);
    expect(screen.getByTestId('me-business-count').textContent).toBe('2');
    fireEvent.click(screen.getByTestId('me-business'));
    const nav = screen.getByTestId('business-direction-nav');
    expect(within(nav).getAllByRole('button').map((b) => b.getAttribute('aria-label'))).toEqual(['Вхідні', 'Замовлення', 'Послуги', 'Я']);
    fireEvent.click(screen.getByTestId('business-direction-back'));
    expect(screen.queryByTestId('business-direction')).toBeNull();
  });

  it('експорт для лейну профілів: вкладки, швидкі дії, екрани', () => {
    expect(tabs).toEqual(['inbox', 'orders', 'services', 'me']);
    expect(quickActions.map((a) => a.id)).toEqual(['order', 'service', 'team']);
    expect(Object.keys(screens)).toEqual(expect.arrayContaining(['inbox', 'orders', 'services', 'me']));
    expect(direction.tabs).toBe(tabs);
  });
});

describe('Вхідні', () => {
  beforeEach(() => {
    act(() => void goBusiness());
    render(<BusinessEntry />);
    fireEvent.click(screen.getByTestId('me-business'));
  });

  it('клієнти окремо від особистого: лише розмови вдвох у колах профілю', () => {
    expect(screen.getByTestId('inbox-row-c_new')).toBeTruthy();
    expect(screen.queryByTestId('inbox-row-c_private')).toBeNull();
    expect(screen.queryByTestId('inbox-row-c_group')).toBeNull();
    expect(screen.getByTestId('inbox-row-c_done')).toBeTruthy();
    fireEvent.click(screen.getByTestId('inbox-filter-done'));
    expect(screen.queryByTestId('inbox-row-c_done')).toBeNull();
  });

  it('стан міняється дотиком і переживає перезапуск', () => {
    fireEvent.click(screen.getByTestId('inbox-move-c_new'));
    expect(screen.queryByTestId('inbox-row-c_new')).toBeNull();
    expect(screen.getByTestId('inbox-row-c_done')).toBeTruthy();
    fireEvent.click(screen.getByTestId('inbox-filter-progress'));
    expect(screen.getByTestId('inbox-row-c_new')).toBeTruthy();
    expect(JSON.parse(localStorage.getItem(INBOX_KEY)!).c_new.status).toBe('progress');
  });

  it('порожньо — чесно, без вигаданих клієнтів', () => {
    act(() => useMessengerStore.setState({ chats: [] } as never));
    expect(screen.getByTestId('inbox-empty')).toBeTruthy();
  });

  it('дотик по рядку відкриває розмову й закриває напрямок', () => {
    const open = vi.fn();
    useMessengerStore.setState({ setActiveChat: open } as never);
    fireEvent.click(within(screen.getByTestId('inbox-row-c_new')).getByText('Aisha'));
    expect(open).toHaveBeenCalledWith('c_new');
    expect(screen.queryByTestId('business-direction')).toBeNull();
  });
});

describe('правки після критика', () => {
  it('1: розмова з «Бізнес» закриває і «Я»', () => {
    act(() => void goBusiness());
    const closeMe = vi.fn();
    useMessengerStore.setState({ setActiveChat: vi.fn() } as never);
    render(<BusinessEntry onChatOpened={closeMe} />);
    fireEvent.click(screen.getByTestId('me-business'));
    fireEvent.click(within(screen.getByTestId('inbox-row-c_new')).getByText('Aisha'));
    expect(closeMe).toHaveBeenCalledTimes(1);
  });

  it('2: прочитана розмова без позначки — «Нові», не «Готово»', () => {
    expect(statusOfChat({ unreadCount: 0, lastAt: undefined }, undefined)).toBe('new');
  });

  it('10: закріплена розмова з другом не клієнт: ні рядка у Вхідних, ні смуги швидких відповідей', () => {
    const p = useProfiles.getState().addFromTemplate('business', 'Shop');
    act(() => {
      useProfiles.getState().setActive(p.id);
      useMessengerStore.setState({ chats: [chat('c_friend', 'Petro', { circle: 'friends', pinned: true }), chat('c_new', 'Aisha')] } as never);
    });
    expect(clientChats(useMessengerStore.getState().chats, p).map((c) => c.id)).toEqual(['c_new']);
    render(<BusinessChatBar chatId="c_friend" />);
    expect(screen.queryByTestId('business-chat-bar')).toBeNull();
  });

  it('5: «Замовлення» — лише ті, що надіслав цей бізнес у клієнтських розмовах', () => {
    act(() => void goBusiness());
    act(() =>
      useMessengerStore.setState({
        chats: [
          chat('c_new', 'Aisha', { messages: [orderMsg('m1', true)] }),
          chat('c_peer', 'Planted', { messages: [{ ...orderMsg('m2', false), text: encodeOrder({ ...ORDER, id: 'A-666' }, label) }] }),
          chat('c_mama', 'Mama', { circle: 'close', messages: [{ ...orderMsg('m3', true), text: encodeOrder({ ...ORDER, id: 'A-777' }, label) }] }),
        ],
      } as never),
    );
    render(<BusinessEntry />);
    fireEvent.click(screen.getByTestId('me-business'));
    fireEvent.click(screen.getByLabelText('Замовлення'));
    expect(screen.getAllByTestId('orders-row-A-217')).toHaveLength(1);
    expect(screen.queryByTestId('orders-row-A-666')).toBeNull();
    expect(screen.queryByTestId('orders-row-A-777')).toBeNull();
  });

  it('3: у «Вхідних» рядок без службових маркерів', () => {
    act(() => void goBusiness());
    act(() => useMessengerStore.setState({ chats: [chat('c_new', 'Aisha', { unreadCount: 1, lastSnippet: 'Замовлення A-1: Готове\nPH-ORS:A-1:ready' })] } as never));
    render(<BusinessEntry />);
    fireEvent.click(screen.getByTestId('me-business'));
    expect(screen.getByTestId('inbox-row-c_new').textContent).not.toContain('PH-ORS');
  });
});

describe('Замовлення', () => {
  it('вкладка збирає замовлення з розмов; без них — порожній стан', () => {
    act(() => void goBusiness());
    render(<BusinessEntry />);
    fireEvent.click(screen.getByTestId('me-business'));
    fireEvent.click(screen.getByLabelText('Замовлення'));
    expect(screen.getByTestId('orders-empty')).toBeTruthy();
    act(() =>
      useMessengerStore.setState({
        chats: [chat('c_new', 'Aisha', { messages: [orderMsg()] }), chat('c_x', 'Ondrej', { messages: [orderMsg('m9', false)] })],
      } as never),
    );
    expect(screen.getAllByTestId('orders-row-A-217')).toHaveLength(1);
    expect(screen.getByTestId('orders-active')).toBeTruthy();
  });
});

describe('замовлення в розмові', () => {
  const withChat = (messages: Message[]) =>
    act(() => useMessengerStore.setState({ chats: [chat('c_new', 'Aisha', { messages })] } as never));

  it('картка: позиції, сума, код, «Тестові платежі» і п’ять кроків', () => {
    const m = orderMsg();
    withChat([m]);
    render(<OrderCard msg={m} />);
    expect(screen.getByTestId('order-total').textContent).toBe('6.80 USDC');
    expect(screen.getByTestId('order-code').textContent).toBe('4817');
    expect(screen.getByText('Тестові платежі')).toBeTruthy();
    expect(within(screen.getByTestId('order-steps')).getAllByRole('listitem')).toHaveLength(5);
  });

  it('мій бік: одна бурштинова дія — наступний крок, і вона шле рядок стану в ту ж розмову', () => {
    const m = orderMsg();
    withChat([m]);
    render(<OrderCard msg={m} />);
    expect(screen.queryByTestId('order-pay')).toBeNull();
    fireEvent.click(screen.getByTestId('order-next'));
    expect(sendMessage).toHaveBeenCalledWith(expect.stringContaining('PH-ORS:A-217:accepted'), { to: 'c_new' });
  });

  it('після «Видано» дій немає; чужий бік не має кнопки кроку', () => {
    const m = orderMsg();
    withChat([m, { id: 's', type: 'text', isSelf: true, text: encodeStatus('A-217', 'done', 'x') } as Message]);
    const { unmount } = render(<OrderCard msg={m} />);
    expect(screen.queryByTestId('order-next')).toBeNull();
    unmount();
    const theirs = orderMsg('m2', false);
    withChat([theirs]);
    render(<OrderCard msg={theirs} />);
    expect(screen.queryByTestId('order-next')).toBeNull();
  });

  it('стрічка малює картку замість тексту, а рядок стану — одним рядком', () => {
    const rows = buildFeedRows([orderMsg(), { id: 's', type: 'text', text: encodeStatus('A-217', 'ready', 'x') } as Message], 'me');
    expect(rows.map((r) => r.cardView)).toEqual([true, true]);
    render(<OrderStatusLine id="A-217" status="ready" />);
    expect(screen.getByTestId('order-status-line').textContent).toBe('Замовлення A-217 · Готове');
  });

  it('смуга над композером: лише в бізнесі й лише в клієнтській розмові; швидка відповідь іде в розмову', () => {
    const { unmount } = render(<BusinessChatBar chatId="c_new" />);
    expect(screen.queryByTestId('business-chat-bar')).toBeNull();
    unmount();
    act(() => void goBusiness());
    const r = render(<BusinessChatBar chatId="c_private" />);
    expect(screen.queryByTestId('business-chat-bar')).toBeNull();
    r.unmount();
    render(<BusinessChatBar chatId="c_new" />);
    fireEvent.click(screen.getByTestId('business-quick-ready5'));
    expect(sendMessage).toHaveBeenCalledWith('Буде готово за 5 хв', { to: 'c_new' });
  });

  it('аркуш: порожнє замовлення не їде; з позицією їде текстом з рядком картки', () => {
    act(() => void goBusiness());
    const onClose = vi.fn();
    render(<OrderSheet chatId="c_new" onClose={onClose} />);
    fireEvent.click(screen.getByText(/^Надіслати/));
    expect(sendMessage).not.toHaveBeenCalled();
    expect(screen.getByTestId('order-error')).toBeTruthy();
    fireEvent.change(screen.getByTestId('order-name'), { target: { value: 'Flat white' } });
    fireEvent.change(screen.getByTestId('order-price'), { target: { value: '3.4' } });
    fireEvent.click(screen.getByTestId('order-add'));
    fireEvent.click(screen.getByTestId('order-pay-devnet'));
    fireEvent.click(screen.getByText(/^Надіслати/));
    const [text, opts] = sendMessage.mock.calls[0]!;
    expect(text).toContain('Flat white');
    expect(text).toContain('PH-ORD:');
    expect(opts).toEqual({ to: 'c_new' });
    expect(onClose).toHaveBeenCalled();
  });

  it('час видачі — колеса годин зі спільних вікон «Коли», без системного поля; хрестик — «Будь-коли»', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 9, 9, 12, 0));
    try {
      act(() => void goBusiness());
      render(<OrderSheet chatId="c_new" onClose={() => {}} />);
      expect(document.querySelector('input[type="time"]')).toBeNull();
      expect(screen.getByTestId('order-pickup').textContent).toContain('Будь-коли');
      fireEvent.click(screen.getByTestId('order-pickup'));
      fireEvent.click(within(screen.getByRole('listbox', { name: 'Години' })).getByRole('option', { name: '18' }));
      fireEvent.click(within(screen.getByRole('listbox', { name: 'Хвилини' })).getByRole('option', { name: '30' }));
      fireEvent.click(screen.getByRole('button', { name: /^Обрати · / }));
      expect(screen.getByTestId('order-pickup').textContent).toContain('18:30');
      fireEvent.change(screen.getByTestId('order-name'), { target: { value: 'Flat white' } });
      fireEvent.change(screen.getByTestId('order-price'), { target: { value: '3.4' } });
      fireEvent.click(screen.getByTestId('order-add'));
      fireEvent.click(screen.getByText(/^Надіслати/));
      expect(sendMessage.mock.calls[0]![0]).toContain('18:30');
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('досяжність із справжніх входів', () => {
  const src = (p: string) => readFileSync(join(process.cwd(), 'src/components/messenger', p), 'utf8');
  it('«Я» несе рядок, композер — смугу, стрічка — картку замовлення', () => {
    expect(src('MeScreen.tsx')).toContain('<BusinessEntry onChatOpened={onClose} />');
    expect(src('MessageComposer.tsx')).toContain('<BusinessChatBar chatId={chatId} />');
    expect(src('feed/MessageEmbeds.tsx')).toContain('<OrderCard msg={msg} />');
    expect(src('feed/feedModel.ts')).toContain('isOrderMessage(msg)');
  });
});
