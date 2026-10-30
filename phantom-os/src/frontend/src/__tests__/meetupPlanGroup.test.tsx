/**
 * Група плану (W4): шапка каже, коли група сховається; профіль групи показує
 * план — де, коли, скільки йде — і веде до «Лишити», «Новий план», «Розділити».
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, renderHook, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../services/messengerApi', async (orig) => {
  const real = await orig<typeof import('../services/messengerApi')>();
  return {
    ...real,
    messengerApi: {
      ...real.messengerApi,
      pulse: vi.fn(() => new Promise(() => {})),
      peerProfiles: vi.fn(() => new Promise(() => {})),
    },
  };
});

import { Header } from '../components/messenger/Header';
import { GroupProfileSheet } from '../components/messenger/GroupProfileSheet';
import { openMeetupAsk, useMeetupAsk } from '../services/nativeMeetupDraft';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message, UserProfile } from '../types/messenger';
import { AT, ME, OLENA, installBridge, meetupMsg, removeBridge, type Call } from './meetupFixtures';

let prev: ReturnType<typeof useMessengerStore.getState>;
let calls: Call[];

const group = (messages: Message[]): Chat =>
  ({
    id: 'grp',
    title: 'Cafe X · 19:00',
    type: 'group',
    circle: 'all',
    avatar: '',
    unreadCount: 0,
    messages,
    members: [
      { id: ME, name: 'Я', role: 'owner' },
      { id: OLENA, name: 'Олена', role: 'member' },
    ],
  }) as unknown as Chat;

const plan = () => meetupMsg({ cardMarks: { 'rsvp:yes': [OLENA] } });

const drawHeader = (c: Chat) =>
  render(
    <MemoryRouter>
      <Header
        currentChat={c}
        currentUser={{ id: ME, name: 'Я', avatar: '' } as unknown as UserProfile}
        onOpenSettings={() => {}}
        onOpenGroupDetails={() => {}}
        isSoundEnabled
        onToggleSound={() => {}}
        onToggleSearch={() => {}}
        isSearching={false}
        onBack={() => {}}
      />
    </MemoryRouter>,
  );

const Profile: React.FC<{ onClose?: () => void }> = ({ onClose = () => {} }) => {
  const c = useMessengerStore((s) => s.chats[0]);
  return <GroupProfileSheet chat={c} onClose={onClose} onSelectMember={() => {}} onTogglePinChat={() => {}} />;
};

beforeEach(() => {
  prev = useMessengerStore.getState();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(AT + 2 * 3600_000));
  calls = installBridge(() => undefined);
  useMessengerStore.setState({
    currentUser: { ...useMessengerStore.getState().currentUser, id: ME, name: 'Я' },
    chats: [group([plan()])],
    activeChatId: 'grp',
  });
});

afterEach(() => {
  cleanup();
  removeBridge();
  openMeetupAsk(null);
  vi.useRealTimers();
  useMessengerStore.setState(prev, true);
});

describe('шапка групи плану', () => {
  it('каже, коли група сховається', () => {
    drawHeader(group([plan()]));
    expect(screen.getByTestId('header-plan-line').textContent).toMatch(/сховається .*19:00/);
  });

  it('звичайна група й лишена група плану — без цього рядка', () => {
    drawHeader(group([{ ...plan(), id: 'x', senderId: OLENA, isSelf: false, type: 'text', text: 'привіт', meetupData: undefined }, plan()]));
    expect(screen.queryByTestId('header-plan-line')).toBeNull();
    cleanup();
    drawHeader(group([meetupMsg({ cardMarks: { 'keep:1': [OLENA] } })]));
    expect(screen.queryByTestId('header-plan-line')).toBeNull();
  });
});

describe('план у профілі групи', () => {
  it('де, коли, скільки йде і коли сховається', () => {
    render(<Profile />);
    const card = screen.getByTestId('group-plan');
    expect(card.textContent).toContain('Cafe X');
    expect(card.textContent).toContain('1 з 3 ідуть');
    expect(screen.getByTestId('group-plan-closes').textContent).toMatch(/сховається/i);
  });

  it('«Лишити» ставить keep:1 на картку плану', async () => {
    render(<Profile />);
    fireEvent.click(screen.getByTestId('group-plan-keep'));
    await act(async () => {
      for (let i = 0; i < 6; i++) await Promise.resolve();
    });
    expect(calls.filter((c) => c.p.endsWith('/marks')).map((c) => c.b)).toEqual([{ value: 'keep:1', on: true }]);
  });

  it('«Новий план» і «Розділити» відкривають чернетку саме для цієї групи', () => {
    const onClose = vi.fn();
    const ask = renderHook(() => useMeetupAsk());
    render(<Profile onClose={onClose} />);
    fireEvent.click(screen.getByTestId('group-plan-new'));
    expect(ask.result.current.ask).toEqual({ kind: 'meetup', chatId: 'grp' });
    fireEvent.click(screen.getByTestId('group-plan-split'));
    expect(ask.result.current.ask).toEqual({ kind: 'bill-draft', messageId: 'row-mu' });
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it('у групі без плану блоку немає', () => {
    useMessengerStore.setState({ chats: [group([{ ...plan(), type: 'text', text: 'hi', meetupData: undefined }])] });
    render(<Profile />);
    expect(screen.queryByTestId('group-plan')).toBeNull();
  });
});
