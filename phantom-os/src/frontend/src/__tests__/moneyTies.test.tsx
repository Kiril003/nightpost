import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';

vi.mock('../services/circlesApi', async (orig) => {
  const real = await orig<typeof import('../services/circlesApi')>();
  return { ...real, listCircles: vi.fn(), listCirclesWithMeta: vi.fn(), leaveCircle: vi.fn(), joinCircle: vi.fn() };
});

import * as circlesApi from '../services/circlesApi';
import type { CircleInfo } from '../services/circlesApi';
import { CircleSheet } from '../components/messenger/CircleSheet';
import { moneyTies } from '../services/money/ties';
import { requestBill } from '../services/money/request';
import { useMoments } from '../services/moments';
import { whereApi, type WhereState } from '../services/peerWhere';
import { useCirclesStore } from '../stores/circlesStore';
import { useMessengerStore } from '../stores/messengerStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Chat, Message } from '../types/messenger';

const ME = 'me';
const PEER = 'node-olena';

const BUILTINS: CircleInfo[] = [
  { id: 'personal', name: null, builtin: true, count: 0, locationDefault: true, momentsDefault: true },
  { id: 'close', name: null, builtin: true, count: 1, locationDefault: true, momentsDefault: true },
  { id: 'friends', name: null, builtin: true, count: 0, locationDefault: false, momentsDefault: true },
  { id: 'social', name: null, builtin: true, count: 0, locationDefault: false, momentsDefault: false },
  { id: 'work', name: null, builtin: true, count: 0, locationDefault: false, momentsDefault: false },
];

const PEOPLE = [
  { id: ME, name: 'Я' },
  { id: PEER, name: 'Olena' },
];

function ask(id: string, from: string, to: string, units: bigint): Message {
  const bill = requestBill({ meId: from, people: PEOPLE, amounts: new Map([[to, units]]), token: 'USDC', note: 'Таксі' });
  return {
    id,
    senderId: from,
    senderName: from,
    senderAvatar: '',
    timestamp: '19:00',
    type: 'widget:bill',
    billData: bill,
  } as unknown as Message;
}

const chat = (messages: Message[], ring = 'friends'): Chat =>
  ({
    id: 'c1',
    title: 'Olena',
    type: 'dm',
    peerNodeId: PEER,
    circle: ring,
    circles: [ring],
    avatar: '',
    unreadCount: 0,
    pinned: false,
    messages,
  }) as unknown as Chat;

beforeEach(() => {
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'en' } }));
  vi.mocked(circlesApi.listCircles).mockResolvedValue(BUILTINS);
  vi.mocked(circlesApi.listCirclesWithMeta).mockResolvedValue({ circles: BUILTINS, maxCustom: 8 });
  useCirclesStore.setState({ circles: BUILTINS, maxCustom: 8 });
  useMessengerStore.setState({ currentUser: { ...useMessengerStore.getState().currentUser, id: ME } });
  useMoments.setState({ list: [] });
});

afterEach(() => {
  cleanup();
  delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
  vi.restoreAllMocks();
  useMessengerStore.setState({ chats: [] } as never);
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
});

describe('money ties on a ring move', () => {
  it('requests both ways net to one tie', () => {
    expect(moneyTies([ask('m1', PEER, ME, 14_200_000n), ask('m2', ME, PEER, 5_000_000n)], ME)).toEqual([
      { dir: 'iOwe', token: 'USDC', units: 9_200_000n },
    ]);
    expect(moneyTies([ask('m2', ME, PEER, 5_000_000n)], ME)).toEqual([{ dir: 'owesMe', token: 'USDC', units: 5_000_000n }]);
  });

  it('no bills, no ties', () => {
    expect(moneyTies([], ME)).toEqual([]);
  });

  it('chat -> circle: unticking Friends with an open request shows it before the move', async () => {
    useMessengerStore.setState({ chats: [chat([ask('m1', PEER, ME, 14_200_000n)])] } as never);
    const toggleSpy = vi.spyOn(useCirclesStore.getState(), 'toggle');
    render(<CircleSheet chatId="c1" open onClose={vi.fn()} />);

    const sheet = await screen.findByTestId('circle-multi-sheet');
    fireEvent.click(within(sheet).getByText('Friends').closest('button')!);

    expect(await within(sheet).findByText('What changes')).toBeTruthy();
    expect(within(sheet).getAllByTestId('ring-move-tie').map((n) => n.textContent)).toEqual(['You owe · 14.2 USDC']);
    expect(toggleSpy).not.toHaveBeenCalled();
  });

  it('one open request + live place -> two lines on moving out of Close', async () => {
    (globalThis as { PhantomBridge?: unknown }).PhantomBridge = { call: () => '{}' };
    const live: WhereState = {
      now_ms: 1_000,
      eligible: true,
      why_not: null,
      mine: { precision: 'quarter', until_ms: 5_000, stand: false },
      theirs: null,
    };
    vi.spyOn(whereApi, 'get').mockResolvedValue(live);
    useMessengerStore.setState({ chats: [chat([ask('m1', PEER, ME, 14_200_000n)], 'close')] } as never);
    render(<CircleSheet chatId="c1" open onClose={vi.fn()} />);

    const sheet = await screen.findByTestId('circle-multi-sheet');
    await vi.waitFor(() => expect(whereApi.get).toHaveBeenCalled());
    await Promise.resolve();
    fireEvent.click(within(sheet).getByText('Close').closest('button')!);

    expect(await within(sheet).findByText('Will no longer see where you are')).toBeTruthy();
    expect(within(sheet).getByText('You owe · 14.2 USDC')).toBeTruthy();
  });

  it('no money and nothing lost moves at once', async () => {
    useMessengerStore.setState({ chats: [chat([])] } as never);
    vi.mocked(circlesApi.leaveCircle).mockResolvedValueOnce([]);
    const toggleSpy = vi.spyOn(useCirclesStore.getState(), 'toggle');
    render(<CircleSheet chatId="c1" open onClose={vi.fn()} />);

    const sheet = await screen.findByTestId('circle-multi-sheet');
    fireEvent.click(within(sheet).getByText('Friends').closest('button')!);
    await vi.waitFor(() => expect(toggleSpy).toHaveBeenCalledWith('c1', 'friends', false));
    expect(within(sheet).queryByTestId('ring-move-tie')).toBeNull();
  });
});
