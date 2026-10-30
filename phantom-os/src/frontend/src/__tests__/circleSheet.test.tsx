import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { ApiError } from '../services/api';

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
import type { CircleInfo } from '../services/circlesApi';
import { CircleSheet } from '../components/messenger/CircleSheet';
import { useCirclesStore } from '../stores/circlesStore';
import { useMessengerStore } from '../stores/messengerStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Chat } from '../types/messenger';

const BUILTINS: CircleInfo[] = [
  { id: 'personal', name: null, builtin: true, count: 0, locationDefault: false, momentsDefault: false },
  { id: 'close', name: null, builtin: true, count: 1, locationDefault: true, momentsDefault: true },
  { id: 'friends', name: null, builtin: true, count: 1, locationDefault: false, momentsDefault: true },
  { id: 'social', name: null, builtin: true, count: 0, locationDefault: false, momentsDefault: false },
  { id: 'work', name: null, builtin: true, count: 0, locationDefault: false, momentsDefault: false },
];

const chat = (id: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id,
    title: 'Olena',
    type: 'dm',
    circle: 'close',
    circles: ['close', 'friends'],
    avatar: '',
    unreadCount: 0,
    pinned: false,
    messages: [],
    ...extra,
  }) as unknown as Chat;

beforeEach(() => {
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'en' } }));
  vi.mocked(circlesApi.listCircles).mockResolvedValue(BUILTINS);
  vi.mocked(circlesApi.listCirclesWithMeta).mockResolvedValue({ circles: BUILTINS, maxCustom: 8 });
  useCirclesStore.setState({ circles: BUILTINS, maxCustom: 8 });
  useMessengerStore.setState({ chats: [chat('c1')] } as never);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  useMessengerStore.setState({ chats: [] } as never);
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
});

describe('CircleSheet', () => {
  it('shows prefilled ticks from chat.circles and toggles via joinCircle / leaveCircle', async () => {
    vi.mocked(circlesApi.joinCircle).mockResolvedValueOnce(['close', 'friends', 'work']);
    vi.mocked(circlesApi.leaveCircle).mockResolvedValueOnce(['friends', 'work']);
    const onDone = vi.fn();

    render(<CircleSheet chatId="c1" open onClose={vi.fn()} onDone={onDone} />);

    const sheet = await screen.findByTestId('circle-multi-sheet');
    const closeBtn = within(sheet).getByText('Close').closest('button')!;
    const friendsBtn = within(sheet).getByText('Friends').closest('button')!;
    const workBtn = within(sheet).getByText('Work').closest('button')!;

    expect(within(closeBtn).queryByLabelText(/selected/i)).toBeTruthy();
    expect(within(friendsBtn).queryByLabelText(/selected/i)).toBeTruthy();
    expect(within(workBtn).queryByLabelText(/selected/i)).toBeNull();

    fireEvent.click(workBtn);
    await waitFor(() => expect(circlesApi.joinCircle).toHaveBeenCalledWith('work', 'c1'));
    await waitFor(() => expect(within(workBtn).queryByLabelText(/selected/i)).toBeTruthy());

    fireEvent.click(closeBtn);
    await waitFor(() => expect(circlesApi.leaveCircle).toHaveBeenCalledWith('close', 'c1'));
    await waitFor(() => expect(within(closeBtn).queryByLabelText(/selected/i)).toBeNull());

    fireEvent.click(within(sheet).getByRole('button', { name: 'Done' }));
    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it('New name -> name -> Create adds and ticks the custom row', async () => {
    const created: CircleInfo = {
      id: 'u:hikers',
      name: 'Hikers',
      builtin: false,
      count: 0,
      locationDefault: false,
      momentsDefault: false,
    };
    vi.mocked(circlesApi.createCircle).mockResolvedValueOnce(created);
    vi.mocked(circlesApi.joinCircle).mockResolvedValueOnce(['close', 'friends', 'u:hikers']);

    render(<CircleSheet chatId="c1" open onClose={vi.fn()} />);

    const sheet = await screen.findByTestId('circle-multi-sheet');
    fireEvent.click(within(sheet).getByText('New name'));

    const input = within(sheet).getByRole('textbox') as HTMLInputElement;
    expect(input.maxLength).toBe(32);
    fireEvent.change(input, { target: { value: 'Hikers' } });
    fireEvent.click(within(sheet).getByRole('button', { name: 'Create' }));

    await waitFor(() => expect(circlesApi.createCircle).toHaveBeenCalledWith('Hikers'));
    await waitFor(() => expect(circlesApi.joinCircle).toHaveBeenCalledWith('u:hikers', 'c1'));

    const hikersBtn = (await within(sheet).findByText('Hikers')).closest('button')!;
    expect(within(hikersBtn).queryByLabelText(/selected/i)).toBeTruthy();
  });

  it('shows tooMany refusal on 409 too_many_circles', async () => {
    vi.mocked(circlesApi.createCircle).mockRejectedValueOnce(new ApiError(409, 'too_many_circles', 'limit'));

    render(<CircleSheet chatId="c1" open onClose={vi.fn()} />);

    const sheet = await screen.findByTestId('circle-multi-sheet');
    fireEvent.click(within(sheet).getByText('New name'));
    fireEvent.change(within(sheet).getByRole('textbox'), { target: { value: 'Extra' } });
    fireEvent.click(within(sheet).getByRole('button', { name: 'Create' }));

    await waitFor(() => expect(within(sheet).getByText('Up to 8 of your own')).toBeTruthy());
  });

  it('hides New circle row when custom count reaches max_custom', async () => {
    const customs: CircleInfo[] = Array.from({ length: 8 }, (_, i) => ({
      id: `u:c${i}`,
      name: `Custom ${i}`,
      builtin: false,
      count: 0,
      locationDefault: false,
      momentsDefault: false,
    }));
    const all = [...BUILTINS, ...customs];
    vi.mocked(circlesApi.listCircles).mockResolvedValue(all);
    vi.mocked(circlesApi.listCirclesWithMeta).mockResolvedValue({ circles: all, maxCustom: 8 });
    useCirclesStore.setState({ circles: all, maxCustom: 8 });

    render(<CircleSheet chatId="c1" open onClose={vi.fn()} />);

    const sheet = await screen.findByTestId('circle-multi-sheet');
    expect(within(sheet).queryByText('New name')).toBeNull();
  });

  it('T50: the sheet is as tall as its list, so «Done» sits under the last row', async () => {
    render(<CircleSheet chatId="c1" open onClose={vi.fn()} onDone={vi.fn()} />);
    const sheet = await screen.findByTestId('circle-multi-sheet');
    const frame = sheet.querySelector('[data-kit="sheet-frame"]')!;
    expect(frame.className).toMatch(/max-h-\[calc\(100%-48px\)\]/);
    expect(frame.className).not.toMatch(/(^|\s)h-\[calc/);
    fireEvent.click(within(sheet).getByText('New name'));
    const create = await screen.findByTestId('circle-multi-sheet');
    expect(create.querySelector('[data-kit="sheet-frame"]')!.className).not.toMatch(/(^|\s)h-\[calc/);
  });
});
