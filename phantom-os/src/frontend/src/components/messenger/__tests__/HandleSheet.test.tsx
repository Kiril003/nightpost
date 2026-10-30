import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { HandleSheet } from '../HandleSheet';
import { MeScreen } from '../MeScreen';
import { PhoneSettingsSheet } from '../PhoneSettingsSheet';
import { messengerApi } from '../../../services/messengerApi';
import { phantomSelfApi } from '../../../services/phantomSelfApi';
import { deviceApi } from '../../../services/deviceApi';
import { ApiError } from '../../../services/api';
import { useMessengerStore } from '../../../stores/messengerStore';
import { handleRule } from '../../../services/peopleIdentity';

let prevState: ReturnType<typeof useMessengerStore.getState>;

beforeEach(() => {
  prevState = useMessengerStore.getState();
  (globalThis as { PhantomBridge?: unknown }).PhantomBridge = { call: () => '{}' };
  vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'listBlocked').mockResolvedValue({ blocked: [] });
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'privacy').mockResolvedValue({ read_receipts: true });
  vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
  vi.spyOn(deviceApi, 'list').mockResolvedValue({ stopped: false, devices: [] });
  useMessengerStore.setState({
    currentUser: { ...prevState.currentUser, id: 'me', name: 'Кирило', status: '', bio: '', avatar: '' },
    chats: [],
  });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.useRealTimers();
  delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
  useMessengerStore.setState(prevState, true);
});

