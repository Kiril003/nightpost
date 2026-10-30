import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useGlobalSearch } from '../components/messenger/GlobalSearch';
import { claimedItemNames, fragmentOfMessage, searchableFields } from '../services/messageSearch';
import { useMessengerStore } from '../stores/messengerStore';
import type { CardMarks } from '../types/meetup';
import type { Chat, Message } from '../types/messenger';
import { ME, OLENA, PETRO, billMsg } from './meetupFixtures';

const MARKS: CardMarks = { 'dish:latte': [ME, OLENA], 'dish:cake': [PETRO], 'dish:soup': [ME] };

const chatOf = (messages: Message[]): Chat => ({
  id: 'grp',
  title: 'Cafe X · 19:00',
  type: 'group',
  unreadCount: 0,
  circle: 'friends',
  messages,
}) as unknown as Chat;

let prev: ReturnType<typeof useMessengerStore.getState>;

beforeEach(() => {
  prev = useMessengerStore.getState();
});

afterEach(() => {
  useMessengerStore.setState(prev, true);
});

const settle = () =>
  act(async () => {
    await new Promise((r) => setTimeout(r, 30));
  });

describe('money-45: пошук рахунку за стравою, яку я позначив своєю', () => {
  it('страви з моїми позначками dish:, у порядку рахунку', () => {
    const msg = billMsg({ cardMarks: MARKS });
    expect(claimedItemNames(msg, ME)).toEqual(['Латте', 'Суп']);
    expect(claimedItemNames(msg, PETRO)).toEqual(['Торт']);
    expect(claimedItemNames(billMsg({ cardMarks: {} }), ME)).toEqual([]);
    expect(claimedItemNames({ ...msg, isDeleted: true }, ME)).toEqual([]);
  });

  it('без того, хто дивиться, страви в пошук не йдуть', () => {
    const msg = billMsg({ cardMarks: MARKS });
    expect(searchableFields(msg)).not.toContainEqual(['text', 'Суп']);
    expect(searchableFields(msg, ME)).toContainEqual(['text', 'Суп']);
    expect(fragmentOfMessage(msg, 'суп', ME)).not.toBeNull();
    expect(fragmentOfMessage(msg, 'торт', ME)).toBeNull();
  });

  it('глобальний пошук знаходить рахунок за моєю стравою і не знаходить за чужою', async () => {
    useMessengerStore.setState({ currentUser: { ...useMessengerStore.getState().currentUser, id: ME } });
    const chats = [chatOf([billMsg({ cardMarks: MARKS })])];

    const mine = renderHook(() => useGlobalSearch(chats, 'суп', true));
    await settle();
    expect(mine.result.current.letters.map((l) => l.messageId)).toEqual(['row-bill']);
    mine.unmount();

    const other = renderHook(() => useGlobalSearch(chats, 'торт', true));
    await settle();
    expect(other.result.current.letters).toEqual([]);
  });

  it('позначив страву — пошук бачить її без перезапуску', async () => {
    useMessengerStore.setState({ currentUser: { ...useMessengerStore.getState().currentUser, id: ME } });
    let chats = [chatOf([billMsg({ cardMarks: {} })])];
    const hook = renderHook(({ c }) => useGlobalSearch(c, 'торт', true), { initialProps: { c: chats } });
    await settle();
    expect(hook.result.current.letters).toEqual([]);

    chats = [chatOf([billMsg({ cardMarks: { 'dish:cake': [ME] } })])];
    hook.rerender({ c: chats });
    await settle();
    expect(hook.result.current.letters.map((l) => l.messageId)).toEqual(['row-bill']);
  });
});
