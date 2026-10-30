/**
 * MSG-SYS крок «організація» (_briefs/msg-sys/CARD.md): інфо розмови має
 * вкладки «Платежі» і «Зустрічі» — рахунки, витрати, перекази, зустрічі й події
 * з назвою і сумою, місцем чи днем; торкання веде в лист.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { messengerApi } from '../services/messengerApi';
import { useMessengerStore } from '../stores/messengerStore';
import { GroupProfileSheet } from '../components/messenger/GroupProfileSheet';
import { indexShared } from '../services/peerShared';
import { MessageIndex, foldQuery, fragmentOfMessage } from '../services/messageSearch';
import type { Chat, Message } from '../types/messenger';
import { MemoryRouter } from 'react-router-dom';
import { LiveLocationBubble } from '../components/messenger/LiveLocationBubble';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

let prev: ReturnType<typeof useMessengerStore.getState>;
beforeEach(() => {
  prev = useMessengerStore.getState();
  vi.spyOn(messengerApi, 'groupMembers').mockRejectedValue(new Error('down'));
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  useMessengerStore.setState(prev, true);
});

const at = '2026-10-28T10:25:00Z';
const msg = (id: string, over: Partial<Message>): Message =>
  ({ id, senderId: 'p1', senderName: 'Оксана', senderAvatar: '', timestamp: '12:25', sentAt: at, type: 'text', text: '', isSelf: false, ...over }) as Message;

const BILL = msg('b1', {
  type: 'widget:bill',
  billData: {
    v: 1, id: 'bill1', meetupId: null, venueId: 'v1', title: 'Піца', token: 'USDC',
    items: [{ id: 'i1', name: 'Маргарита', price: '12.5' }, { id: 'i2', name: 'Сік', price: '3' }],
    payee: { kind: 'peer', id: 'p1' }, people: [{ id: 'p1', name: 'Оксана' }],
  },
} as never);
const LEGACY = msg('sb1', { type: 'split-bill', splitBillData: { title: 'Вечеря', totalAmount: 300, currency: 'UAH', participants: [] } } as never);
const EXPENSE = msg('fe1', {
  type: 'widget:family-expense',
  familyExpenseData: { id: 'fe', title: 'Дача', currency: '₴', items: [{ id: 'e1', title: 'Дрова', amountUAH: 420, category: '', paidBy: 'Оксана' }] },
} as never);
const EVENT = msg('ev1', { type: 'event', eventData: { title: 'Суботник', date: '2026-11-01', time: '10:00', location: 'Двір', attendees: [] } } as never);
const TEXT = msg('t1', { text: 'привіт' });

describe('інфо розмови: «Платежі» і «Зустрічі»', () => {
  it('рахунок, старий рахунок і витрати — у «Платежах» із сумою; подія — у «Зустрічах» з днем і місцем', () => {
    const idx = indexShared([TEXT, BILL, LEGACY, EXPENSE, EVENT], 'me');
    expect(idx.payments.map((p) => [p.msgId, p.title, p.sub])).toEqual([
      ['fe1', 'Дача', '420 ₴'],
      ['sb1', 'Вечеря', '300 ₴'],
      ['b1', 'Піца', '15,50 USDC'],
    ]);
    expect(idx.meetups.map((p) => [p.msgId, p.title, p.sub])).toEqual([['ev1', 'Суботник', '1 лист. · 10:00 · Двір']]);
  });

  it('стерта картка не йде ні в «Платежі», ні в «Зустрічі»', () => {
    const idx = indexShared([{ ...BILL, isDeleted: true }, { ...EVENT, isDeleted: true }], 'me');
    expect(idx.payments).toEqual([]);
    expect(idx.meetups).toEqual([]);
  });

  it('у профілі групи — вкладки «Платежі» і «Зустрічі», рядок веде в лист', () => {
    const c = { id: 'g1', title: 'Двір', type: 'group', circle: 'all', avatar: '', unreadCount: 0, pinned: false, messages: [TEXT, BILL, EVENT] } as unknown as Chat;
    useMessengerStore.setState({ chats: [c], currentUser: { id: 'me', name: 'Кирило', avatar: '' } } as never);
    render(<GroupProfileSheet chat={c} onClose={() => {}} onSelectMember={() => {}} onTogglePinChat={() => {}} />);
    const pay = screen.getByTestId('info-tab-payments');
    expect(pay.textContent).toContain('Платежі');
    fireEvent.click(pay);
    const row = screen.getByTestId('shared-payments-row');
    expect(within(row).getByText('Піца')).toBeTruthy();
    expect(row.textContent).toContain('15,50');
    fireEvent.click(screen.getByTestId('info-tab-meetups'));
    expect(screen.getByTestId('shared-meetups-row').textContent).toContain('Суботник');
    // Один день на зустріч: справа не дата надсилання (28 жовт.), а нічого.
    expect(screen.getByTestId('shared-meetups-row').textContent).not.toContain('28');
    fireEvent.click(screen.getByTestId('info-tab-payments'));
    expect(screen.getByTestId('shared-payments-row').textContent).toContain('28');
  });

  it('без платежів і зустрічей — цих вкладок немає, а не нуль', () => {
    const c = { id: 'g2', title: 'Двір', type: 'group', circle: 'all', avatar: '', unreadCount: 0, pinned: false, messages: [TEXT, msg('l1', { text: 'https://ex.ua/a' })] } as unknown as Chat;
    useMessengerStore.setState({ chats: [c], currentUser: { id: 'me', name: 'Кирило', avatar: '' } } as never);
    render(<GroupProfileSheet chat={c} onClose={() => {}} onSelectMember={() => {}} onTogglePinChat={() => {}} />);
    expect(screen.queryByTestId('info-tab-payments')).toBeNull();
    expect(screen.queryByTestId('info-tab-meetups')).toBeNull();
  });
});

describe('пошук знаходить картку за її полями: назва, сума з монетою, місце, день', () => {
  const c = { id: 'g1', title: 'Двір', type: 'group', circle: 'all', avatar: '', unreadCount: 0, pinned: false, messages: [TEXT, BILL, LEGACY, EXPENSE, EVENT] } as unknown as Chat;
  const find = (q: string) => {
    const idx = new MessageIndex('me');
    idx.indexChat(c);
    return idx.search(foldQuery(q)).map((h) => h.messageId).sort();
  };

  it('назва, сума, монета, місце', () => {
    expect(find('піца')).toEqual(['b1']);
    expect(find('15,50')).toEqual(['b1']);
    expect(find('usdc')).toEqual(['b1']);
    expect(find('420')).toEqual(['fe1']);
    expect(find('вечеря')).toEqual(['sb1']);
    expect(find('суботник')).toEqual(['ev1']);
  });

  it('знахідка показує назву картки поруч зі збігом', () => {
    expect(fragmentOfMessage(EVENT, foldQuery('двір'))?.text).toBe('Суботник · 1 лист. · 10:00 · Двір');
    expect(fragmentOfMessage(LEGACY, foldQuery('300'))?.text).toBe('Вечеря · 300 ₴');
  });
});

describe('жива геолокація — без сирих координат', () => {
  const live = (over: Partial<{ label: string; revoked: boolean; expiresAtMs: number }> = {}) => ({
    sessionId: 'l', point: { lat: 50.45, lon: 30.52, atMs: new Date(2026, 9, 29, 14, 32).getTime(), ...(over.label ? { label: over.label } : {}) },
    startedAtMs: Date.now(), expiresAtMs: over.expiresAtMs ?? Date.now() + 600_000, revoked: over.revoked,
  });
  const draw = (loc: ReturnType<typeof live>) =>
    render(<MemoryRouter><LiveLocationBubble location={loc as never} /></MemoryRouter>).getByTestId('live-location-bubble');

  it('наживо: заголовок — коли оновлено, не «50.45000, 30.52000»', () => {
    const card = draw(live());
    expect(card.textContent).not.toMatch(/50\.45|30\.52/);
    expect(card.querySelector('h4')!.textContent).toBe('Оновлено о 14:32');
  });

  it('місце з назвою — назва', () => {
    expect(draw(live({ label: 'Вокзал' })).querySelector('h4')!.textContent).toBe('Вокзал');
  });

  it('закінчилось: останнє положення з часом, без координат', () => {
    const card = draw(live({ expiresAtMs: Date.now() - 1000 }));
    expect(card.textContent).not.toMatch(/50\.45|30\.52/);
    expect(card.textContent).toContain('останнє відоме положення · 14:32');
  });
});
