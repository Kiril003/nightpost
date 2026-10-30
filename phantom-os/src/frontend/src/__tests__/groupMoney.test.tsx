import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, cleanup, act, waitFor, within } from '@testing-library/react';
import React from 'react';

const sendBillMock = vi.fn();
const canPayHereMock = vi.fn(() => true);

vi.mock('../services/meetupApi', () => ({
  sendBill: (...args: unknown[]) => sendBillMock(...args),
  canPayHere: () => canPayHereMock(),
}));

import { GroupMoneySheet } from '../components/messenger/solana/GroupMoneySheet';
import { PayMoneySheet } from '../components/messenger/solana/PayMoneySheet';
import { SolanaSheets } from '../components/messenger/solana/SolanaSheets';
import { askSolana, solanaApi, useSolanaAsk } from '../services/solanaActions';
import { dropPayNote, holdPayNote, pendingPayNotes } from '../services/money/payNotes';
import { requestBill } from '../services/money/request';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message } from '../types/messenger';

const ME = 'me';
const B = 'peer-b';
const C = 'peer-c';
const D = 'peer-d';

const door = (testId: string) => within(screen.getByTestId(testId)).getByRole('button');

const MEMBERS = [
  { id: ME, name: 'Я', role: 'owner' as const, isMe: true },
  { id: B, name: 'Богдан', role: 'member' as const },
  { id: C, name: 'Василь', role: 'member' as const },
  { id: D, name: 'Ганна', role: 'member' as const },
];

function setChat(members = MEMBERS, messages: Message[] = []) {
  useMessengerStore.setState({
    currentUser: { ...useMessengerStore.getState().currentUser, id: ME, name: 'Я' },
    activeChatId: 'g1',
    chats: [
      {
        id: 'g1',
        title: 'Група',
        type: 'group',
        members,
        messages,
      } as unknown as Chat,
    ],
  });
}

beforeEach(() => {
  localStorage.clear();
  for (const n of pendingPayNotes()) dropPayNote(n.ref);
  sendBillMock.mockReset();
  sendBillMock.mockResolvedValue({ id: 'msg-1' });
  canPayHereMock.mockReturnValue(true);
  setChat();
});

afterEach(() => {
  act(() => askSolana(null));
  cleanup();
  vi.restoreAllMocks();
});

