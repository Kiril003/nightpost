import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';

vi.mock('../services/circlesApi', async (orig) => {
  const real = await orig<typeof import('../services/circlesApi')>();
  return {
    ...real,
    listCircles: vi.fn(),
    listCirclesWithMeta: vi.fn(),
    createCircle: vi.fn(),
    deleteCircle: vi.fn(),
    joinCircle: vi.fn(),
    leaveCircle: vi.fn(),
  };
});

import * as circlesApi from '../services/circlesApi';
import { translate } from '../i18n';
import { pluralKey } from '../components/chat/tNow';
import type { CircleInfo } from '../services/circlesApi';
import { CircleSheet } from '../components/messenger/CircleSheet';
import { useMoments, type Moment } from '../services/moments';
import { whereApi, type WhereState } from '../services/peerWhere';
import { useCirclesStore } from '../stores/circlesStore';
import { useMessengerStore } from '../stores/messengerStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Chat } from '../types/messenger';

const BUILTINS: CircleInfo[] = [
  { id: 'personal', name: null, builtin: true, count: 0, locationDefault: true, momentsDefault: true },
  { id: 'close', name: null, builtin: true, count: 1, locationDefault: true, momentsDefault: true },
  { id: 'friends', name: null, builtin: true, count: 0, locationDefault: false, momentsDefault: true },
  { id: 'social', name: null, builtin: true, count: 0, locationDefault: false, momentsDefault: false },
  { id: 'work', name: null, builtin: true, count: 0, locationDefault: false, momentsDefault: false },
];

const chat = (id: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id,
    title: 'Olena',
    type: 'dm',
    peerNodeId: 'node-olena',
    circle: 'close',
    circles: ['close'],
    avatar: '',
    unreadCount: 0,
    pinned: false,
    messages: [],
    ...extra,
  }) as unknown as Chat;

const makeMoment = (id: string, circles: string[]): Moment => ({
  id,
  fromId: 'me',
  fromName: 'Me',
  mine: true,
  atMs: 1_000,
  editedMs: 0,
  untilMs: Date.now() + 60_000,
  keep: false,
  reach: 'circles',
  circles,
  text: 'hello',
  seen: true,
  toCount: 1,
  media: [],
  viewers: [],
  reactions: {},
  myReaction: '',
});

beforeEach(() => {
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'en' } }));
  vi.mocked(circlesApi.listCircles).mockResolvedValue(BUILTINS);
  vi.mocked(circlesApi.listCirclesWithMeta).mockResolvedValue({ circles: BUILTINS, maxCustom: 8 });
  useCirclesStore.setState({ circles: BUILTINS, maxCustom: 8 });
  useMessengerStore.setState({ chats: [chat('c1')] } as never);
  useMoments.setState({ list: [makeMoment('m1', ['close']), makeMoment('m2', ['close'])] });
});

afterEach(() => {
  cleanup();
  delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
  vi.restoreAllMocks();
  useMoments.setState({ list: null });
  useMessengerStore.setState({ chats: [] } as never);
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
});

