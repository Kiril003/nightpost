/**
 * Builder B1: запис чи повідомлення зі сторінки /p/:handle доходить власнику в застосунок.
 * Рядок «Запити зі сторінок» у списку розмов; відкрили — показані йдуть до сервера як `ack`.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';

const calls: Array<[string, string, unknown]> = [];
let reply: unknown = {};
vi.mock('../services/api', async (orig) => ({
  ...(await orig<typeof import('../services/api')>()),
  request: vi.fn(async (method: string, path: string, body?: unknown) => {
    calls.push([method, path, body]);
    if (path === '/messenger/venues/requests') return reply;
    throw Object.assign(new Error('Not Found'), { status: 404, code: 'not_bridged' });
  }),
}));

import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { pageRequestsOf } from '../services/pageRequests';
import type { Chat, UserProfile } from '../types/messenger';

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;
const AT = new Date(2026, 9, 30, 9, 0).getTime();
const PULL = {
  venues: [{ id: 'v1', name: 'Cafe X' }],
  requests: [
    { id: 'aaaaaaaaaaaaaaa1', venue_id: 'v1', block: 'book-1', kind: 'book', service: 'one', at_ms: AT, at_local: '2026-10-30T09:00', text: '', name: 'Olena', contact: 'olena@example.test', created_ms: 2, picked_ms: null },
    { id: 'aaaaaaaaaaaaaaa2', venue_id: 'v1', block: 'msg-1', kind: 'message', service: null, at_ms: null, at_local: null, text: 'Do you open on Sunday?', name: 'Petr', contact: '+420 000', created_ms: 1, picked_ms: 5 },
  ],
};

const CHATS = [{ id: 'c1', title: 'Оксана', type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false, messages: [], lastSnippet: 'привіт', lastAt: new Date().toISOString() }] as unknown as Chat[];

const renderList = () =>
  render(<PhoneChatList chats={CHATS} currentUser={USER} onSelectChat={vi.fn()} onNewChat={vi.fn()} onOpenChatActions={vi.fn()} />);

describe('запити зі сторінок', () => {
  beforeEach(() => {
    calls.length = 0;
    reply = PULL;
  });

  it('розбір: нові спершу, назва сторінки, криві рядки відкинуто', () => {
    const got = pageRequestsOf({ ...PULL, requests: [...PULL.requests, { id: 'bad', kind: 'book' }, { id: 'aaaaaaaaaaaaaaa3', kind: 'x' }] });
    expect(got.map((r) => [r.id, r.venueName, r.seen])).toEqual([
      ['aaaaaaaaaaaaaaa1', 'Cafe X', false],
      ['aaaaaaaaaaaaaaa2', 'Cafe X', true],
    ]);
  });

  it('у списку розмов — рядок з лічильником нових; відкрили — бачимо запис і повідомлення, нові позначено показаними', async () => {
    renderList();
    const row = (await waitFor(() => {
      const r = document.querySelector('[data-folder-row="page-requests"]');
      expect(r).toBeTruthy();
      return r;
    })) as HTMLElement;
    expect(within(row).getByText('Запити зі сторінок')).toBeTruthy();
    expect(within(row).getByText('1')).toBeTruthy();
    fireEvent.click(row);
    const sheet = await screen.findByTestId('page-requests-sheet');
    expect(within(sheet).getByText('Olena')).toBeTruthy();
    expect(within(sheet).getByText('olena@example.test')).toBeTruthy();
    expect(within(sheet).getByText('Do you open on Sunday?')).toBeTruthy();
    expect(within(sheet).getAllByText('Cafe X')).toHaveLength(2);
    await waitFor(() => expect(calls).toContainEqual(['POST', '/messenger/venues/requests', { ack: ['aaaaaaaaaaaaaaa1'] }]));
  });

  it('K2: замовлення зі сторінки — рядки, доставка і сума від сервера; запис — майстер і передоплата', async () => {
    reply = {
      venues: [{ id: 'v1', name: 'Cafe X' }],
      requests: [
        { id: 'aaaaaaaaaaaaaaa4', venue_id: 'v1', block: 'shop-1', kind: 'order', service: null, at_ms: null, at_local: null, text: 'Ring twice', name: 'Eva', contact: 'eva@example.test', created_ms: 4, picked_ms: null,
          order: { lines: [{ id: 'rye', name: 'Rye loaf', qty: 2, price: '4.5' }, { id: 'bun', name: 'Bun', qty: 3, price: '1.2' }], subtotal: '12.6', fee: '2', total: '14.6', token: 'USDC', fulfil: 'delivery', zone: 'Centre', address: 'Nádražní 5' } },
        { id: 'aaaaaaaaaaaaaaa5', venue_id: 'v1', block: 'book-1', kind: 'book', service: 'one', at_ms: AT, at_local: '2026-10-30T09:00', text: '', name: 'Olena', contact: 'o@example.test', created_ms: 3, picked_ms: null,
          staff: 'olia', staff_name: 'Оля', deposit: { amount: '3.6', token: 'USDC' } },
      ],
    };
    expect(pageRequestsOf(reply).map((r) => r.kind)).toEqual(['order', 'book']);
    expect(pageRequestsOf({ requests: [{ ...(reply as { requests: object[] }).requests[0], order: { lines: 'x' } }] })).toEqual([]);
    renderList();
    const row = (await waitFor(() => {
      const r = document.querySelector('[data-folder-row="page-requests"]');
      expect(r).toBeTruthy();
      return r;
    })) as HTMLElement;
    expect(within(row).getByText('Eva · Замовлення')).toBeTruthy();
    fireEvent.click(row);
    const sheet = await screen.findByTestId('page-requests-sheet');
    const order = sheet.querySelector('[data-page-request="aaaaaaaaaaaaaaa4"]') as HTMLElement;
    expect(within(order).getByText('2 × Rye loaf')).toBeTruthy();
    expect(within(order).getByText('3 × Bun')).toBeTruthy();
    expect(within(order).getByText('Доставка: Centre · 2 USDC')).toBeTruthy();
    expect(within(order).getByText('Nádražní 5')).toBeTruthy();
    expect(within(order).getByText('Разом: 14,6 USDC')).toBeTruthy();
    const book = sheet.querySelector('[data-page-request="aaaaaaaaaaaaaaa5"]') as HTMLElement;
    expect(within(book).getByText('Майстер: Оля')).toBeTruthy();
    expect(within(book).getByText('Передоплата: 3,6 USDC')).toBeTruthy();
  });

  it('K2: запис на курс — курс, наступне заняття і ціна від сервера; без курсу рядок відкинуто', async () => {
    const enroll = { id: 'aaaaaaaaaaaaaaa6', venue_id: 'v1', block: 'course-1', kind: 'enroll', service: null, at_ms: null, at_local: null, text: 'Beginner', name: 'Eva', contact: 'eva@example.test', created_ms: 6, picked_ms: null,
      enroll: { course: 'Photo basics', price: '240.5', token: 'USDC', sessions: 8, next: { name: 'Light and exposure', date: '2026-10-31', time: '19:00' } } };
    reply = { venues: [{ id: 'v1', name: 'Cafe X' }], requests: [enroll] };
    expect(pageRequestsOf(reply).map((r) => [r.kind, r.enroll?.course])).toEqual([['enroll', 'Photo basics']]);
    expect(pageRequestsOf({ requests: [{ ...enroll, enroll: { course: '' } }] })).toEqual([]);
    renderList();
    const row = (await waitFor(() => {
      const r = document.querySelector('[data-folder-row="page-requests"]');
      expect(r).toBeTruthy();
      return r;
    })) as HTMLElement;
    expect(within(row).getByText('Eva · Запис на курс')).toBeTruthy();
    fireEvent.click(row);
    const sheet = await screen.findByTestId('page-requests-sheet');
    const card = sheet.querySelector('[data-page-request="aaaaaaaaaaaaaaa6"]') as HTMLElement;
    expect(within(card).getByText('Photo basics')).toBeTruthy();
    expect(within(card).getByText('Заняття: 8')).toBeTruthy();
    expect(within(card).getByText('Наступне: Light and exposure · 31.10 · 19:00')).toBeTruthy();
    expect(within(card).getByText('Ціна: 240,5 USDC')).toBeTruthy();
    expect(within(card).getByText('Beginner')).toBeTruthy();
  });

  it('немає запитів або міст їх не знає — рядка немає, нічого не вигадано', async () => {
    reply = { venues: [], requests: [] };
    renderList();
    await waitFor(() => expect(calls.some(([, p]) => p === '/messenger/venues/requests')).toBe(true));
    expect(document.querySelector('[data-folder-row="page-requests"]')).toBeNull();
  });
});
