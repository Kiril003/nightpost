import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { BillSheet } from '../components/messenger/meetup/BillSheet';
import { useMessengerStore } from '../stores/messengerStore';
import type { BillData, CardMarks } from '../types/meetup';
import type { Message } from '../types/messenger';
import { BILL, billMsg, installBridge, removeBridge, type Call } from './meetupFixtures';

const REPO = join(process.cwd(), '../../..');
const CARD_MARKS_KT = 'phantom-companion/app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerCardMarks.kt';

/** Закритий словник позначок, який телефон пропускає (`VALUES` у WebMessengerCardMarks.kt). */
function nativeValues(): RegExp[] {
  const kt = readFileSync(join(REPO, CARD_MARKS_KT), 'utf8');
  const block = kt.slice(kt.indexOf('private val VALUES = listOf('));
  const body = block.slice(0, block.indexOf('\n    )'));
  return [...body.matchAll(/Regex\("((?:[^"\\]|\\.)*)"\)/g)].map((m) => new RegExp(`^(?:${m[1].replace(/\\\\/g, '\\')})$`));
}

const ME = 'a1b2c3d4e5f60718';
const OLENA = '0f1e2d3c4b5a6978';
const PETRO = '9988776655443322';

const HEX_BILL: BillData = {
  ...BILL,
  payee: { kind: 'peer', id: ME },
  people: [
    { id: ME, name: 'Я' },
    { id: OLENA, name: 'Олена' },
    { id: PETRO, name: 'Петро' },
  ],
};
const MARKS: CardMarks = { 'dish:latte': [OLENA], 'dish:cake': [PETRO], 'dish:soup': [ME] };

let prev: ReturnType<typeof useMessengerStore.getState>;
let calls: Call[] = [];

function seed(msg: Message) {
  useMessengerStore.setState({
    chats: [{ id: 'grp', type: 'group', title: 'Cafe X · 19:00', messages: [msg], unreadCount: 0 } as never],
    activeChatId: 'grp',
    currentUser: { ...useMessengerStore.getState().currentUser, id: ME, name: 'Я' },
  });
}

const flush = () =>
  act(async () => {
    for (let i = 0; i < 4; i++) await Promise.resolve();
  });

beforeEach(() => {
  prev = useMessengerStore.getState();
  calls = installBridge(() => undefined);
});

afterEach(() => {
  cleanup();
  removeBridge();
  useMessengerStore.setState(prev, true);
});

describe('«Отримав готівку»: позначка, яку телефон справді пропустить', () => {
  it('отримувач тисне «отримав готівку» — міст отримує cash:<id людини>, і словник телефона його пропускає', async () => {
    const msg = billMsg({ billData: HEX_BILL, cardMarks: MARKS });
    seed(msg);
    render(<BillSheet open onClose={() => undefined} msg={msg} meId={ME} />);

    fireEvent.click(screen.getByTestId(`bill-cash-${OLENA}`));
    await flush();

    const sent = calls.filter((c) => c.p.endsWith('/marks')).map((c) => c.b);
    expect(sent).toEqual([{ value: `cash:${OLENA}`, on: true }]);
    expect(nativeValues().some((re) => re.test(sent[0].value))).toBe(true);
    expect(screen.queryByTestId('bill-error')).toBeNull();
  });

  it('словник телефона не пропускає cash: без id і з задовгим id', () => {
    const values = nativeValues();
    expect(values.length).toBeGreaterThan(5);
    for (const bad of ['cash:', `cash:${'a'.repeat(65)}`, 'cash:OLENA', `cash:${OLENA}\n`]) {
      expect(values.some((re) => re.test(bad)), bad).toBe(false);
    }
  });
});
