/**
 * Аркуш плану (макет 04): з картки «План» — хто на місці, хто в дорозі, хто
 * запізнюється (і «Покликати»), що хто принесе, що після, «Лишити».
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { MeetupCard } from '../components/messenger/meetup/MeetupCard';
import { useMessengerStore } from '../stores/messengerStore';
import { etaValue } from '../services/meetup/marks';
import type { Message } from '../types/messenger';
import { AT, ME, OLENA, PETRO, installBridge, meetupMsg, removeBridge, type Call } from './meetupFixtures';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

let prev: ReturnType<typeof useMessengerStore.getState>;
let calls: Call[];
let sent: string[];

const flush = () =>
  act(async () => {
    for (let i = 0; i < 6; i++) await Promise.resolve();
  });

function seed(msg: Message) {
  useMessengerStore.setState({
    chats: [{ id: 'grp', type: 'group', title: 'Cafe X · 19:00', messages: [msg], unreadCount: 0 } as never],
    activeChatId: 'grp',
    currentUser: { ...useMessengerStore.getState().currentUser, id: ME, name: 'Я' },
    sendMessage: (text: string) => {
      sent.push(text);
    },
  } as never);
}

const Live: React.FC = () => {
  const m = useMessengerStore((s) => s.chats[0]?.messages[0]);
  return m ? <MeetupCard msg={m} meId={ME} /> : null;
};

const openPlan = () => {
  render(<Live />);
  fireEvent.click(screen.getByTestId('meetup-plan'));
  return screen.getByTestId('plan-sheet');
};

beforeEach(() => {
  prev = useMessengerStore.getState();
  sent = [];
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(AT + 10 * 60_000));
  calls = installBridge(() => undefined);
});

afterEach(() => {
  cleanup();
  removeBridge();
  vi.useRealTimers();
  useMessengerStore.setState(prev, true);
  localStorage.clear();
});

describe('аркуш плану', () => {
  it('з картки: на місці, в дорозі «за 4 хв», запізнюється з «Покликати»', () => {
    seed(meetupMsg({ cardMarks: { 'rsvp:yes': [OLENA, PETRO], 'here:1': [OLENA], [etaValue(AT + 14 * 60_000)]: [PETRO] } }));
    const sheet = openPlan();
    expect(within(sheet).getByTestId('plan-now').textContent).toContain('Зараз · 19:10');
    expect(within(sheet).getByTestId('plan-here').textContent).toContain('на місці: 1');
    expect(within(sheet).getByTestId(`plan-onway-${PETRO}`).textContent).toContain('за 4 хв');
    expect(within(sheet).queryByTestId(`plan-late-${PETRO}`)).toBeNull();
  });

  it('запізнюється — «Покликати» пише в цю розмову один раз', () => {
    seed(meetupMsg({ cardMarks: { 'rsvp:yes': [OLENA, PETRO], 'here:1': [OLENA] } }));
    const sheet = openPlan();
    expect(within(sheet).getByTestId(`plan-late-${PETRO}`).textContent).toContain('запізнюється на 10 хв');
    fireEvent.click(within(sheet).getByTestId(`plan-ping-${PETRO}`));
    fireEvent.click(within(sheet).getByTestId(`plan-ping-${PETRO}`));
    expect(sent).toEqual(['Петро, ти вже в дорозі?']);
    expect(within(sheet).getByTestId(`plan-ping-${PETRO}`).textContent).toBe('Покликано');
  });

  it('за дві години до зустрічі — блоку «Зараз» немає; порожнє «що принести» сказано', () => {
    vi.setSystemTime(new Date(AT - 3 * 3600_000));
    seed(meetupMsg({ cardMarks: { 'rsvp:yes': [OLENA] } }));
    const sheet = openPlan();
    expect(within(sheet).queryByTestId('plan-now')).toBeNull();
    expect(within(sheet).getByTestId('plan-bring').textContent).toContain('Поки ніхто нічого не бере.');
  });

  it('що принести: чуже з іменем, мій дотик бере річ, нова річ — позначка bring:', async () => {
    seed(meetupMsg({ cardMarks: { 'bring:Свічки': [OLENA] } }));
    const sheet = openPlan();
    const row = within(sheet).getByTestId('plan-bring-Свічки');
    expect(row.getAttribute('aria-checked')).toBe('false');
    expect(row.textContent).toContain('Олена');
    fireEvent.click(row);
    await flush();
    fireEvent.click(within(sheet).getByTestId('plan-bring-add'));
    fireEvent.change(within(sheet).getByTestId('plan-bring-input'), { target: { value: '  Торт  ' } });
    fireEvent.click(within(sheet).getByTestId('plan-bring-save'));
    await flush();
    expect(calls.filter((c) => c.p.endsWith('/marks')).map((c) => c.b)).toEqual([
      { value: 'bring:Свічки', on: true },
      { value: 'bring:Торт', on: true },
    ]);
  });

  it('після: «Розділити» відкриває рахунок; група плану — «Лишити» внизу', async () => {
    seed(meetupMsg({ cardMarks: { 'rsvp:yes': [OLENA] } }));
    const sheet = openPlan();
    expect(within(sheet).getByTestId('plan-expiry').textContent).toContain('Розмова лишиться, якщо хтось її збереже.');
    fireEvent.click(within(sheet).getByTestId('plan-keep'));
    await flush();
    expect(calls.filter((c) => c.p.endsWith('/marks')).map((c) => c.b)).toEqual([{ value: 'keep:1', on: true }]);
    fireEvent.click(within(sheet).getByTestId('plan-after-split'));
    expect(screen.queryByTestId('plan-sheet')).toBeNull();
  });
});