describe('HandleSheet: live availability & suggestions', () => {
  it('shows field label «Нікнейм», rule hint, and «@x вільний» after 400 ms debounce when handle is free', async () => {
    vi.useFakeTimers();
    vi.spyOn(messengerApi, 'directoryMe').mockResolvedValue({ handle: null, published: false });
    const lookup = vi
      .spyOn(messengerApi, 'directoryLookup')
      .mockResolvedValue({ handle: 'kyrylo_free', available: true });

    render(<HandleSheet onDone={vi.fn()} />);

    const sheet = screen.getByTestId('handle-sheet');
    expect(sheet.querySelectorAll('button.bg-accent')).toHaveLength(1);
    const input = screen.getByLabelText('Нікнейм') as HTMLInputElement;
    expect(screen.getByTestId('handle-note').textContent).toBe(handleRule());

    fireEvent.change(input, { target: { value: 'kyrylo_free' } });
    expect(lookup).not.toHaveBeenCalled();

    await act(async () => {
      vi.advanceTimersByTime(400);
    });

    expect(lookup).toHaveBeenCalledWith('kyrylo_free');
    expect(screen.getByTestId('handle-note').textContent).toBe('@kyrylo_free вільний');
    expect(screen.queryAllByTestId('handle-suggestion')).toHaveLength(0);
  });

  it('shows «@x зайнятий» + up to 3 free chips (skipping taken ones per lookup), and tapping a chip fills the field', async () => {
    vi.useFakeTimers();
    vi.spyOn(messengerApi, 'directoryMe').mockResolvedValue({ handle: null, published: false });
    const takenSet = new Set(['kyrylo_m1', 'kyrylo_m1_1']);
    const lookup = vi
      .spyOn(messengerApi, 'directoryLookup')
      .mockImplementation(async (h: string) => ({
        handle: h,
        available: !takenSet.has(h),
        taken: takenSet.has(h),
      }));

    render(<HandleSheet onDone={vi.fn()} />);

    const input = screen.getByLabelText('Нікнейм') as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'kyrylo_m1' } });

    await act(async () => {
      vi.advanceTimersByTime(400);
    });
    await act(async () => {
      await Promise.resolve();
    });

    expect(screen.getByTestId('handle-note').textContent).toBe('@kyrylo_m1 зайнятий');

    const chips = screen.getAllByTestId('handle-suggestion');
    expect(chips.length).toBe(3);
    const chipHandles = chips.map((c) => (c.textContent ?? '').replace(/^@/, ''));
    expect(chipHandles).not.toContain('kyrylo_m1');
    expect(chipHandles).not.toContain('kyrylo_m1_1');
    for (const h of chipHandles) {
      expect(takenSet.has(h)).toBe(false);
      expect(lookup).toHaveBeenCalledWith(h);
    }
    expect(chipHandles).toContain('kyrylo_1');

    fireEvent.click(chips[0]);
    expect(input.value).toBe(chipHandles[0]);
    expect(screen.getByTestId('handle-note').textContent).toBe(`@${chipHandles[0]} вільний`);
  });

  it('lookup 404 → no availability line on type; save works and maps taken to the same 3 chips', async () => {
    vi.useFakeTimers();
    vi.spyOn(messengerApi, 'directoryMe').mockResolvedValue({ handle: null, published: false });

    vi.spyOn(messengerApi, 'directoryLookup').mockImplementation(async (h: string) => ({
      handle: h,
      available: h !== 'kyrylo_m1',
      taken: h === 'kyrylo_m1',
    }));

    const first = render(<HandleSheet onDone={vi.fn()} />);
    fireEvent.change(screen.getByLabelText('Нікнейм'), { target: { value: 'kyrylo_m1' } });
    await act(async () => {
      vi.advanceTimersByTime(400);
    });
    await act(async () => {
      await Promise.resolve();
    });
    const expectedChips = screen.getAllByTestId('handle-suggestion').map((c) => c.textContent);
    expect(expectedChips).toHaveLength(3);
    first.unmount();

    vi.spyOn(messengerApi, 'directoryLookup').mockRejectedValue(
      new ApiError(404, 'not_found', 'Not found'),
    );
    const publish = vi
      .spyOn(messengerApi, 'directoryPublish')
      .mockRejectedValueOnce(new ApiError(409, 'taken', 'Taken'))
      .mockResolvedValueOnce({ handle: 'kyrylo_m1_1' });
    const onDone = vi.fn();

    render(<HandleSheet onDone={onDone} />);
    const input = screen.getByLabelText('Нікнейм') as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'kyrylo_m1' } });

    await act(async () => {
      vi.advanceTimersByTime(400);
    });
    await act(async () => {
      await Promise.resolve();
    });

    expect(screen.getByTestId('handle-note').textContent).toBe('За цим ніком вас знайдуть');
    expect(screen.queryAllByTestId('handle-suggestion')).toHaveLength(0);

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Зберегти' }));
    });

    expect(publish).toHaveBeenCalledWith('kyrylo_m1');
    expect(screen.getByTestId('handle-note').textContent).toBe('@kyrylo_m1 зайнятий');
    const chipsOnSave = screen.getAllByTestId('handle-suggestion').map((c) => c.textContent);
    expect(chipsOnSave).toEqual(expectedChips);

    fireEvent.click(screen.getAllByTestId('handle-suggestion')[0]);
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Зберегти' }));
    });
    expect(publish).toHaveBeenCalledWith('kyrylo_m1_1');
    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it('edit during pending lookup drops stale results so old chips do not appear', async () => {
    vi.useFakeTimers();
    vi.spyOn(messengerApi, 'directoryMe').mockResolvedValue({ handle: null, published: false });

    let resolveInitial!: (v: { handle: string; available: boolean; taken?: boolean }) => void;
    let resolveSuggestionList: Array<() => void> = [];
    const lookup = vi.spyOn(messengerApi, 'directoryLookup').mockImplementation((h: string) => {
      if (h === 'kyrylo_m1') {
        return new Promise((r) => {
          resolveInitial = r;
        });
      }
      if (h === 'kyrylo_m2') {
        return Promise.resolve({ handle: h, available: false, taken: true });
      }
      return new Promise((r) => {
        resolveSuggestionList.push(() => r({ handle: h, available: true }));
      });
    });

    render(<HandleSheet onDone={vi.fn()} />);
    const input = screen.getByLabelText('Нікнейм') as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'kyrylo_m1' } });

    await act(async () => {
      vi.advanceTimersByTime(400);
    });
    expect(lookup).toHaveBeenCalledWith('kyrylo_m1');

    // 1) User edits while initial lookup for kyrylo_m1 is in flight
    fireEvent.change(input, { target: { value: 'kyrylo_m2' } });

    await act(async () => {
      resolveInitial({ handle: 'kyrylo_m1', available: false, taken: true });
      resolveSuggestionList.forEach((fn) => fn());
      resolveSuggestionList = [];
      await Promise.resolve();
    });

    expect(screen.getByTestId('handle-note').textContent).toBe('За цим ніком вас знайдуть');
    expect(screen.queryAllByTestId('handle-suggestion')).toHaveLength(0);

    // 2) Let kyrylo_m2 resolve as taken, then edit while suggestion lookups are in flight
    await act(async () => {
      vi.advanceTimersByTime(400);
    });
    expect(screen.getByTestId('handle-note').textContent).toBe('@kyrylo_m2 зайнятий');
    expect(resolveSuggestionList.length).toBeGreaterThan(0);

    fireEvent.change(input, { target: { value: 'other_draft' } });

    await act(async () => {
      resolveSuggestionList.forEach((fn) => fn());
      await Promise.resolve();
    });

    expect(screen.getByTestId('handle-note').textContent).toBe('За цим ніком вас знайдуть');
    expect(screen.queryAllByTestId('handle-suggestion')).toHaveLength(0);
  });

  it('on save-time taken: clears busy and shows taken immediately while fetching max 6 suggestions in parallel; 5xx shows no chips', async () => {
    vi.spyOn(messengerApi, 'directoryMe').mockResolvedValue({ handle: null, published: false });
    vi.spyOn(messengerApi, 'directoryPublish').mockRejectedValue(new ApiError(409, 'taken', 'Taken'));

    const pendingSuggestions: Array<() => void> = [];
    const lookup = vi.spyOn(messengerApi, 'directoryLookup').mockImplementation(
      (h: string) =>
        new Promise((resolve) => {
          pendingSuggestions.push(() => resolve({ handle: h, available: true }));
        }),
    );

    render(<HandleSheet onDone={vi.fn()} />);
    const saveBtn = screen.getByRole('button', { name: 'Зберегти' }) as HTMLButtonElement;
    // Disabled while directoryMe is loading
    expect(saveBtn.disabled).toBe(true);

    await waitFor(() => expect(messengerApi.directoryMe).toHaveBeenCalled());
    const input = screen.getByLabelText('Нікнейм') as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'kyrylo_m1' } });
    await waitFor(() => expect(saveBtn.disabled).toBe(false));

    await act(async () => {
      fireEvent.click(saveBtn);
    });

    // Immediately after publish rejects with 'taken', before suggestion lookups resolve:
    expect(saveBtn.disabled).toBe(false);
    expect(saveBtn.textContent).toBe('Зберегти');
    expect(screen.getByTestId('handle-note').textContent).toBe('@kyrylo_m1 зайнятий');
    expect(screen.queryAllByTestId('handle-suggestion')).toHaveLength(0);
    // All suggestion lookups were fired in parallel, max 6
    expect(lookup.mock.calls.length).toBeGreaterThan(0);
    expect(lookup.mock.calls.length).toBeLessThanOrEqual(6);

    await act(async () => {
      pendingSuggestions.forEach((fn) => fn());
    });
    expect(screen.getAllByTestId('handle-suggestion')).toHaveLength(3);

    // 5xx/unreachable (even with "404" in message text) must never show unchecked chips as free
    cleanup();
    vi.spyOn(messengerApi, 'directoryLookup').mockRejectedValue(
      new ApiError(503, 'unreachable', 'upstream 404 gateway error'),
    );
    render(<HandleSheet onDone={vi.fn()} />);
    await waitFor(() => expect(screen.getByLabelText('Нікнейм')).toBeTruthy());
    fireEvent.change(screen.getByLabelText('Нікнейм'), { target: { value: 'kyrylo_m1' } });
    await waitFor(() => expect((screen.getByRole('button', { name: 'Зберегти' }) as HTMLButtonElement).disabled).toBe(false));
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Зберегти' }));
    });
    expect(screen.getByTestId('handle-note').textContent).toBe('@kyrylo_m1 зайнятий');
    expect(screen.queryAllByTestId('handle-suggestion')).toHaveLength(0);
  });

  it('shows nothing for own handle (mine=true) and shows phoneOnly when directory is away', async () => {
    vi.useFakeTimers();
    vi.spyOn(messengerApi, 'directoryMe').mockResolvedValue({ handle: null, published: false });
    vi.spyOn(messengerApi, 'directoryLookup').mockResolvedValue({
      handle: 'kyrylo_m1',
      available: true,
      taken: false,
      mine: true,
    } as { handle: string; available: boolean; taken?: boolean; mine?: boolean });

    render(<HandleSheet onDone={vi.fn()} />);
    fireEvent.change(screen.getByLabelText('Нікнейм'), { target: { value: 'kyrylo_m1' } });
    await act(async () => {
      vi.advanceTimersByTime(400);
    });
    expect(screen.getByTestId('handle-note').textContent).toBe('За цим ніком вас знайдуть');
    expect(screen.queryAllByTestId('handle-suggestion')).toHaveLength(0);

    cleanup();
    vi.useRealTimers();
    vi.spyOn(messengerApi, 'directoryMe').mockRejectedValue(new ApiError(404, 'not_found', 'No route'));
    render(<HandleSheet onDone={vi.fn()} />);
    await waitFor(() => expect(screen.queryByLabelText('Нікнейм')).toBeNull());
    expect(screen.getByText('Нікнейм задається в застосунку на телефоні.')).toBeTruthy();
  });
});

