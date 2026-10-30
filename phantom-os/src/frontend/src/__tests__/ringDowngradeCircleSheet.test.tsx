import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';

vi.mock('../services/circlesApi', async (orig) => {
  const real = await orig<typeof import('../services/circlesApi')>();
  return {
    ...real,
    listCircles: vi.fn(),
    listCirclesWithMeta: vi.fn(),
    joinCircle: vi.fn(),
    leaveCircle: vi.fn(),
  };
});

vi.mock('../services/ringDowngrade', async (orig) => {
  const real = await orig<typeof import('../services/ringDowngrade')>();
  return { ...real, reachOf: vi.fn().mockResolvedValue([]) };
});

import * as circlesApi from '../services/circlesApi';
import * as ringDowngrade from '../services/ringDowngrade';
import type { CircleInfo } from '../services/circlesApi';
import { CircleSheet } from '../components/messenger/CircleSheet';
import { useMoments } from '../services/moments';
import { useCirclesStore } from '../stores/circlesStore';
import { useMessengerStore } from '../stores/messengerStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Chat } from '../types/messenger';

const BUILTINS: CircleInfo[] = [
  { id: 'personal', name: null, builtin: true, count: 0, locationDefault: true, momentsDefault: true },
  { id: 'close', name: null, builtin: true, count: 1, locationDefault: true, momentsDefault: true },
  { id: 'friends', name: null, builtin: true, count: 1, locationDefault: false, momentsDefault: true },
  { id: 'social', name: null, builtin: true, count: 0, locationDefault: false, momentsDefault: false },
  { id: 'work', name: null, builtin: true, count: 0, locationDefault: false, momentsDefault: false },
];

const olena: Chat = {
  id: 'c1',
  title: 'Olena',
  type: 'dm',
  peerNodeId: 'node-olena',
  circle: 'close',
  circles: ['close', 'friends'],
  avatar: '',
  unreadCount: 0,
  pinned: false,
  messages: [],
};

beforeEach(() => {
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'en' } }));
  vi.mocked(circlesApi.listCircles).mockResolvedValue(BUILTINS);
  vi.mocked(circlesApi.listCirclesWithMeta).mockResolvedValue({ circles: BUILTINS, maxCustom: 8 });
  useCirclesStore.setState({ circles: BUILTINS, maxCustom: 8 });
  useMessengerStore.setState({ chats: [olena] } as never);
  useMoments.setState({ list: [] });
  vi.mocked(ringDowngrade.reachOf).mockClear();
  vi.mocked(circlesApi.leaveCircle).mockReset();
});

afterEach(() => {
  cleanup();
  useMoments.setState({ list: null });
  useMessengerStore.setState({ chats: [] } as never);
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
});

const untickClose = async () => {
  render(<CircleSheet chatId="c1" open onClose={vi.fn()} />);
  const sheet = await screen.findByTestId('circle-multi-sheet');
  fireEvent.click(within(sheet).getByText('Close').closest('button')!);
  const move = within(sheet).queryByRole('button', { name: 'Move' });
  if (move) fireEvent.click(move);
};

describe('CircleSheet → ring downgrade check', () => {
  it('checks what the person still reaches after a successful move to a wider ring', async () => {
    vi.mocked(circlesApi.leaveCircle).mockResolvedValueOnce(['friends']);
    await untickClose();
    await waitFor(() => expect(ringDowngrade.reachOf).toHaveBeenCalledTimes(1));
    expect(ringDowngrade.reachOf).toHaveBeenCalledWith({ id: 'node-olena', chatId: 'c1', name: 'Olena' });
  });

  it('does not offer to strip anything when the move failed', async () => {
    vi.mocked(circlesApi.leaveCircle).mockResolvedValueOnce(null);
    await untickClose();
    await waitFor(() => expect(circlesApi.leaveCircle).toHaveBeenCalledTimes(1));
    await new Promise((r) => setTimeout(r, 0));
    expect(ringDowngrade.reachOf).not.toHaveBeenCalled();
  });
});
