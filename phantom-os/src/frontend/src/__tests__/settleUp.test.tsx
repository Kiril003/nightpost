import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { BillCard } from '../components/messenger/meetup/BillCard';
import { SettleUpHost } from '../components/messenger/SettleUpSheet';
import { settleRef, settleTag } from '../services/money/ledger';
import { useMessengerStore } from '../stores/messengerStore';
import { useSettleUp } from '../stores/settleUpStore';
import type { BillData } from '../types/meetup';
import type { Message } from '../types/messenger';
import { ME, OLENA, PETRO, installBridge, removeBridge, type Call } from './meetupFixtures';

let prev: ReturnType<typeof useMessengerStore.getState>;
let calls: Call[];

const flush = () =>
  act(async () => {
    for (let i = 0; i < 6; i++) await Promise.resolve();
  });

const PEOPLE = [
  { id: ME, name: 'Я' },
  { id: OLENA, name: 'Олена' },
  { id: PETRO, name: 'Петро' },
];

function makeBill(id: string, billId: string, payeeId: string, price: string, holders: string[], token: 'USDC' | 'SOL' = 'USDC'): Message {
  const bill: BillData = {
    v: 1,
    id: billId,
    meetupId: 'mu-1',
    venueId: 'cafe-x',
    title: 'Cafe X',
    token,
    items: [{ id: 'dish1', name: 'Страва', price }],
    payee: { kind: 'peer', id: payeeId },
    people: PEOPLE,
  };
  return {
    id,
    senderId: payeeId,
    senderName: payeeId,
    senderAvatar: '',
    timestamp: '19:00',
    type: 'widget:bill' as never,
    billData: bill,
    cardMarks: { 'dish:dish1': holders },
  } as unknown as Message;
}

let seq = 0;
function makePay(senderId: string, amount: string, ref: string): Message {
  return {
    id: `pay-${++seq}`,
    senderId,
    senderName: senderId,
    senderAvatar: '',
    timestamp: '19:10',
    type: 'text',
    payment: {
      amount,
      token: 'USDC',
      cluster: 'devnet',
      to: 'BxsSMk2r7BPxLChLdAE7Jv7UuDbZbaBRis31PgWTDfJH',
      toMine: true,
      signature: `sig-${seq}-${'a'.repeat(50)}`,
      status: 'confirmed',
      ref,
    },
  } as unknown as Message;
}

function seed(messages: Message[], me = PETRO) {
  useMessengerStore.setState({
    chats: [{ id: 'grp', type: 'group', title: 'Cafe X · 19:00', messages, unreadCount: 0 } as never],
    activeChatId: 'grp',
    currentUser: { ...useMessengerStore.getState().currentUser, id: me, name: me },
  });
}

beforeEach(() => {
  prev = useMessengerStore.getState();
  useSettleUp.getState().close();
  calls = installBridge((c) => (c.p === '/messenger/solana/pay' ? { data: { opened: true } } : undefined));
});

afterEach(() => {
  cleanup();
  removeBridge();
  useSettleUp.getState().close();
  useMessengerStore.setState(prev, true);
  localStorage.clear();
});

