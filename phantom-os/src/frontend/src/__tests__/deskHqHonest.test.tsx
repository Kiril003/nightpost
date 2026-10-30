import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import { polisApi, forgeApi, type PolisState, type ForgeRun, type PolisCitizen } from '../services/api';
import { DeskHq, shortGoal } from '../components/shell/DeskHq';
import { translate } from '../i18n';
import { pluralKey } from '../components/chat/tNow';

const EMPTY_STATE: PolisState = {
  missions: [],
  citizens: [],
  gates: [],
  governor: { wave_size: 1, max_wave: 4, running_nodes: 0, queued_nodes: 0, night_mode: false },
};

const BASE_RUN: ForgeRun = {
  id: 'r1',
  goal: 'Перевірка симбіоту',
  project: null,
  status: 'running',
  ask: null,
  error: null,
  created_at: '2026-10-17T12:00:00Z',
  finished_at: null,
  calls: 1,
  model: 'gemini',
  waiting: null,
  tasks: 1,
  done: 0,
  proof: null,
};

function makeCitizens(count: number): PolisCitizen[] {
  return Array.from({ length: count }, (_, i) => ({
    id: `c-${i}`,
    role: 'senior_backend',
    activity: 'idle',
    mission_id: null,
    missions_done: 0,
    reliability: 1,
    tier: 'senior',
  }));
}

afterEach(() => vi.restoreAllMocks());

describe('Штаб — чесний стоп, людські кроки, множина ролей', () => {
  it('stopped run with waiting:"stopped" shows «Зупинено» and never «модель звільниться»', async () => {
    const stoppedRun: ForgeRun = { ...BASE_RUN, status: 'stopped', waiting: 'stopped' };
    vi.spyOn(polisApi, 'state').mockResolvedValue(EMPTY_STATE);
    vi.spyOn(forgeApi, 'list').mockResolvedValue({ runs: [stoppedRun] });
    vi.spyOn(forgeApi, 'get').mockResolvedValue(stoppedRun);
    render(<DeskHq />);
    const main = await screen.findByTestId('forge-run');
    expect(within(main).getByText('Зупинено')).toBeTruthy();
    expect(screen.queryByText(/модель звільниться/i)).toBeNull();
  });

  it('shows human step title and keeps raw goal hidden until details are opened', async () => {
    const rawGoal = 'Find the conversation_id for the chat named Nightpost and write the conversation_id to chat_id.txt';
    const runWithStep: ForgeRun = {
      ...BASE_RUN,
      tree: {
        calls: 1,
        model: 'gemini',
        root: {
          id: 't1',
          goal: rawGoal,
          title: 'Знайти чат',
          status: 'running',
          depth: 0,
          attempts: 1,
          failures: [],
          proof: null,
          from_recipe: false,
          check_by_model: false,
          children: [],
        },
      },
    };
    vi.spyOn(polisApi, 'state').mockResolvedValue(EMPTY_STATE);
    vi.spyOn(forgeApi, 'list').mockResolvedValue({ runs: [runWithStep] });
    vi.spyOn(forgeApi, 'get').mockResolvedValue(runWithStep);
    render(<DeskHq />);
    const progress = await screen.findByLabelText('Хід роботи');
    expect(within(progress).getByText('Знайти чат')).toBeTruthy();
    expect(within(progress).queryByText(/chat_id\.txt/)).toBeNull();

    fireEvent.click(within(progress).getByText('Деталі'));
    expect(await within(progress).findByText(/chat_id\.txt/)).toBeTruthy();
    expect(shortGoal(rawGoal)).not.toContain('chat_id.txt');
    expect(shortGoal(rawGoal)).not.toContain('conversation_id');
  });

  it('uk rolesReady pluralizes 23 → «23 ролі», 25 → «25 ролей», 21 → «21 роль»', async () => {
    expect(translate(pluralKey('uk', 23, 'deskHq.rolesReady'), 'uk', { n: 23, max: 4 })).toContain('23 ролі');
    expect(translate(pluralKey('uk', 25, 'deskHq.rolesReady'), 'uk', { n: 25, max: 4 })).toContain('25 ролей');
    expect(translate(pluralKey('uk', 21, 'deskHq.rolesReady'), 'uk', { n: 21, max: 4 })).toContain('21 роль');

    vi.spyOn(polisApi, 'state').mockResolvedValue({
      ...EMPTY_STATE,
      citizens: makeCitizens(23),
    });
    vi.spyOn(forgeApi, 'list').mockResolvedValue({ runs: [] });
    render(<DeskHq />);
    const gov = await screen.findByTestId('hq-governor');
    expect(gov.textContent).toContain('23 ролі');
    expect(gov.textContent).not.toContain('23 ролей');
  });
});