describe('GroupMoneySheet and group PayMoneySheet', () => {
  it('request 30 USDC over 3 members -> 10/10/10, fix one to 15 -> 7.5/7.5, sends one bill with asks', async () => {
    const onClose = vi.fn();
    render(<GroupMoneySheet chatId="g1" onClose={onClose} />);

    fireEvent.click(door('group-money-request'));
    const totalInput = screen.getByTestId('group-request-total') as HTMLInputElement;
    fireEvent.change(totalInput, { target: { value: '30' } });

    const inB = screen.getByTestId(`group-request-amount-${B}`) as HTMLInputElement;
    const inC = screen.getByTestId(`group-request-amount-${C}`) as HTMLInputElement;
    const inD = screen.getByTestId(`group-request-amount-${D}`) as HTMLInputElement;

    expect(inB.value).toBe('10');
    expect(inC.value).toBe('10');
    expect(inD.value).toBe('10');

    fireEvent.change(inB, { target: { value: '15' } });
    expect(inB.value).toBe('15');
    expect(inC.value).toBe('7.5');
    expect(inD.value).toBe('7.5');

    fireEvent.change(screen.getByTestId('group-request-note'), { target: { value: 'Піца' } });

    const submit = screen.getByRole('button', { name: /30(,00)?\sUSDC/ });
    fireEvent.click(submit);

    await waitFor(() => expect(sendBillMock).toHaveBeenCalledTimes(1));
    expect(sendBillMock.mock.calls[0]).toHaveLength(1);
    const [billArg] = sendBillMock.mock.calls[0];
    expect(billArg.title).toBe('Піца');
    expect(billArg.token).toBe('USDC');
    expect(billArg.payee).toEqual({ kind: 'peer', id: ME });
    expect(billArg.asks).toEqual([
      { id: B, amount: '15' },
      { id: C, amount: '7.5' },
      { id: D, amount: '7.5' },
    ]);
    expect(onClose).toHaveBeenCalled();
  });

  it('a request is not sent into a chat that is no longer open', async () => {
    const onClose = vi.fn();
    render(<GroupMoneySheet chatId="g1" onClose={onClose} />);
    fireEvent.click(door('group-money-request'));
    fireEvent.change(screen.getByTestId('group-request-total'), { target: { value: '30' } });
    act(() => {
      useMessengerStore.setState({ activeChatId: 'g2' });
    });
    fireEvent.click(screen.getByRole('button', { name: /30(,00)?\sUSDC/ }));
    await act(async () => {
      await Promise.resolve();
    });
    expect(sendBillMock).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByTestId('group-money-sheet').textContent).toMatch(/Не вдалося|Didnʼt go through|Nepodařilo se/);
  });

  it('pay defaults to lastAsker with amount and ref, Next calls askSolana pay with chat', async () => {
    const bill = requestBill({
      meId: C,
      people: [
        { id: ME, name: 'Я' },
        { id: B, name: 'Богдан' },
        { id: C, name: 'Василь' },
      ],
      amounts: new Map([[ME, 12_500_000n]]),
      token: 'USDC',
      note: 'Квитки',
    });
    const reqMsg = {
      id: 'm-req',
      senderId: C,
      senderName: 'Василь',
      senderAvatar: '',
      timestamp: '14:00',
      type: 'widget:bill',
      billData: bill,
    } as unknown as Message;

    setChat(MEMBERS, [reqMsg]);

    vi.spyOn(solanaApi, 'peer').mockResolvedValue({
      address: 'WalletAddr111111111111111111111111111111111',
      can_pay: true,
      cluster: 'devnet',
      seeker: false,
    });

    let capturedAsk: ReturnType<typeof useSolanaAsk> = null;
    const Probe = () => {
      capturedAsk = useSolanaAsk();
      return null;
    };

    render(
      <>
        <GroupMoneySheet chatId="g1" onClose={() => {}} />
        <Probe />
      </>,
    );

    fireEvent.click(door('group-money-pay'));
    const radioC = screen.getByTestId(`group-pay-member-${C}`);
    expect(radioC.getAttribute('aria-checked')).toBe('true');

    fireEvent.change(screen.getByTestId('group-pay-note'), { target: { value: 'За квитки' } });
    fireEvent.click(screen.getByRole('button', { name: /Далі|Next|Další/ }));

    await waitFor(() => expect(capturedAsk?.kind).toBe('pay'));
    expect(capturedAsk).toEqual({
      kind: 'pay',
      peer: { id: C, name: 'Василь' },
      cluster: 'devnet',
      chat: {
        id: 'g1',
        ref: bill.id,
        amount: '12.5',
        token: 'USDC',
        note: 'За квитки',
      },
    });
  });

  it('can_pay false shows cantPay line', async () => {
    vi.spyOn(solanaApi, 'peer').mockResolvedValue({
      address: null,
      can_pay: false,
      cluster: 'devnet',
      seeker: false,
    });

    render(<GroupMoneySheet chatId="g1" onClose={() => {}} />);
    fireEvent.click(door('group-money-pay'));
    fireEvent.click(screen.getByTestId(`group-pay-member-${B}`));
    fireEvent.click(screen.getByRole('button', { name: /Далі|Next|Další/ }));

    await waitFor(() => {
      expect(screen.getByTestId('group-money-sheet').textContent).toMatch(/Не можна переказати|Canʼt pay|Nelze zaplatit/);
    });
  });

  it('no members shows noMembers line and no footer', () => {
    setChat([{ id: ME, name: 'Я', role: 'owner', isMe: true }]);
    render(<GroupMoneySheet chatId="g1" onClose={() => {}} />);
    expect(screen.getByTestId('group-money-sheet').textContent).toMatch(/Немає учасників|No members|Žádní členové/);
  });

  it('SolanaSheets renders GroupMoneySheet for kind group', () => {
    act(() => askSolana({ kind: 'group', chatId: 'g1' }));
    render(<SolanaSheets />);
    expect(screen.getByTestId('group-money-sheet')).toBeTruthy();
  });

  it('PayMoneySheet with chat posts chat_id + ref, holds note in pendingPayNotes, and does NOT call sendMessage after opened', async () => {
    const paySpy = vi.spyOn(solanaApi, 'pay').mockResolvedValue({ opened: true });
    const setActiveSpy = vi.fn();
    const sendMsgSpy = vi.fn();
    useMessengerStore.setState({
      setActiveChat: setActiveSpy as never,
      sendMessage: sendMsgSpy as never,
    });

    const onClose = vi.fn();
    render(
      <PayMoneySheet
        peer={{ id: C, name: 'Василь' }}
        cluster="devnet"
        chat={{ id: 'g1', ref: 'rq-abcdefgh1234', amount: '12.5', note: 'Повертаю' }}
        onClose={onClose}
      />,
    );

    const input = screen.getByTestId('solana-amount-input') as HTMLInputElement;
    expect(input.value).toBe('12.5');

    fireEvent.click(screen.getByRole('button', { name: /12,5 USDC/ }));
    await waitFor(() => expect(paySpy).toHaveBeenCalledTimes(1));
    expect(paySpy).toHaveBeenCalledWith({
      peer_id: C,
      token: 'USDC',
      amount: '12.5',
      chat_id: 'g1',
      ref: 'rq-abcdefgh1234',
    });
    expect(sendMsgSpy).not.toHaveBeenCalled();
    expect(setActiveSpy).not.toHaveBeenCalled();
    expect(pendingPayNotes()).toEqual([
      expect.objectContaining({
        chatId: 'g1',
        ref: 'rq-abcdefgh1234',
        note: 'Повертаю',
      }),
    ]);
    expect(onClose).toHaveBeenCalled();
  });

  it('PayMoneySheet opens on the coin the request was made in', async () => {
    const paySpy = vi.spyOn(solanaApi, 'pay').mockResolvedValue({ opened: true });
    render(
      <PayMoneySheet
        peer={{ id: C, name: 'Василь' }}
        cluster="devnet"
        chat={{ id: 'g1', ref: 'rq-solrequest01', amount: '0.3', token: 'SOL' }}
        onClose={() => {}}
      />,
    );
    expect(screen.getByTestId('solana-coin-SOL').getAttribute('aria-pressed')).toBe('true');
    fireEvent.click(screen.getByRole('button', { name: /0,3 SOL/ }));
    await waitFor(() =>
      expect(paySpy).toHaveBeenCalledWith({ peer_id: C, token: 'SOL', amount: '0.3', chat_id: 'g1', ref: 'rq-solrequest01' }),
    );
  });

  it('SolanaSheets PayNoteWatcher posts note once when confirmed payment from me lands', async () => {
    const sendMsgSpy = vi.fn();
    useMessengerStore.setState({ sendMessage: sendMsgSpy as never });
    holdPayNote({ chatId: 'g1', ref: 'rq-watch123456', note: 'Повертаю', at: Date.now() });

    render(<SolanaSheets />);
    expect(sendMsgSpy).not.toHaveBeenCalled();

    const confirmedMsg = {
      id: 'm-pay-1',
      senderId: ME,
      senderName: 'Я',
      senderAvatar: '',
      timestamp: '14:05',
      type: 'payment',
      payment: {
        signature: 'sig-xyz',
        amount: '12.5',
        token: 'USDC',
        to: 'addr-1',
        cluster: 'devnet',
        status: 'confirmed',
        ref: 'rq-watch123456',
      },
    } as unknown as Message;

    act(() => {
      setChat(MEMBERS, [confirmedMsg]);
    });

    await waitFor(() => expect(sendMsgSpy).toHaveBeenCalledTimes(1));
    expect(sendMsgSpy).toHaveBeenCalledWith('Повертаю');
    expect(pendingPayNotes()).toEqual([]);

    act(() => {
      setChat(MEMBERS, [
        confirmedMsg,
        {
          id: 'm-extra',
          senderId: B,
          senderName: 'Богдан',
          senderAvatar: '',
          timestamp: '14:06',
          type: 'text',
          content: 'Ок',
        } as unknown as Message,
      ]);
    });

    expect(sendMsgSpy).toHaveBeenCalledTimes(1);
  });

  it('a note that cannot be removed from storage is still posted once', async () => {
    const sendMsgSpy = vi.fn();
    useMessengerStore.setState({ sendMessage: sendMsgSpy as never });
    holdPayNote({ chatId: 'g1', ref: 'rq-stuck000001', note: 'Дякую', at: Date.now() });
    vi.spyOn(localStorage, 'setItem').mockImplementation(() => {
      throw new Error('quota');
    });
    render(<SolanaSheets />);

    const paid = {
      id: 'm-pay-2',
      senderId: ME,
      senderName: 'Я',
      senderAvatar: '',
      timestamp: '14:05',
      type: 'payment',
      payment: { signature: 'sig-stuck', amount: '5', token: 'USDC', to: 'addr-1', cluster: 'devnet', status: 'confirmed', ref: 'rq-stuck000001' },
    } as unknown as Message;
    const other = { id: 'm-other', senderId: B, senderName: 'Богдан', senderAvatar: '', timestamp: '14:06', type: 'text', content: 'Ок' } as unknown as Message;

    act(() => {
      setChat(MEMBERS, [paid]);
    });
    await waitFor(() => expect(sendMsgSpy).toHaveBeenCalledTimes(1));
    act(() => {
      setChat(MEMBERS, [paid, other]);
    });
    act(() => {
      setChat(MEMBERS, [paid, other, { ...other, id: 'm-other-2' }]);
    });
    expect(sendMsgSpy).toHaveBeenCalledTimes(1);
  });

  it('a failed payment drops the held note without posting it', async () => {
    const sendMsgSpy = vi.fn();
    useMessengerStore.setState({ sendMessage: sendMsgSpy as never });
    holdPayNote({ chatId: 'g1', ref: 'rq-failed00001', note: 'Дякую', at: Date.now() });
    render(<SolanaSheets />);

    act(() => {
      setChat(MEMBERS, [
        {
          id: 'm-pay-3',
          senderId: ME,
          senderName: 'Я',
          senderAvatar: '',
          timestamp: '14:05',
          type: 'payment',
          payment: { signature: 'sig-failed', amount: '5', token: 'USDC', to: 'addr-1', cluster: 'devnet', status: 'failed', ref: 'rq-failed00001' },
        } as unknown as Message,
      ]);
    });
    await waitFor(() => expect(pendingPayNotes()).toEqual([]));
    expect(sendMsgSpy).not.toHaveBeenCalled();
  });

  it('plain pay without asker passes a gp- ref', async () => {
    vi.spyOn(solanaApi, 'peer').mockResolvedValue({
      address: 'WalletAddr111111111111111111111111111111111',
      can_pay: true,
      cluster: 'devnet',
      seeker: false,
    });

    let capturedAsk: ReturnType<typeof useSolanaAsk> = null;
    const Probe = () => {
      capturedAsk = useSolanaAsk();
      return null;
    };

    render(
      <>
        <GroupMoneySheet chatId="g1" onClose={() => {}} />
        <Probe />
      </>
    );

    fireEvent.click(door('group-money-pay'));
    fireEvent.click(screen.getByTestId(`group-pay-member-${B}`));
    fireEvent.change(screen.getByTestId('group-pay-note'), { target: { value: 'Просто так' } });
    fireEvent.click(screen.getByRole('button', { name: /Далі|Next|Další/ }));

    await waitFor(() => expect(capturedAsk?.kind).toBe('pay'));
    expect(capturedAsk).toEqual({
      kind: 'pay',
      peer: { id: B, name: 'Богдан' },
      cluster: 'devnet',
      chat: {
        id: 'g1',
        ref: expect.stringMatching(/^gp-[a-z0-9]{12}$/),
        note: 'Просто так',
      },
    });
  });
});

void React;