describe('CircleSheet ring move confirm', () => {
  it('unticking a ring when the diff loses something shows confirm step and does NOT call toggle', async () => {
    const toggleSpy = vi.spyOn(useCirclesStore.getState(), 'toggle');

    render(<CircleSheet chatId="c1" open onClose={vi.fn()} />);

    const sheet = await screen.findByTestId('circle-multi-sheet');
    const closeBtn = within(sheet).getByText('Close').closest('button')!;
    fireEvent.click(closeBtn);

    expect(await within(sheet).findByText('What changes')).toBeTruthy();
    expect(within(sheet).getByText('Won’t see 2 Moments')).toBeTruthy();
    expect(toggleSpy).not.toHaveBeenCalled();
    expect(circlesApi.leaveCircle).not.toHaveBeenCalled();
  });

  it('Cancel leaves the ring ticked and returns to the list', async () => {
    const toggleSpy = vi.spyOn(useCirclesStore.getState(), 'toggle');

    render(<CircleSheet chatId="c1" open onClose={vi.fn()} />);

    const sheet = await screen.findByTestId('circle-multi-sheet');
    fireEvent.click(within(sheet).getByText('Close').closest('button')!);

    await within(sheet).findByText('What changes');
    fireEvent.click(within(sheet).getByRole('button', { name: 'Cancel' }));

    const closeBtn = (await within(sheet).findByText('Close')).closest('button')!;
    expect(within(closeBtn).queryByLabelText(/selected/i)).toBeTruthy();
    expect(toggleSpy).not.toHaveBeenCalled();
  });

  it('Move calls toggle(chatId, ringId, false) once and returns to the list', async () => {
    vi.mocked(circlesApi.leaveCircle).mockResolvedValueOnce([]);
    const toggleSpy = vi.spyOn(useCirclesStore.getState(), 'toggle');

    render(<CircleSheet chatId="c1" open onClose={vi.fn()} />);

    const sheet = await screen.findByTestId('circle-multi-sheet');
    fireEvent.click(within(sheet).getByText('Close').closest('button')!);

    await within(sheet).findByText('What changes');
    fireEvent.click(within(sheet).getByRole('button', { name: 'Move' }));

    await waitFor(() => expect(toggleSpy).toHaveBeenCalledTimes(1));
    expect(toggleSpy).toHaveBeenCalledWith('c1', 'close', false);

    const closeBtn = (await within(sheet).findByText('Close')).closest('button')!;
    expect(within(closeBtn).queryByLabelText(/selected/i)).toBeNull();
  });

  it('ticking a ring calls toggle directly with no confirm step', async () => {
    vi.mocked(circlesApi.joinCircle).mockResolvedValueOnce(['close', 'work']);
    const toggleSpy = vi.spyOn(useCirclesStore.getState(), 'toggle');

    render(<CircleSheet chatId="c1" open onClose={vi.fn()} />);

    const sheet = await screen.findByTestId('circle-multi-sheet');
    const workBtn = within(sheet).getByText('Work').closest('button')!;
    fireEvent.click(workBtn);

    await waitFor(() => expect(toggleSpy).toHaveBeenCalledWith('c1', 'work', true));
    expect(within(sheet).queryByText('What changes')).toBeNull();
  });

  it('shows the location row only while my location is live for this person on the phone', async () => {
    (globalThis as { PhantomBridge?: unknown }).PhantomBridge = { call: () => '{}' };
    const live: WhereState = {
      now_ms: 1_000,
      eligible: true,
      why_not: null,
      mine: { precision: 'quarter', until_ms: 5_000, stand: false },
      theirs: null,
    };
    const get = vi.spyOn(whereApi, 'get').mockResolvedValue(live);
    useMoments.setState({ list: [] });

    render(<CircleSheet chatId="c1" open onClose={vi.fn()} />);

    const sheet = await screen.findByTestId('circle-multi-sheet');
    await waitFor(() => expect(get).toHaveBeenCalledWith('node-olena'));
    await Promise.resolve();
    fireEvent.click(within(sheet).getByText('Close').closest('button')!);

    expect(await within(sheet).findByText('Will no longer see where you are')).toBeTruthy();
    expect(within(sheet).queryByText(/Moments?$/)).toBeNull();
    expect(within(sheet).getByText('Will no longer see where you are').closest('button')).toBeNull();
  });

  it('with no live location and no Moments the untick applies at once', async () => {
    vi.mocked(circlesApi.leaveCircle).mockResolvedValueOnce([]);
    useMoments.setState({ list: [] });
    const toggleSpy = vi.spyOn(useCirclesStore.getState(), 'toggle');

    render(<CircleSheet chatId="c1" open onClose={vi.fn()} />);

    const sheet = await screen.findByTestId('circle-multi-sheet');
    fireEvent.click(within(sheet).getByText('Close').closest('button')!);

    await waitFor(() => expect(toggleSpy).toHaveBeenCalledWith('c1', 'close', false));
    expect(within(sheet).queryByText('What changes')).toBeNull();
  });

  it('uk copy uses plural forms and the ви address of the people screens', () => {
    const line = (n: number) => translate(pluralKey('uk', n, 'ringMove.moments'), 'uk', { count: n });
    expect(line(1)).toBe('Не бачитиме 1 момент');
    expect(line(3)).toBe('Не бачитиме 3 моменти');
    expect(line(5)).toBe('Не бачитиме 5 моментів');
    expect(line(21)).toBe('Не бачитиме 21 момент');
    expect(translate('ringMove.location', 'uk')).toBe('Більше не бачитиме, де ви');
  });
});
