/**
 * Рахунок у стрічці (W2): «оплачено» — лише з переказу, який підтвердила
 * мережа і який посилається на цей рахунок (`payment.ref == bill.id`).
 * Старий перемикач «Оплачено» (`payBillShare`) з дотику прибрано.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { BillCard } from '../components/messenger/meetup/BillCard';
import { MessageEmbeds } from '../components/messenger/feed/MessageEmbeds';
import { FeedActionsContext, type FeedActions } from '../components/messenger/feed/feedActions';
import { buildFeedRows } from '../components/messenger/feed/feedModel';
import { readPayment } from '../services/solanaFields';
import { useMessengerStore } from '../stores/messengerStore';
import type { Message } from '../types/messenger';
import { BILL, ME, OLENA, PETRO, billMsg, installBridge, payMsg, removeBridge, type Call } from './meetupFixtures';

let prev: ReturnType<typeof useMessengerStore.getState>;
let calls: Call[];

const flush = () =>
  act(async () => {
    for (let i = 0; i < 6; i++) await Promise.resolve();
  });

const MARKS = { 'dish:latte': [OLENA], 'dish:cake': [OLENA, PETRO] };

function seed(messages: Message[], me = ME) {
  useMessengerStore.setState({
    chats: [{ id: 'grp', type: 'group', title: 'Cafe X · 19:00', messages, unreadCount: 0 } as never],
    activeChatId: 'grp',
    currentUser: { ...useMessengerStore.getState().currentUser, id: me, name: me },
  });
}

const openBill = () => {
  const card = within(screen.getByTestId('card-bill'));
  fireEvent.click(card.queryByTestId('bill-card-pay') ?? card.getByText('Відкрити'));
};

const Live: React.FC<{ me: string }> = ({ me }) => {
  const m = useMessengerStore((s) => s.chats[0]?.messages.find((x) => x.id === 'row-bill'));
  return m ? <BillCard msg={m} meId={me} /> : null;
};

beforeEach(() => {
  prev = useMessengerStore.getState();
  calls = installBridge((c) => (c.p === '/messenger/solana/pay' ? { data: { opened: true } } : undefined));
});

afterEach(() => {
  cleanup();
  removeBridge();
  useMessengerStore.setState(prev, true);
  localStorage.clear();
});

describe('стан часток — лише з мережі', () => {
  it('підтверджений переказ із ref — оплачено; «чекає мережу» — не оплачено', () => {
    seed([billMsg({ cardMarks: MARKS }), payMsg(OLENA, '5.5'), payMsg(PETRO, '2', { status: 'pending' })]);
    render(<Live me={ME} />);
    const card = screen.getByTestId('card-bill');
    expect(within(card).getByTestId('card-status').textContent).toBe('1 з 2 оплатили · 13,50\u00A0USDC');
    expect(screen.getByTestId(`bill-person-${OLENA}`).textContent).toContain('5,50');
    expect(screen.getByTestId(`bill-person-${OLENA}`).textContent).toContain('оплачено');
    expect(screen.getByTestId(`bill-person-${OLENA}`).textContent).not.toContain('не оплачено');
    expect(screen.getByTestId(`bill-person-${PETRO}`).textContent).toContain('чекає мережу');
    expect(screen.getByTestId(`bill-person-${ME}`).textContent).toContain('отримує');
  });

  it('переказ без ref (v1) частку не оплачує, хоч і підтверджений', () => {
    seed([billMsg({ cardMarks: MARKS }), payMsg(OLENA, '5.5', { ref: null })]);
    render(<Live me={ME} />);
    expect(screen.getByTestId('card-status').textContent).toBe('0 з 2 оплатили · 13,50\u00A0USDC');
    expect(screen.getByTestId(`bill-person-${OLENA}`).textContent).toContain('не оплачено');
  });

  it('сума менша за частку — «бракує», а не «оплачено»', () => {
    seed([billMsg({ cardMarks: MARKS }), payMsg(OLENA, '5')]);
    render(<Live me={ME} />);
    expect(screen.getByTestId(`bill-person-${OLENA}`).textContent).toContain('бракує 0,50');
  });

  it('ref переказу доїжджає з рядка мосту (PaymentFrame v2)', () => {
    const base = {
      amount: '5.5',
      token: 'USDC',
      cluster: 'devnet',
      to: 'BxsSMk2r7BPxLChLdAE7Jv7UuDbZbaBRis31PgWTDfJH',
      signature: '4WVSq3Jz9yGm1kXcR7pLtH2aNbE5uD8fW6vQhKjZsY3oTi1MnBc9xAeP4rUgmZHT',
      status: 'confirmed',
    };
    expect(readPayment({ ...base, ref: 'bill-1' })?.ref).toBe('bill-1');
    expect(readPayment(base)?.ref).toBeNull();
    expect(readPayment({ ...base, ref: 'x'.repeat(37) })?.ref).toBeNull();
  });
});

describe('аркуш рахунку', () => {
  it('нічиї страви — окремо; галочка своєї страви — позначка dish:<id>', async () => {
    seed([billMsg({ cardMarks: MARKS })], PETRO);
    render(<Live me={PETRO} />);
    openBill();
    const sheet = screen.getByTestId('bill-sheet');
    expect(within(sheet).getByTestId('bill-unclaimed').textContent).toContain('Суп');
    expect(within(sheet).getByTestId('bill-unclaimed').textContent).toContain('6,00\u00A0USDC');
    fireEvent.click(within(sheet).getByTestId('bill-dish-soup'));
    await flush();
    expect(calls.filter((c) => c.p === '/messenger/messages/row-bill/marks').map((c) => c.b)).toEqual([
      { value: 'dish:soup', on: true },
    ]);
  });

  it('«Оплатити мою частку» просить телефон переказати організатору з chat_id і ref', async () => {
    seed([billMsg({ cardMarks: MARKS })], PETRO);
    render(<Live me={PETRO} />);
    openBill();
    const pay = screen.getByTestId('bill-pay');
    expect(pay.textContent).toContain('Оплатити мою частку 2,00\u00A0USDC');
    fireEvent.click(pay);
    await flush();
    const ask = calls.find((c) => c.p === '/messenger/solana/pay');
    expect(ask?.m).toBe('POST');
    expect(ask?.b).toEqual({ peer_id: ME, token: 'USDC', amount: '2', chat_id: 'grp', ref: 'bill-1' });
  });

  it('T49: гаманець відкрився — аркуш закрито, з гаманця людина вертається в розмову без відкритого поля оплати', async () => {
    seed([billMsg({ cardMarks: MARKS })], PETRO);
    render(<Live me={PETRO} />);
    openBill();
    fireEvent.click(screen.getByTestId('bill-pay'));
    await flush();
    expect(calls.filter((c) => c.p === '/messenger/solana/pay')).toHaveLength(1);
    expect(screen.queryByTestId('bill-sheet')).toBeNull();
    expect(screen.queryByTestId('bill-pay')).toBeNull();
    expect(screen.getByTestId('card-bill')).toBeTruthy();
  });

  it('T49: телефон не відкрив гаманця — аркуш лишається й каже про це', async () => {
    removeBridge();
    calls = installBridge((c) => (c.p === '/messenger/solana/pay' ? { data: { opened: false } } : undefined));
    seed([billMsg({ cardMarks: MARKS })], PETRO);
    render(<Live me={PETRO} />);
    openBill();
    fireEvent.click(screen.getByTestId('bill-pay'));
    await flush();
    expect(screen.getByTestId('bill-sheet')).toBeTruthy();
    expect(screen.getByTestId('bill-pay-note').textContent).toContain('відбитком');
  });

  it('рахунок закладу — переказ на адресу закладу, не людині', async () => {
    const venueBill = { ...BILL, payee: { kind: 'venue' as const, venueId: 'cafe-x', address: 'VenueWa11et1111111111111111111111111111111' } };
    seed([billMsg({ billData: venueBill, cardMarks: MARKS })], PETRO);
    render(<Live me={PETRO} />);
    openBill();
    fireEvent.click(screen.getByTestId('bill-pay'));
    await flush();
    expect(calls.find((c) => c.p === '/messenger/solana/pay')?.b).toEqual({
      to: venueBill.payee.address,
      token: 'USDC',
      amount: '2',
      chat_id: 'grp',
      ref: 'bill-1',
    });
  });

  it('без мосту (ПК) — оплати немає, і сказано чому', () => {
    removeBridge();
    seed([billMsg({ cardMarks: MARKS })], PETRO);
    render(<Live me={PETRO} />);
    openBill();
    expect((screen.getByTestId('bill-pay') as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByTestId('bill-pay-note').textContent).toContain('на телефоні');
  });

  it('моя частка оплачена мережею — одна заявка на бали з підписом, і лише раз', async () => {
    const paid = payMsg(PETRO, '2');
    seed([billMsg({ cardMarks: MARKS }), paid], PETRO);
    render(<Live me={PETRO} />);
    await flush();
    cleanup();
    render(<Live me={PETRO} />);
    await flush();
    const claims = calls.filter((c) => c.p === '/messenger/points/payment');
    expect(claims).toHaveLength(1);
    expect(claims[0].b).toEqual({ signature: paid.payment!.signature, ref: 'bill-1' });
  });
});

describe('доплата і чужі очі', () => {
  it('позначив страву після оплати — кнопка просить лише різницю, і дві оплати складаються', async () => {
    // Петро заплатив 2.00 за торт навпіл, потім позначив ще й суп (6.00): частка 8.00.
    const first = payMsg(PETRO, '2');
    const later = { ...MARKS, 'dish:soup': [PETRO] };
    seed([billMsg({ cardMarks: later }), first], PETRO);
    render(<Live me={PETRO} />);
    openBill();
    expect(screen.getByTestId(`bill-share-${PETRO}`).textContent).toContain('бракує 6,00\u00A0USDC');
    const pay = screen.getByTestId('bill-pay');
    expect(pay.textContent).toContain('6,00\u00A0USDC');
    expect(pay.textContent).not.toContain('8,00');
    fireEvent.click(pay);
    await flush();
    expect(calls.find((c) => c.p === '/messenger/solana/pay')?.b).toEqual({
      peer_id: ME,
      token: 'USDC',
      amount: '6',
      chat_id: 'grp',
      ref: 'bill-1',
    });
    act(() => seed([billMsg({ cardMarks: later }), first, payMsg(PETRO, '6')], PETRO));
    openBill();
    expect(screen.getByTestId(`bill-share-${PETRO}`).textContent).toContain('оплачено');
    expect(screen.queryByTestId('bill-pay')).toBeNull();
  });

  it('третій у групі не бачить «оплачено» за переказ людині — лише «не звірено»', () => {
    seed([billMsg({ cardMarks: MARKS }), payMsg(OLENA, '5.5', { toMine: null })], PETRO);
    render(<Live me={PETRO} />);
    const row = screen.getByTestId(`bill-person-${OLENA}`).textContent ?? '';
    expect(row).toContain('не звірено');
    expect(row).not.toContain('оплачено');
    expect(screen.getByTestId('card-status').textContent).toBe('0 з 2 оплатили · 13,50\u00A0USDC');
  });
});

describe('заявка на бали не губиться', () => {
  it('відмова (нема шляху, офлайн) — не записано як надіслане; згодом пробує ще; успіх — більше не питає', async () => {
    let answerPoints: 'fail' | 'ok' = 'fail';
    removeBridge();
    calls = installBridge((c) =>
      c.p === '/messenger/points/payment'
        ? answerPoints === 'fail'
          ? { status: 404, code: 'not_found', detail: 'немає такого шляху: POST /messenger/points/payment' }
          : { data: { status: 'confirmed', awarded: [] } }
        : undefined,
    );
    vi.useFakeTimers({ toFake: ['Date'] });
    try {
      const paid = payMsg(PETRO, '2');
      seed([billMsg({ cardMarks: MARKS }), paid], PETRO);
      render(<Live me={PETRO} />);
      await flush();
      expect(calls.filter((c) => c.p === '/messenger/points/payment')).toHaveLength(1);
      expect(localStorage.getItem('phantom_points_payment_claims') ?? '').not.toContain(paid.payment!.signature);

      vi.setSystemTime(Date.now() + 10 * 60_000);
      answerPoints = 'ok';
      cleanup();
      render(<Live me={PETRO} />);
      await flush();
      expect(calls.filter((c) => c.p === '/messenger/points/payment')).toHaveLength(2);
      expect(localStorage.getItem('phantom_points_payment_claims') ?? '').toContain(paid.payment!.signature);

      vi.setSystemTime(Date.now() + 10 * 60_000);
      cleanup();
      render(<Live me={PETRO} />);
      await flush();
      expect(calls.filter((c) => c.p === '/messenger/points/payment')).toHaveLength(2);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('стара картка split-bill більше не бреше «Оплачено»', () => {
  it('дотик по учаснику нічого не перемикає', () => {
    const payBillShare = vi.fn();
    const legacy: Message = {
      id: 'old',
      senderId: OLENA,
      senderName: 'Олена',
      senderAvatar: '',
      timestamp: '20:00',
      type: 'split-bill',
      splitBillData: {
        title: 'Вечеря',
        totalAmount: 30,
        currency: '₴',
        participants: [{ id: 'p1', name: 'Петро', avatar: '', share: 15, paid: false }],
      },
    } as unknown as Message;
    const rows = buildFeedRows([legacy], ME);
    render(
      <FeedActionsContext.Provider value={{ payBillShare } as unknown as FeedActions}>
        <MessageEmbeds row={rows[0]} currentUserId={ME} currentUserName="Я" isActiveSearchMatch={false} copied={false} />
      </FeedActionsContext.Provider>,
    );
    fireEvent.click(screen.getByText('Петро'));
    expect(payBillShare).not.toHaveBeenCalled();
    expect(screen.queryByText('Оплачено')).toBeNull();
    expect(screen.getByTestId('legacy-bill-note').textContent).toContain('не перевірено мережею');
  });
});

describe('досяжність: стрічка малює картку рахунку', () => {
  it('рядок widget:bill — картка, а не порожня бульбашка', () => {
    seed([billMsg({ cardMarks: MARKS }), payMsg(OLENA, '5.5')]);
    const rows = buildFeedRows(useMessengerStore.getState().chats[0].messages, ME);
    expect(rows[0].cardView).toBe(true);
    render(
      <FeedActionsContext.Provider value={{} as FeedActions}>
        <MessageEmbeds row={rows[0]} currentUserId={ME} currentUserName="Я" isActiveSearchMatch={false} copied={false} />
      </FeedActionsContext.Provider>,
    );
    expect(screen.getByTestId('card-status').textContent).toBe('1 з 2 оплатили · 13,50\u00A0USDC');
  });
});

describe('новий рахунок без позначок не каже «ніхто нічого не винен»', () => {
  it('картка: «Ще ніхто не позначив страви», і в людей — не «частка 0» і не «0.00»', () => {
    seed([billMsg()], PETRO);
    render(<Live me={PETRO} />);
    expect(screen.getByTestId('card-status').textContent).toBe('Ще ніхто не позначив страви · 13,50\u00A0USDC');
    for (const id of [OLENA, PETRO]) {
      const row = screen.getByTestId(`bill-person-${id}`).textContent ?? '';
      expect(row).toContain('страв ще не позначено');
      expect(row).not.toContain('частка 0');
      expect(row).not.toContain('0,00');
    }
    expect(screen.getByTestId(`bill-person-${ME}`).textContent).toContain('отримує');
  });

  it('аркуш: той самий рядок стану і ті самі слова в частках', () => {
    seed([billMsg()], PETRO);
    render(<Live me={PETRO} />);
    openBill();
    const sheet = screen.getByTestId('bill-sheet');
    expect(sheet.textContent).toContain('Ще ніхто не позначив страви · 13,50\u00A0USDC');
    expect(sheet.textContent).not.toContain('0 з 0 оплатили');
    expect(within(sheet).getByTestId(`bill-share-${OLENA}`).textContent).toContain('страв ще не позначено');
    expect(within(sheet).getByTestId(`bill-share-${OLENA}`).textContent).not.toContain('0,00');
  });

  it('позначив лише отримувач, а інші страви нічиї — «інші ще не позначили», а не «0 з 0»', () => {
    seed([billMsg({ cardMarks: { 'dish:latte': [ME] } })], PETRO);
    render(<Live me={PETRO} />);
    expect(screen.getByTestId('card-status').textContent).toBe('Інші ще не позначили страви · 13,50\u00A0USDC');
    expect(screen.getByTestId(`bill-person-${PETRO}`).textContent).toContain('страв ще не позначено');
  });

  it('усі страви розібрано, а в когось нічого — тоді вже «частка 0»', () => {
    seed([billMsg({ cardMarks: { 'dish:latte': [OLENA], 'dish:cake': [OLENA], 'dish:soup': [OLENA] } })], PETRO);
    render(<Live me={PETRO} />);
    expect(screen.getByTestId('card-status').textContent).toBe('0 з 1 оплатили · 13,50\u00A0USDC');
    expect(screen.getByTestId(`bill-person-${PETRO}`).textContent).toContain('частка 0');
  });
});

describe('файл у рахунку', () => {
  it('показує тиху стрічку з іменем і 8 символами відбитка', () => {
    seed([billMsg({ billData: { ...BILL, file: { sha256: '1a2b3c4d' + 'e'.repeat(56), name: 'scan.pdf' } } })]);
    render(<Live me={ME} />);
    expect(screen.getByTestId('bill-file').textContent).toBe('Файл: scan.pdf · 1a2b3c4d');
  });

  it('без файла стрічки нема', () => {
    seed([billMsg()]);
    render(<Live me={ME} />);
    expect(screen.queryByTestId('bill-file')).toBeNull();
  });
});
