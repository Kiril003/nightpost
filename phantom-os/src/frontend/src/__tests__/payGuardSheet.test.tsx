import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, cleanup, act } from '@testing-library/react';
import React from 'react';
import { PayMoneySheet } from '../components/messenger/solana/PayMoneySheet';
import { solanaApi } from '../services/solanaActions';
import { useMessengerStore } from '../stores/messengerStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import { onPeerCheck } from '../services/peerWhere';
import type { Chat, Message, MessagePayment } from '../types/messenger';

const DAY_MS = 86_400_000;

const setLocale = (l: 'uk' | 'en') =>
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: l } }));

const keyChat = (daysAgo: number, msgs: Message[] = [makePayMsg('p-1', 'addr-1')]) =>
  ({
    id: 'chat-key',
    title: 'Taras',
    type: 'direct',
    peerNodeId: 'node-key',
    contactKeyChangedAt: new Date(Date.now() - daysAgo * DAY_MS).toISOString(),
    messages: msgs,
  }) as unknown as Chat;

const renderKey = (chat: Chat, amount = '25') => {
  useMessengerStore.setState({ chats: [chat], activeChatId: chat.id });
  return render(
    <PayMoneySheet
      peer={{ id: 'node-key', name: 'Taras' }}
      cluster="devnet"
      chat={{ id: chat.id, amount }}
      onClose={() => {}}
    />,
  );
};

function makeTextMsg(id: string, createdAt: number): Message {
  return {
    id,
    senderId: 'peer',
    senderName: 'Peer',
    senderAvatar: '',
    timestamp: new Date(createdAt).toISOString(),
    createdAt,
    type: 'text',
    content: 'hi',
  } as unknown as Message;
}

