import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';

vi.mock('../services/circlesApi', async (orig) => {
  const real = await orig<typeof import('../services/circlesApi')>();
  return { ...real, listCircles: vi.fn(), listCirclesWithMeta: vi.fn(), joinCircle: vi.fn(), leaveCircle: vi.fn() };
});

import * as circlesApi from '../services/circlesApi';
import type { CircleInfo } from '../services/circlesApi';
import { CircleSheet } from '../components/messenger/CircleSheet';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { WhereAskChips } from '../components/messenger/WhereAskChips';
import { useMoments, type Moment } from '../services/moments';
import { useCirclesStore } from '../stores/circlesStore';
import { useFolders } from '../stores/folderStore';
import { useMessengerStore } from '../stores/messengerStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Chat, UserProfile } from '../types/messenger';

const flush = () =>
  act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });

const setLocale = (l: 'uk' | 'en') =>
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: l } }));

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  setLocale('uk');
  useMessengerStore.setState({ chats: [] } as never);
  delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
});

describe('CircleSheet: a moment without circles does not break unticking', () => {
  const BUILTINS: CircleInfo[] = [
    { id: 'close', name: null, builtin: true, count: 1, locationDefault: false, momentsDefault: true },
    { id: 'work', name: null, builtin: true, count: 0, locationDefault: false, momentsDefault: false },
  ];

  beforeEach(() => {
    setLocale('en');
    vi.mocked(circlesApi.listCircles).mockResolvedValue(BUILTINS);
    vi.mocked(circlesApi.listCirclesWithMeta).mockResolvedValue({ circles: BUILTINS, maxCustom: 8 });
    vi.mocked(circlesApi.leaveCircle).mockResolvedValue([]);
    useCirclesStore.setState({ circles: BUILTINS, maxCustom: 8 });
    useMessengerStore.setState({
      chats: [{ id: 'c1', title: 'Olena', type: 'dm', circle: 'close', circles: ['close'], avatar: '', unreadCount: 0, messages: [] }],
    } as never);
    useMoments.setState({
      list: [{ id: 'm1', mine: true, reach: 'circles', keep: true, untilMs: 0, circles: undefined } as unknown as Moment],
    } as never);
  });

  const momentsBefore = useMoments.getState().list;
  afterEach(() => useMoments.setState({ list: momentsBefore } as never));

  it('untick still reaches leaveCircle', async () => {
    render(<CircleSheet chatId="c1" open onClose={vi.fn()} />);
    const sheet = await screen.findByTestId('circle-multi-sheet');
    fireEvent.click(within(sheet).getByText('Close').closest('button')!);
    await waitFor(() => expect(circlesApi.leaveCircle).toHaveBeenCalledWith('close', 'c1'));
  });
});

describe('WhereAskChips: guards and live refresh', () => {
  let mine: unknown = null;
  const gets: string[] = [];
  const bridge = (m: string, p: string) => {
    if (m === 'GET') gets.push(p);
    return JSON.stringify({ ok: true, status: 200, data: { now_ms: Date.now(), eligible: true, why_not: null, mine, theirs: null } });
  };
  const ask = (extra: Partial<Chat> = {}): Chat =>
    ({
      id: 'w1',
      title: 'Оксана',
      peerNodeId: 'oksana',
      avatar: '',
      type: 'direct',
      circle: 'friends',
      unreadCount: 1,
      messages: [
        { id: 'q1', senderId: 'oksana', senderName: 'Оксана', senderAvatar: '', timestamp: '12:00', sentAt: new Date(Date.now() - 60_000).toISOString(), type: 'text', text: 'Де ти?', isSelf: false },
      ],
      ...extra,
    }) as Chat;

  beforeEach(() => {
    mine = null;
    gets.length = 0;
    sessionStorage.clear();
    (globalThis as { PhantomBridge?: unknown }).PhantomBridge = { call: bridge };
  });

  it('a chat without a messages array renders nothing instead of throwing', async () => {
    const { container } = render(<WhereAskChips chat={ask({ messages: undefined as never })} />);
    await flush();
    expect(container.innerHTML).toBe('');
  });

  it('a share made elsewhere hides the chips without a remount', async () => {
    render(<WhereAskChips chat={ask()} />);
    await flush();
    expect(screen.getByTestId('where-ask-chips')).toBeTruthy();

    mine = { precision: 'area', until_ms: Date.now() + 3600_000 };
    await act(async () => {
      (window as unknown as { __phantomWhereChanged: () => void }).__phantomWhereChanged();
    });
    await flush();
    expect(gets.length).toBeGreaterThan(1);
    expect(screen.queryByTestId('where-ask-chips')).toBeNull();
  });
});

describe('Today: typing into the resting ask field keeps the first character', () => {
  beforeEach(() => {
    (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = { navigate: vi.fn(() => true) };
    useMessengerStore.setState({ typingStatus: {}, drafts: {} } as never);
    useFolders.setState({ folders: [] } as never);
  });

  it('the opened search box starts with what was typed', async () => {
    const { container } = render(
      <PhoneChatList
        chats={[]}
        currentUser={{ id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile}
        onSelectChat={vi.fn()}
        onNewChat={vi.fn()}
        onOpenChatActions={vi.fn()}
      />,
    );
    const rest = container.querySelector('[data-search-pill] input') as HTMLInputElement;
    fireEvent.change(rest, { target: { value: 'О' } });
    const box = (await waitFor(() => {
      const el = container.querySelector('[data-search-box] input');
      expect(el).toBeTruthy();
      return el;
    })) as HTMLInputElement;
    expect(box.value).toBe('О');
  });
});

describe('FormSheet without onClose', () => {
  it('call settings opened with no closer show no dead «×»', async () => {
    const { CallSettingsSheet } = await import('../components/messenger/CallSettingsSheet');
    render(<CallSettingsSheet active={false} readDataUsedBytes={() => null} />);
    const sheet = screen.getByTestId('call-settings-sheet');
    expect(sheet.querySelector('[data-sheet-close]')).toBeNull();
  });

  it('with a closer the «×» is there and closes', async () => {
    const { CallSettingsSheet } = await import('../components/messenger/CallSettingsSheet');
    const onClose = vi.fn();
    render(<CallSettingsSheet active={false} onClose={onClose} readDataUsedBytes={() => null} />);
    fireEvent.click(screen.getByTestId('call-settings-sheet').querySelector('[data-sheet-close]') as HTMLElement);
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