describe('MeScreen: handle row under name', () => {
  it('renders no handle row while loading', async () => {
    vi.spyOn(messengerApi, 'directoryMe').mockReturnValue(new Promise(() => {}));
    await act(async () => {
      render(<MeScreen onClose={vi.fn()} onOpen={vi.fn()} />);
    });
    expect(screen.queryByTestId('me-handle')).toBeNull();
  });

  it('with no handle shows «Нікнейм — додати» under the name and opens HandleSheet on tap', async () => {
    vi.spyOn(messengerApi, 'directoryMe').mockResolvedValue({ handle: null, published: false });
    render(<MeScreen onClose={vi.fn()} onOpen={vi.fn()} />);

    const head = screen.getByTestId('me-head');
    const handleBtn = await screen.findByTestId('me-handle');
    expect(handleBtn.textContent).toBe('Нікнейм — додати');
    expect(head.nextElementSibling).toBe(handleBtn);

    fireEvent.click(handleBtn);
    expect(await screen.findByTestId('handle-sheet')).toBeTruthy();
  });

  it('with a handle shows «@handle» under the name and PhoneSettingsSheet entry still opens HandleSheet', async () => {
    vi.spyOn(messengerApi, 'directoryMe').mockResolvedValue({ handle: 'kyrylo_m1', published: true });
    render(<PhoneSettingsSheet isOpen onClose={vi.fn()} isSoundEnabled onToggleSound={vi.fn()} onExportAllData={vi.fn()} />);

    const handleBtn = await screen.findByTestId('me-handle');
    await waitFor(() => expect(handleBtn.textContent).toBe('@kyrylo_m1'));

    fireEvent.click(handleBtn);
    expect(await screen.findByTestId('handle-sheet')).toBeTruthy();
  });

  it('під полем: короткий нік чесно названо одразу, повний — «За цим ніком вас знайдуть»', async () => {
    vi.spyOn(messengerApi, 'directoryMe').mockResolvedValue({ handle: null, published: false });
    vi.spyOn(messengerApi, 'directoryLookup').mockRejectedValue(new Error('offline'));

    render(<HandleSheet onDone={vi.fn()} />);
    const input = screen.getByLabelText('Нікнейм') as HTMLInputElement;
    const note = () => screen.getByTestId('handle-note');

    fireEvent.change(input, { target: { value: 'kyr' } });
    expect(note().textContent).toBe(handleRule());
    expect(note().className).toContain('text-danger');

    fireEvent.change(input, { target: { value: 'kyrylo_m1' } });
    expect(note().textContent).toBe('За цим ніком вас знайдуть');
    expect(note().className).not.toContain('text-danger');
  });
});
