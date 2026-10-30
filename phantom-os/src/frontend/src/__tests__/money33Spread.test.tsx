import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { BillCard } from '../components/messenger/meetup/BillCard';
import { billSplitOf, splitBill } from '../services/meetup/split';
import { chatLedger } from '../services/money/ledger';
import { useMessengerStore } from '../stores/messengerStore';
import type { CardMarks } from '../types/meetup';
import type { Message } from '../types/messenger';
import { BILL, ME, OLENA, PETRO, billMsg, installBridge, removeBridge, type Answer, type Call } from './meetupFixtures';

let prev: ReturnType<typeof useMessengerStore.getState>;
let calls: Call[] = [];

const TABLE: CardMarks = { 'dish:latte': [OLENA], 'here:1': [PETRO] };
const SPREAD: CardMarks = { ...TABLE, 'spread:1': [ME] };

function seed(msg: Message, me: string) {
  useMessengerStore.setState({
    chats: [{ id: 'grp', type: 'group', title: 'Cafe X · 19:00', messages: [msg], unreadCount: 0 } as never],
    activeChatId: 'grp',
    currentUser: { ...useMessengerStore.getState().currentUser, id: me, name: me },
  });
}

const Live = ({ me }: { me: string }) => {
  const msg = useMessengerStore((s) => s.chats[0].messages[0]);
  return <BillCard msg={msg} meId={me} />;
};

const dueOf = (id: string) => screen.queryByTestId(`bill-person-${id}`)?.querySelector('[data-bill-due]')?.textContent ?? null;

const flush = () =>
  act(async () => {
    for (let i = 0; i < 4; i++) await Promise.resolve();
  });

function bridge(answer: (c: Call) => Answer | undefined = () => undefined) {
  calls = installBridge(answer);
}

beforeEach(() => {
  prev = useMessengerStore.getState();
  bridge();
});

afterEach(() => {
  cleanup();
  removeBridge();
  useMessengerStore.setState(prev, true);
  localStorage.clear();
});

describe('money-33: нічиє ділить отримувач позначкою на картці, однаково для всіх', () => {
  it('отримувач ставить spread:1 на картку, і частки міняються; повторний дотик знімає', async () => {
    seed(billMsg({ cardMarks: TABLE }), ME);
    render(<Live me={ME} />);
    const petroBefore = dueOf(PETRO);

    fireEvent.click(screen.getByTestId('bill-spread'));
    await flush();

    expect(calls.filter((c) => c.p.endsWith('/marks')).map((c) => c.b)).toEqual([{ value: 'spread:1', on: true }]);
    expect(useMessengerStore.getState().chats[0].messages[0].cardMarks?.['spread:1']).toEqual([ME]);
    expect(document.querySelector('[data-unclaimed-line]')).toBeNull();
    expect(dueOf(PETRO)).toContain('5');
    expect(dueOf(OLENA)).toContain('8');

    fireEvent.click(screen.getByTestId('bill-unspread'));
    await flush();
    expect(calls.filter((c) => c.p.endsWith('/marks')).at(-1)?.b).toEqual({ value: 'spread:1', on: false });
    expect(document.querySelector('[data-unclaimed-line]')).not.toBeNull();
    expect(dueOf(PETRO)).toBe(petroBefore);
  });

  it('інший учасник бачить ті самі розкладені частки, що й отримувач, і платить саме їх', () => {
    seed(billMsg({ cardMarks: SPREAD }), ME);
    const { unmount } = render(<Live me={ME} />);
    const payeeView = { petro: dueOf(PETRO), olena: dueOf(OLENA) };
    unmount();

    seed(billMsg({ cardMarks: SPREAD }), PETRO);
    render(<Live me={PETRO} />);
    expect(dueOf(PETRO)).toBe(payeeView.petro);
    expect(dueOf(OLENA)).toBe(payeeView.olena);
    expect(document.querySelector('[data-unclaimed-line]')).toBeNull();
    expect(screen.queryByTestId('bill-spread')).toBeNull();
    expect(screen.queryByTestId('bill-unspread')).toBeNull();
  });

  it('spread:1 не від отримувача нічого не міняє', () => {
    const marks: CardMarks = { ...TABLE, 'spread:1': [PETRO] };
    expect(billSplitOf(BILL, marks)).toEqual(splitBill(BILL, marks));
    seed(billMsg({ cardMarks: marks }), PETRO);
    render(<Live me={PETRO} />);
    expect(document.querySelector('[data-unclaimed-line]')).not.toBeNull();
  });

  it('борги розмови рахують ті самі частки, що й картка', () => {
    const msg = billMsg({ cardMarks: SPREAD });
    const split = billSplitOf(BILL, SPREAD);
    const petro = split.shares.find((s) => s.id === PETRO)?.units ?? 0n;
    expect(petro).toBeGreaterThan(splitBill(BILL, TABLE).shares.find((s) => s.id === PETRO)?.units ?? 0n);
    const owed = chatLedger([msg], PETRO).transfers.find((tr) => tr.from === PETRO && tr.to === ME);
    expect(owed?.units).toBe(petro);
  });

  it('міст відмовив — речення під карткою, частки й позначки ті самі', async () => {
    bridge((c) => (c.p.endsWith('/marks') ? { status: 409, code: 'not_member' } : undefined));
    seed(billMsg({ cardMarks: TABLE }), ME);
    render(<Live me={ME} />);
    const petroBefore = dueOf(PETRO);
    fireEvent.click(screen.getByTestId('bill-spread'));
    await flush();
    expect(screen.getByTestId('bill-spread-error').textContent).not.toBe('');
    expect(dueOf(PETRO)).toBe(petroBefore);
    expect(useMessengerStore.getState().chats[0].messages[0].cardMarks).toEqual(TABLE);
  });

  it('за столом нікого — кнопки немає', () => {
    seed(billMsg({ cardMarks: {} }), ME);
    render(<Live me={ME} />);
    expect(screen.queryByTestId('bill-spread')).toBeNull();
  });
});