describe('SettleUpHost and BillCard entry', () => {
  const twoLines = () => {
    const b1 = makeBill('row-b1', 'bill-1', OLENA, '600', [PETRO, ME]);
    const b2 = makeBill('row-b2', 'bill-2', PETRO, '150', [PETRO, ME]);
    seed([b1, b2], PETRO);
    act(() => useSettleUp.getState().open('grp'));
  };

  it('lists my line first although it is the smaller one, and calls pay with peer_id, chat_id and su- ref', async () => {
    twoLines();

    render(<SettleUpHost />);
    const sheet = screen.getByTestId('settle-up-sheet');
    const rows = within(sheet).getAllByRole('listitem');
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toContain('Ви');
    expect(rows[0].textContent).toContain('Олена');
    expect(rows[0].textContent).toContain('225,00\u00A0USDC');
    expect(rows[1].textContent).toContain('Я');
    expect(rows[1].textContent).toContain('375,00\u00A0USDC');

    const btn = within(sheet).getByRole('button', { name: /Сплатити 225,00\sUSDC/ });
    fireEvent.click(btn);
    await flush();

    const asks = calls.filter((c) => c.p === '/messenger/solana/pay');
    expect(asks).toHaveLength(1);
    expect(asks[0].m).toBe('POST');
    expect(asks[0].b).toMatchObject({
      peer_id: OLENA,
      token: 'USDC',
      amount: '225',
      chat_id: 'grp',
    });
    expect((asks[0].b as { ref: string }).ref.startsWith(`su-${settleTag(OLENA)}-`)).toBe(true);
    // T49: гаманець відкрився — аркуш «Розрахуватися» закривається.
    expect(useSettleUp.getState().chatId).toBeNull();
  });

  it('a double tap on Pay opens one transfer', async () => {
    twoLines();
    render(<SettleUpHost />);
    const btn = within(screen.getByTestId('settle-up-sheet')).getByRole('button', { name: /Сплатити/ });
    fireEvent.click(btn);
    fireEvent.click(btn);
    await flush();
    expect(calls.filter((c) => c.p === '/messenger/solana/pay')).toHaveLength(1);
  });

  it('Pay offers the largest open line in one token, never comparing amounts across tokens', async () => {
    const usdc = makeBill('row-b1', 'bill-1', OLENA, '6', [PETRO, OLENA]);
    const sol = makeBill('row-b2', 'bill-2', OLENA, '1', [PETRO, OLENA], 'SOL');
    seed([usdc, sol], PETRO);
    act(() => useSettleUp.getState().open('grp'));
    render(<SettleUpHost />);
    const sheet = screen.getByTestId('settle-up-sheet');
    expect(within(sheet).getAllByRole('listitem')).toHaveLength(2);
    fireEvent.click(within(sheet).getByRole('button', { name: /Сплатити 3,00\sUSDC/ }));
    await flush();
    expect(calls.find((c) => c.p === '/messenger/solana/pay')?.b).toMatchObject({ token: 'USDC', amount: '3' });
  });

  it('a chat without bills shows no sheet and no settled line', () => {
    seed([], PETRO);
    act(() => useSettleUp.getState().open('grp'));
    render(<SettleUpHost />);
    expect(screen.queryByTestId('settle-up-sheet')).toBeNull();
    expect(document.body.textContent).not.toContain('Усе сплачено');
    expect(useSettleUp.getState().chatId).toBeNull();
  });

  it('shows settled line and no footer when ledger is empty', () => {
    const b1 = makeBill('row-b1', 'bill-1', OLENA, '200', [OLENA, PETRO]);
    const b2 = makeBill('row-b2', 'bill-2', OLENA, '200', [OLENA, PETRO]);
    const p = makePay(PETRO, '200', settleRef(OLENA));
    seed([b1, b2, p], PETRO);
    act(() => useSettleUp.getState().open('grp'));

    render(<SettleUpHost />);
    const sheet = screen.getByTestId('settle-up-sheet');
    expect(sheet.textContent).toContain('Усе сплачено');
    expect(within(sheet).queryByRole('button', { name: /Сплатити/ })).toBeNull();
  });

  it('BillCard settle button shows with 2 bills and hides with 1', () => {
    const b1 = makeBill('row-b1', 'bill-1', OLENA, '200', [OLENA, PETRO]);
    seed([b1], PETRO);
    const { unmount } = render(<BillCard msg={b1} meId={PETRO} />);
    expect(screen.queryByTestId('bill-card-settle')).toBeNull();
    unmount();

    const b2 = makeBill('row-b2', 'bill-2', PETRO, '100', [OLENA, PETRO]);
    seed([b1, b2], PETRO);
    render(
      <>
        <BillCard msg={b1} meId={PETRO} />
        <SettleUpHost />
      </>,
    );
    const btn = screen.getByTestId('bill-card-settle');
    expect(btn.textContent).toBe('Розрахуватися');
    fireEvent.click(btn);
    expect(useSettleUp.getState().chatId).toBe('grp');
    expect(screen.getByTestId('settle-up-sheet')).toBeTruthy();
  });
});
