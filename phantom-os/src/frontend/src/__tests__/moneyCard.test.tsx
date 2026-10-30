/**
 * «Скільки я відправив цього тижня?», «Хто мені винен?», «Мій баланс» →
 * картка «Гроші»: баланс, перекази за тиждень по людях, відкриті частки
 * рахунків, позначка «Тестові платежі» у devnet і дії «Відкрити чернетку» /
 * «Поділити» / «Нагадати» (жодна дія не платить сама).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MessageCard } from '../components/messenger/cards/MessageCard';
import { moneyCardOf } from '../components/messenger/cards/MoneyCard';
import { refuseCard } from '../services/composerCards';
import { messageFromNode } from '../services/messengerApi';
import { askSolana, solanaApi, useSolanaAsk } from '../services/solanaActions';
import { useMessengerStore } from '../stores/messengerStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Chat, Message } from '../types/messenger';

const NOW = Date.now();
const DATA = {
  v: 1,
  at: NOW,
  wallet: true,
  balances: [
    { coin: 'USDC', amount: '42.5' },
    { coin: 'SOL', amount: '1.25' },
  ],
  week: [
    { peer: 'node_olya', name: 'Оля Коваль', coin: 'USDC', amount: '6.5' },
    { peer: 'node_petro', name: 'Петро', coin: 'SOL', amount: '0.2' },
  ],
  splits: [
    { chat: 'chat_olya', peer: 'node_olya', name: 'Оля Коваль', coin: 'USDC', amount: '4', owesMe: true },
    { chat: 'chat_petro', peer: 'node_petro', name: 'Петро', coin: 'USDC', amount: '9.5', owesMe: false },
  ],
  test: true,
};

const card = (data: unknown, senderId = 'peer_phantom_agent'): Message =>
  ({
    id: 'm_money',
    senderId,
    senderName: 'Nightpost',
    senderAvatar: '',
    timestamp: '10:00',
    type: 'widget:money',
    isSelf: false,
    moneyData: data,
  }) as unknown as Message;

const DM_OLYA = {
  id: 'chat_olya',
  title: 'Оля Коваль',
  avatar: '',
  type: 'dm',
  circle: 'friends',
  pinned: false,
  unreadCount: 0,
  peerNodeId: 'node_olya',
  members: [],
  messages: [],
} as unknown as Chat;

const DM_PETRO = {
  id: 'chat_petro',
  title: 'Петро',
  avatar: '',
  type: 'dm',
  circle: 'friends',
  pinned: false,
  unreadCount: 0,
  peerNodeId: 'node_petro',
  members: [],
  messages: [],
} as unknown as Chat;

const AskProbe = () => {
  const ask = useSolanaAsk();
  return <div data-testid="solana-ask">{ask ? JSON.stringify(ask) : ''}</div>;
};

const flush = () =>
  act(async () => {
    for (let i = 0; i < 12; i++) await Promise.resolve();
  });

beforeEach(() => {
  vi.restoreAllMocks();
  askSolana(null);
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
  useMessengerStore.setState({
    chats: [DM_OLYA, DM_PETRO],
    activeChatId: 'phantom_agent',
    currentUser: { id: 'node_me', name: 'Кирило', avatar: '' },
  } as never);
});
afterEach(() => {
  askSolana(null);
  cleanup();
});

describe('MoneyCard', () => {
  it('рядок мосту з kind widget:money стає карткою з moneyData', () => {
    const msg = messageFromNode(
      {
        id: 'srv_m1',
        kind: 'widget:money',
        author_id: 'peer_phantom_agent',
        author_name: 'Nightpost',
        sent_at: '2026-10-25T10:00:00',
        body: '',
        moneyData: DATA,
      } as never,
      'node_me',
    );
    expect(msg.type).toBe('widget:money');
    expect(moneyCardOf(msg)?.balances).toEqual([
      { coin: 'USDC', amount: '42.5' },
      { coin: 'SOL', amount: '1.25' },
    ]);
  });

  it('криве тіло не читається', () => {
    expect(moneyCardOf(card({ ...DATA, v: 2 }))).toBeNull();
    expect(moneyCardOf(card({ ...DATA, wallet: 'yes' }))).toBeNull();
    expect(moneyCardOf(card(null))).toBeNull();
  });

  it('показує баланс, перекази за тиждень, розрахунки й позначку «Тестові платежі»', () => {
    render(<MessageCard msg={card(DATA)} meId="node_me" meName="Кирило" />);
    const el = screen.getByTestId('money-card');
    expect(el.querySelector('[data-testid="card-title"]')?.textContent).toBe('Гроші');
    expect(el.querySelector('[data-testid="card-status"]')?.textContent).toContain('Тестові платежі');

    const balances = [...el.querySelectorAll('[data-money-row="balance"]')].map((n) => n.textContent);
    expect(balances[0]).toContain('42.5 USDC · 1.25 SOL');
    expect(balances[0]).toContain('Тестові платежі');

    const week = [...el.querySelectorAll('[data-money-row="week"]')].map((n) => n.textContent);
    expect(week).toEqual(['Оля Коваль · 6.5 USDC', 'Петро · 0.2 SOL']);

    const splits = [...el.querySelectorAll('[data-money-row="split"]')].map((n) => n.textContent);
    expect(splits[0]).toContain('Оля Коваль · 4 USDC');
    expect(splits[0]).toContain('Винен вам');
    expect(splits[1]).toContain('Петро · 9.5 USDC');
    expect(splits[1]).toContain('Ваша частка');
  });

  it('порожній стан чесний — без вигаданих цифр', () => {
    render(
      <MessageCard
        msg={card({ v: 1, at: NOW, wallet: false, balances: [], week: [], splits: [], test: false })}
        meId="node_me"
        meName="Кирило"
      />,
    );
    const el = screen.getByTestId('money-card');
    expect(el.querySelector('[data-money-row="balance-none"]')?.textContent).toBe('Гаманець не підключено');
    expect(el.querySelector('[data-money-row="week-none"]')?.textContent).toBe('Переказів за тиждень немає');
    expect(el.querySelector('[data-money-row="split-none"]')?.textContent).toBe('Ніхто нікому не винен');
    expect(el.textContent).not.toContain('Тестові платежі');
  });

  it('дії відкривають чернетку переказу, аркуш поділу і розмову для нагадування, але ніколи не платять самі', async () => {
    const paySpy = vi.spyOn(solanaApi, 'pay');
    render(
      <>
        <MessageCard msg={card(DATA)} meId="node_me" meName="Кирило" />
        <AskProbe />
      </>,
    );

    fireEvent.click(screen.getByTestId('money-action-draft'));
    expect(paySpy).not.toHaveBeenCalled();
    const draftAsk = JSON.parse(screen.getByTestId('solana-ask').textContent || '{}');
    expect(draftAsk).toMatchObject({
      kind: 'pay',
      peer: { id: 'node_petro', name: 'Петро' },
      cluster: 'devnet',
      chat: { id: 'chat_petro', amount: '9.5', token: 'USDC' },
    });

    fireEvent.click(screen.getByTestId('money-action-split'));
    expect(paySpy).not.toHaveBeenCalled();
    const splitAsk = JSON.parse(screen.getByTestId('solana-ask').textContent || '{}');
    expect(splitAsk).toMatchObject({
      kind: 'group',
      chatId: 'chat_olya',
      peer: { id: 'node_olya', name: 'Оля Коваль' },
    });

    fireEvent.click(screen.getByTestId('money-action-remind'));
    await flush();
    expect(paySpy).not.toHaveBeenCalled();
    expect(useMessengerStore.getState().activeChatId).toBe('chat_olya');
  });

  it('картка грошей не від помічника відмовляється, а надіслати чи переслати її не можна', () => {
    render(<MessageCard msg={card(DATA, 'node_olya')} meId="node_me" meName="Кирило" />);
    expect(screen.queryByTestId('money-card')).toBeNull();
    expect(screen.getByTestId('card-refused').textContent).toBe('Не вдалося прочитати');

    expect(refuseCard('widget:money', JSON.stringify(DATA))).not.toBeNull();
    useMessengerStore.getState().openForwardModal(card(DATA));
    expect(useMessengerStore.getState().isForwardModalOpen).toBe(false);
  });
});
