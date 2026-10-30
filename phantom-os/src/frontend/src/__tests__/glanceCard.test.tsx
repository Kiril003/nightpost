/**
 * «Що там з Олею?» → картка «людина одним поглядом»: лише те, що приїхало
 * з телефона; порожнє чесно порожнє; «Написати» веде в розмову з нею.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MessageCard } from '../components/messenger/cards/MessageCard';
import { glanceOf } from '../components/messenger/cards/GlanceCard';
import { refuseCard } from '../services/composerCards';
import { messageFromNode } from '../services/messengerApi';
import { useMessengerStore } from '../stores/messengerStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Chat, Message } from '../types/messenger';

const NOW = Date.now();
const DATA = {
  v: 1,
  peer: 'node_olya',
  name: 'Оля Коваль',
  at: NOW,
  last: { text: 'Ти встигаєш на 10?', at: NOW - 60_000, mine: false },
  promises: [{ what: 'скинути фото', due: null }],
  money: [{ coin: 'USDC', sent: '6.5', got: '2' }],
  test: true,
};

const card = (data: unknown, senderId = 'peer_phantom_agent'): Message =>
  ({ id: 'm1', senderId, senderName: 'Nightpost', senderAvatar: '', timestamp: '10:00', type: 'widget:glance', isSelf: false, glanceData: data }) as unknown as Message;

const DM = { id: 'chat_olya', title: 'Оля Коваль', avatar: '', type: 'dm', circle: 'friends', pinned: false, unreadCount: 0, peerNodeId: 'node_olya', members: [], messages: [] } as unknown as Chat;

const flush = () => act(async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); });

beforeEach(() => {
  vi.restoreAllMocks();
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
  useMessengerStore.setState({ chats: [DM], activeChatId: 'phantom_agent', currentUser: { id: 'node_me', name: 'Кирило', avatar: '' } } as never);
});
afterEach(cleanup);

describe('GlanceCard', () => {
  it('рядок мосту з kind widget:glance стає карткою з glanceData', () => {
    const msg = messageFromNode(
      { id: 'srv_1', kind: 'widget:glance', author_id: 'peer_phantom_agent', author_name: 'Nightpost', sent_at: '2026-10-25T10:00:00', body: '', glanceData: DATA } as never,
      'node_me',
    );
    expect(msg.type).toBe('widget:glance');
    expect(glanceOf(msg)?.name).toBe('Оля Коваль');
  });

  it('криве тіло не читається', () => {
    expect(glanceOf(card({ ...DATA, peer: '' }))).toBeNull();
    expect(glanceOf(card({ ...DATA, v: 2 }))).toBeNull();
  });

  it('показує останні слова, обіцянку й гроші з позначкою тестових', () => {
    render(<MessageCard msg={card(DATA)} meId="node_me" meName="Кирило" />);
    const el = screen.getByTestId('glance-card');
    expect(el.querySelector('[data-testid="card-title"]')?.textContent).toBe('Оля Коваль');
    expect(el.textContent).toContain('Станом на');
    const rows = Object.fromEntries([...el.querySelectorAll('[data-glance-row]')].map((n) => [n.getAttribute('data-glance-row'), n.textContent]));
    expect(rows.last).toContain('«Ти встигаєш на 10?»');
    expect(rows.last).toContain('Оля');
    expect(rows.promise).toContain('скинути фото');
    expect(rows.money).toContain('Від вас 6.5 USDC · Вам 2 USDC');
    expect(rows.money).toContain('Тестові платежі');
  });

  it('нічого між вами — одне чесне речення, без вигаданих рядків', () => {
    render(<MessageCard msg={card({ ...DATA, last: null, promises: [], money: [], test: false })} meId="node_me" meName="Кирило" />);
    const el = screen.getByTestId('glance-card');
    expect(el.querySelectorAll('[data-glance-row]').length).toBe(0);
    expect(el.querySelector('[data-glance-empty]')?.textContent).toBe('Між вами поки нічого');
  });

  it('«Написати» відкриває розмову з цією людиною', async () => {
    render(<MessageCard msg={card(DATA)} meId="node_me" meName="Кирило" />);
    fireEvent.click(screen.getByText('Написати'));
    await flush();
    expect(useMessengerStore.getState().activeChatId).toBe('chat_olya');
  });

  it('погляд чи День від людини — не картка, лише рядок «Не вдалося прочитати»', () => {
    for (const msg of [card(DATA, 'node_olya'), { ...card(DATA, 'node_olya'), type: 'widget:day', glanceData: undefined, dayData: { v: 1, day: '2026-10-25', at: NOW } } as unknown as Message]) {
      cleanup();
      render(<MessageCard msg={msg} meId="node_me" meName="Кирило" />);
      expect(screen.queryByTestId('glance-card')).toBeNull();
      expect(screen.queryByTestId('day-card')).toBeNull();
      expect(screen.queryByTestId('day-card-past')).toBeNull();
      expect(screen.getByTestId('card-refused').textContent).toBe('Не вдалося прочитати');
      expect(screen.queryByText('Написати')).toBeNull();
    }
  });

  it('картки помічника не надсилаються й не пересилаються', () => {
    expect(refuseCard('widget:glance', JSON.stringify(DATA))).not.toBeNull();
    expect(refuseCard('widget:day', JSON.stringify({ v: 1, day: '2026-10-25', at: NOW }))).not.toBeNull();
    useMessengerStore.getState().openForwardModal(card(DATA));
    expect(useMessengerStore.getState().isForwardModalOpen).toBe(false);
    const text = { id: 't1', senderId: 'node_me', senderName: 'Кирило', senderAvatar: '', timestamp: '10:00', type: 'text', text: 'привіт', isSelf: true } as unknown as Message;
    useMessengerStore.getState().openForwardModal([card(DATA), text]);
    expect(useMessengerStore.getState().activeForwardMessages.map((m) => m.id)).toEqual(['t1']);
    useMessengerStore.getState().closeForwardModal();
  });
});