function makePayMsg(id: string, to: string): Message {
  return {
    id,
    senderId: 'me',
    senderName: 'Me',
    senderAvatar: '',
    timestamp: new Date().toISOString(),
    isSelf: true,
    status: 'sent',
    type: 'text',
    content: '',
    payment: {
      tx: `tx-${id}`,
      from: 'wallet-me',
      to,
      amount: '10',
      token: 'USDC',
      cluster: 'devnet',
    } as unknown as MessagePayment,
  } as unknown as Message;
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-04-19T12:00:00Z'));
  localStorage.clear();
  setLocale('en');
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('PayMoneySheet payGuard', () => {
  it('requires a 2-second hold when contact key changed 1 day ago', () => {
    const paySpy = vi.spyOn(solanaApi, 'pay').mockResolvedValue({ opened: true });
    const now = Date.now();
    const chat = {
      id: 'chat-key',
      title: 'Taras',
      type: 'direct',
      peerNodeId: 'node-key',
      contactKeyChangedAt: new Date(now - DAY_MS).toISOString(),
      messages: [makePayMsg('p-1', 'addr-1')],
    } as unknown as Chat;
    useMessengerStore.setState({ chats: [chat], activeChatId: 'chat-key' });

    render(
      <PayMoneySheet
        peer={{ id: 'node-key', name: 'Taras' }}
        cluster="devnet"
        chat={{ id: 'chat-key', amount: '25' }}
        onClose={() => {}}
      />,
    );

    const guardLine = screen.getByTestId('pay-guard');
    expect(guardLine.textContent).toContain('Key changed 1 day ago');

    const sendBtn = screen.getByRole('button', { name: /25 USDC/ });
    fireEvent.click(sendBtn);
    expect(paySpy).not.toHaveBeenCalled();

    fireEvent.pointerDown(sendBtn);
    act(() => {
      vi.advanceTimersByTime(1900);
    });
    fireEvent.pointerUp(sendBtn);
    act(() => {
      vi.advanceTimersByTime(500);
    });
    expect(paySpy).not.toHaveBeenCalled();

    fireEvent.pointerDown(sendBtn);
    act(() => {
      vi.advanceTimersByTime(2000);
    });
    expect(paySpy).toHaveBeenCalledTimes(1);
    expect(paySpy).toHaveBeenCalledWith({
      peer_id: 'node-key',
      token: 'USDC',
      amount: '25',
      chat_id: 'chat-key',
    });
  });

  it('shows no pay-guard and pays on a single click for a normal chat with an earlier payment', () => {
    const paySpy = vi.spyOn(solanaApi, 'pay').mockResolvedValue({ opened: true });
    const chat = {
      id: 'chat-ok',
      title: 'Vasyl',
      type: 'direct',
      peerNodeId: 'node-ok',
      messages: [makePayMsg('p-1', 'addr-1')],
    } as unknown as Chat;
    useMessengerStore.setState({ chats: [chat], activeChatId: 'chat-ok' });

    render(
      <PayMoneySheet
        peer={{ id: 'node-ok', name: 'Vasyl' }}
        cluster="devnet"
        chat={{ id: 'chat-ok', amount: '15' }}
        onClose={() => {}}
      />,
    );

    expect(screen.queryByTestId('pay-guard')).toBeNull();
    const sendBtn = screen.getByRole('button', { name: /15 USDC/ });
    fireEvent.click(sendBtn);
    expect(paySpy).toHaveBeenCalledTimes(1);
    expect(paySpy).toHaveBeenCalledWith({
      peer_id: 'node-ok',
      token: 'USDC',
      amount: '15',
      chat_id: 'chat-ok',
    });
  });

  it('shows first-payment line but still pays on one click', () => {
    const paySpy = vi.spyOn(solanaApi, 'pay').mockResolvedValue({ opened: true });
    const chat = {
      id: 'chat-first',
      title: 'Oksana',
      type: 'direct',
      peerNodeId: 'node-first',
      messages: [],
    } as unknown as Chat;
    useMessengerStore.setState({ chats: [chat], activeChatId: 'chat-first' });

    render(
      <PayMoneySheet
        peer={{ id: 'node-first', name: 'Oksana' }}
        cluster="devnet"
        chat={{ id: 'chat-first', amount: '10' }}
        onClose={() => {}}
      />,
    );

    const guardLine = screen.getByTestId('pay-guard');
    expect(guardLine.textContent).toMatch(/First payment|Перший переказ|První platba/);

    const sendBtn = screen.getByRole('button', { name: /10 USDC/ });
    fireEvent.click(sendBtn);
    expect(paySpy).toHaveBeenCalledTimes(1);
    expect(paySpy).toHaveBeenCalledWith({
      peer_id: 'node-first',
      token: 'USDC',
      amount: '10',
      chat_id: 'chat-first',
    });
  });

  it('keeps blocking payment when lookalike hold is active even after a full hold', () => {
    const paySpy = vi.spyOn(solanaApi, 'pay').mockResolvedValue({ opened: true });
    const now = Date.now();
    const trustedChat = {
      id: 'chat-a',
      title: 'Олек',
      type: 'direct',
      peerNodeId: 'node-a',
      circle: 'close',
      messages: [makeTextMsg('m-old', now - 200 * DAY_MS)],
    } as unknown as Chat;
    const lookalikeChat = {
      id: 'chat-b',
      title: 'Olek',
      type: 'direct',
      peerNodeId: 'node-b',
      contactKeyChangedAt: new Date(now - DAY_MS).toISOString(),
      messages: [makeTextMsg('m-new', now - 2 * DAY_MS)],
    } as unknown as Chat;
    useMessengerStore.setState({
      chats: [trustedChat, lookalikeChat],
      activeChatId: 'chat-b',
    });

    render(
      <PayMoneySheet
        peer={{ id: 'node-b', name: 'Olek' }}
        cluster="devnet"
        chat={{ id: 'chat-b', amount: '25' }}
        onClose={() => {}}
      />,
    );

    expect(screen.getByTestId('lookalike-hold')).not.toBeNull();
    expect(screen.getByTestId('pay-guard')).not.toBeNull();

    const sendBtn = screen.getByRole('button', { name: /25 USDC/ });
    fireEvent.pointerDown(sendBtn);
    act(() => {
      vi.advanceTimersByTime(2500);
    });
    expect(paySpy).not.toHaveBeenCalled();
  });

  it('uk plural and today for a changed key', () => {
    setLocale('uk');
    const { unmount } = renderKey(keyChat(3));
    expect(screen.getByTestId('pay-guard').textContent).toContain('Ключ змінився 3 дні тому');
    unmount();
    renderKey(keyChat(0));
    expect(screen.getByTestId('pay-guard').textContent).toContain('Ключ змінився сьогодні');
  });

  it('keyboard hold pays after 2 s, early key release does not', () => {
    const paySpy = vi.spyOn(solanaApi, 'pay').mockResolvedValue({ opened: true });
    renderKey(keyChat(1));
    const sendBtn = screen.getByRole('button', { name: /25 USDC/ });
    fireEvent.keyDown(sendBtn, { key: 'Enter' });
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    fireEvent.keyUp(sendBtn, { key: 'Enter' });
    act(() => {
      vi.advanceTimersByTime(2000);
    });
    expect(paySpy).not.toHaveBeenCalled();
    fireEvent.keyDown(sendBtn, { key: ' ' });
    act(() => {
      vi.advanceTimersByTime(2000);
    });
    expect(paySpy).toHaveBeenCalledTimes(1);
  });

  it('editing the amount mid-hold cancels the hold', () => {
    const paySpy = vi.spyOn(solanaApi, 'pay').mockResolvedValue({ opened: true });
    renderKey(keyChat(1));
    const sendBtn = screen.getByRole('button', { name: /25 USDC/ });
    fireEvent.pointerDown(sendBtn);
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    fireEvent.change(screen.getByTestId('solana-amount-input'), { target: { value: '250' } });
    act(() => {
      vi.advanceTimersByTime(2000);
    });
    expect(paySpy).not.toHaveBeenCalled();
  });

  it('switching the coin mid-hold cancels the hold', () => {
    const paySpy = vi.spyOn(solanaApi, 'pay').mockResolvedValue({ opened: true });
    renderKey(keyChat(1));
    fireEvent.pointerDown(screen.getByRole('button', { name: /25 USDC/ }));
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    fireEvent.click(screen.getByRole('button', { name: 'EURC' }));
    act(() => {
      vi.advanceTimersByTime(2000);
    });
    expect(paySpy).not.toHaveBeenCalled();
  });

  it('Check opens the key check for this peer', () => {
    const seen: string[] = [];
    const off = onPeerCheck((id) => seen.push(id));
    renderKey(keyChat(1));
    fireEvent.click(screen.getByRole('button', { name: 'Check' }));
    off();
    expect(seen).toEqual(['node-key']);
  });

  it('past payments to A then B: no guard, one click pays (no stale «new address»)', () => {
    const paySpy = vi.spyOn(solanaApi, 'pay').mockResolvedValue({ opened: true });
    const chat = {
      id: 'chat-addr',
      title: 'Taras',
      type: 'direct',
      peerNodeId: 'node-key',
      messages: [makePayMsg('p-1', 'addr-1'), makePayMsg('p-2', 'addr-2')],
    } as unknown as Chat;
    renderKey(chat);
    expect(screen.queryByTestId('pay-guard')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /25 USDC/ }));
    expect(paySpy).toHaveBeenCalledTimes(1);
  });
});

void React;
