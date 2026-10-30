import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { ApiError, polisApi, forgeApi, type PolisState, type ForgeRun } from '../services/api';
import { DeskHq } from '../components/shell/DeskHq';

const EMPTY_STATE: PolisState = {
  missions: [],
  citizens: [],
  gates: [],
  governor: { wave_size: 1, max_wave: 4, running_nodes: 0, queued_nodes: 0, night_mode: false },
};

const ASKING_RUN: ForgeRun = {
  id: 'r1',
  goal: 'Перевірка симбіоту',
  project: null,
  status: 'asking',
  ask: { id: 'a1', tool: 'messenger.send', args: { text: 'Привіт' }, risk: 'outward', at: '2026-10-16T12:00:00Z' },
  error: null,
  created_at: '2026-10-16T12:00:00Z',
  finished_at: null,
  calls: 1,
  model: 'gemini',
  waiting: null,
  tasks: 1,
  done: 0,
  proof: null,
};

afterEach(() => vi.restoreAllMocks());

describe('Штаб — запит дозволу від Кузні', () => {
  it('shows the open question with Так/Ні and no "nothing to decide"', async () => {
    vi.spyOn(polisApi, 'state').mockResolvedValue(EMPTY_STATE);
    vi.spyOn(forgeApi, 'list').mockResolvedValue({ runs: [ASKING_RUN] });
    vi.spyOn(forgeApi, 'get').mockResolvedValue(ASKING_RUN);
    render(<DeskHq />);
    const gates = await screen.findByTestId('hq-gates');
    expect(await within(gates).findByText('Надіслати повідомлення')).toBeTruthy();
    expect(within(gates).getByText('Привіт')).toBeTruthy();
    expect(within(gates).getByRole('button', { name: 'Так' })).toBeTruthy();
    expect(within(gates).getByRole('button', { name: 'Ні' })).toBeTruthy();
    expect(within(gates).queryByText('Рішень від вас зараз не треба.')).toBeNull();
  });

  it('Так answers yes once', async () => {
    vi.spyOn(polisApi, 'state').mockResolvedValue(EMPTY_STATE);
    vi.spyOn(forgeApi, 'list').mockResolvedValue({ runs: [ASKING_RUN] });
    vi.spyOn(forgeApi, 'get').mockResolvedValue(ASKING_RUN);
    const answer = vi.spyOn(forgeApi, 'answer').mockResolvedValue(ASKING_RUN);
    render(<DeskHq />);
    const gates = await screen.findByTestId('hq-gates');
    fireEvent.click(await within(gates).findByRole('button', { name: 'Так' }));
    await waitFor(() => expect(answer).toHaveBeenCalledTimes(1));
    expect(answer).toHaveBeenCalledWith('r1', true);
  });

  it('asking status is a word, not a code', async () => {
    vi.spyOn(polisApi, 'state').mockResolvedValue(EMPTY_STATE);
    vi.spyOn(forgeApi, 'list').mockResolvedValue({ runs: [ASKING_RUN] });
    vi.spyOn(forgeApi, 'get').mockResolvedValue(ASKING_RUN);
    render(<DeskHq />);
    const list = await screen.findByLabelText('Доручення');
    expect(await within(list).findByText('чекає на вас')).toBeTruthy();
    expect(screen.queryByText('asking')).toBeNull();
  });

  it('409 while stopped reloads instead of failing', async () => {
    vi.spyOn(polisApi, 'state').mockResolvedValue(EMPTY_STATE);
    const list = vi.spyOn(forgeApi, 'list').mockResolvedValue({ runs: [ASKING_RUN] });
    vi.spyOn(forgeApi, 'get').mockResolvedValue(ASKING_RUN);
    vi.spyOn(forgeApi, 'answer').mockRejectedValue(new ApiError(409, 'CONFLICT', 'stopped'));
    render(<DeskHq />);
    const gates = await screen.findByTestId('hq-gates');
    const yes = await within(gates).findByRole('button', { name: 'Так' });
    const before = list.mock.calls.length;
    fireEvent.click(yes);
    await waitFor(() => expect(list.mock.calls.length).toBeGreaterThan(before));
  });

  it('no asks keeps the empty line', async () => {
    const runningRun: ForgeRun = { ...ASKING_RUN, status: 'running', ask: null };
    vi.spyOn(polisApi, 'state').mockResolvedValue(EMPTY_STATE);
    vi.spyOn(forgeApi, 'list').mockResolvedValue({ runs: [runningRun] });
    vi.spyOn(forgeApi, 'get').mockResolvedValue(runningRun);
    render(<DeskHq />);
    await screen.findByLabelText('Доручення');
    const gates = await screen.findByTestId('hq-gates');
    expect(within(gates).getByText('Рішень від вас зараз не треба.')).toBeTruthy();
  });
});
