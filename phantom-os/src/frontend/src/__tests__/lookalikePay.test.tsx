import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import React from 'react';
import { PayMoneySheet } from '../components/messenger/solana/PayMoneySheet';
import { LOOKALIKE_OK_KEY, isLookalikeOk } from '../services/money/lookalike';
import { useMessengerStore } from '../stores/messengerStore';
import { en } from '../i18n/messages/en';
import type { Chat, Message } from '../types/messenger';

const DAY_MS = 24 * 60 * 60 * 1000;

function makeMsg(id: string, createdAt: number): Message {
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

function seedChats() {
  const now = Date.now();
  const trustedChat = {
    id: 'chat-a',
    title: 'Олек',
    type: 'direct',
    peerNodeId: 'node-a',
    circle: 'close',
    messages: [makeMsg('m-old', now - 200 * DAY_MS)],
  } as unknown as Chat;

  const lookalikeChat = {
    id: 'chat-b',
    title: 'Olek',
    type: 'direct',
    peerNodeId: 'node-b',
    messages: [makeMsg('m-new', now - 2 * DAY_MS)],
  } as unknown as Chat;

  const normalChat = {
    id: 'chat-c',
    title: 'Василь',
    type: 'direct',
    peerNodeId: 'node-c',
    messages: [makeMsg('m-c', now - 2 * DAY_MS)],
  } as unknown as Chat;

  useMessengerStore.setState({
    chats: [trustedChat, lookalikeChat, normalChat],
    activeChatId: 'chat-b',
  });
}

beforeEach(() => {
  localStorage.clear();
  localStorage.setItem('phantom.locale', 'en');
  localStorage.setItem('np.locale', 'en');
  localStorage.setItem('locale', 'en');
  seedChats();
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('PayMoneySheet lookalike hold', () => {
  it('shows lookalike-hold and disables Send until "Someone else" is clicked, which writes the key', () => {
    expect(en['lookalike.notThe']).toBe('Not the {name} you know');
    expect(en['lookalike.someoneElse']).toBe('Someone else');

    render(
      <PayMoneySheet
        peer={{ id: 'node-b', name: 'Olek' }}
        cluster="devnet"
        chat={{ id: 'chat-b', amount: '25' }}
        onClose={() => {}}
      />,
    );

    const hold = screen.getByTestId('lookalike-hold');
    expect(hold.textContent).toMatch(/Not the Олек you know|Не той Олек, якого ви знаєте|Není to Олек, kterého znáš/);

    const sendBtn = screen.getByRole('button', { name: /25 USDC/ }) as HTMLButtonElement;
    expect(sendBtn.disabled).toBe(true);

    const dismissBtn = screen.getByRole('button', { name: /Someone else|Інша людина|Někdo jiný/ });
    fireEvent.click(dismissBtn);

    expect(screen.queryByTestId('lookalike-hold')).toBeNull();
    expect(sendBtn.disabled).toBe(false);
    expect(isLookalikeOk('node-b')).toBe(true);
    expect(JSON.parse(localStorage.getItem(LOOKALIKE_OK_KEY) || '[]')).toContain('node-b');
  });

  it('does not show lookalike-hold for a normal peer', () => {
    render(
      <PayMoneySheet
        peer={{ id: 'node-c', name: 'Василь' }}
        cluster="devnet"
        chat={{ id: 'chat-c', amount: '10' }}
        onClose={() => {}}
      />,
    );

    expect(screen.queryByTestId('lookalike-hold')).toBeNull();
    const sendBtn = screen.getByRole('button', { name: /10 USDC/ }) as HTMLButtonElement;
    expect(sendBtn.disabled).toBe(false);
  });
});

void React;
