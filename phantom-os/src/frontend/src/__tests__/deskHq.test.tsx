/**
 * Штаб (27.09): організація агентів «Полісу» під наглядом людини — доручення,
 * хід роботи з ролями й витратами, ворота «Так / Ні», команда.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { polisApi, forgeApi, type PolisState, type PolisNode, type ForgeRun, type ForgeTask } from '../services/api';
import { DeskHq, inOrder, spent, countTree } from '../components/shell/DeskHq';

const node = (id: string, title: string, status: string, deps: string[], roles: string[], calls = 0): PolisNode => ({
  id, title, status, kind: 'workstream', depends_on: deps, crew: { roles, size: 1, lead: null }, gate_kind: null,
  budget: { max_tokens: 1000, max_llm_calls: 25, spent_tokens: calls * 900, spent_llm_calls: calls }, attempts: 1,
  output_summary: `звіт ${title}`,
});

const STATE: PolisState = {
  missions: [
    {
      id: 'm1', title: 'Як зберігати каву', brief: 'Порівняй способи.', pipeline: 'research_library', domain: 'research',
      status: 'running', progress: 0.5, created_at: '2026-09-27T12:00:00Z',
      nodes: [
        node('syn', 'Синтез', 'pending', ['r1', 'r2'], ['team_lead_research']),
        node('r1', 'Потік 1', 'done', ['map'], ['domain_researcher'], 1),
        node('map', 'Мапа дослідження', 'done', [], ['team_lead_research'], 2),
        node('r2', 'Потік 2', 'running', ['map'], ['domain_researcher'], 1),
      ],
    },
  ],
  citizens: [{ id: 'domain_researcher', role: 'domain_researcher', activity: 'working', mission_id: 'm1', missions_done: 3, reliability: 1, tier: 'майстер' }],
  gates: [{ id: 'g1', mission_id: 'm1', node_id: 'syn', kind: 'operator', question: 'Публікувати звіт?', payload_preview: 'Короткий зміст' }],
  governor: { wave_size: 1, max_wave: 4, running_nodes: 1, queued_nodes: 1, night_mode: false },
};

afterEach(() => vi.restoreAllMocks());

describe('Штаб', () => {
  it('роботи — у порядку залежностей; витрати — сума по агентах', () => {
    expect(inOrder(STATE.missions[0].nodes).map((n) => n.id)).toEqual(['map', 'r1', 'r2', 'syn']);
    expect(spent(STATE.missions[0])).toEqual({ calls: 4, tokens: 3600 });
  });

  it('показує хід роботи людською мовою: ролі, стан, витрати', async () => {
    vi.spyOn(polisApi, 'state').mockResolvedValue(STATE);
    render(<DeskHq />);
    const steps = await screen.findByRole('list', { name: 'Хід роботи' });
    expect(within(steps).getAllByText(/Дослідник · готово/).length).toBe(1);
    expect(within(steps).getByText(/Керівник досліджень · у черзі/)).toBeTruthy();
    expect(screen.getByTestId('hq-cost').textContent).toMatch(/4 роботи · 4 звернення до моделі · 3.6 тис. токенів/);
    expect(screen.getByTestId('hq-governor').textContent).toMatch(/Зараз працюють 1 агент з 4/);
  });

  it('ворота: людина вирішує «Так»', async () => {
    vi.spyOn(polisApi, 'state').mockResolvedValue(STATE);
    const resolve = vi.spyOn(polisApi, 'resolveGate').mockResolvedValue({ ok: true });
    render(<DeskHq />);
    const gates = await screen.findByTestId('hq-gates');
    expect(within(gates).getByText('Публікувати звіт?')).toBeTruthy();
    fireEvent.click(within(gates).getByRole('button', { name: 'Так' }));
    await waitFor(() => expect(resolve).toHaveBeenCalledWith('g1', true));
  });

  it('одне поле «Що треба?» — без вибору напрямку, доручення йде в Кузню', async () => {
    vi.spyOn(polisApi, 'state').mockResolvedValue({ ...STATE, missions: [], gates: [] });
    vi.spyOn(forgeApi, 'list').mockResolvedValue({ runs: [] });
    const start = vi.spyOn(forgeApi, 'start').mockResolvedValue({ id: 'r2' });
    vi.spyOn(forgeApi, 'get').mockRejectedValue(new Error('ще нема'));
    render(<DeskHq />);
    fireEvent.change(await screen.findByLabelText('Що треба?'), { target: { value: 'Сезон серіалу про Київ' } });
    expect(screen.queryAllByRole('radio')).toHaveLength(0);
    fireEvent.click(screen.getByRole('button', { name: 'Доручити' }));
    await waitFor(() => expect(start).toHaveBeenCalledWith('Сезон серіалу про Київ'));
  });
});

describe('Штаб — агент питає людину', () => {
  it('питання агента видно, «Дозволити» йде в його завдання', async () => {
    const { agentAskApi } = await import('../services/api');
    vi.spyOn(polisApi, 'state').mockResolvedValue({ ...STATE, gates: [] });
    vi.spyOn(agentAskApi, 'waiting').mockResolvedValue({
      questions: [{ task_id: 't1', goal: 'Створи файл', question: 'Дозволити теку ~/phantom-test?', kind: 'consent', asked_at: '' }],
    });
    const answer = vi.spyOn(agentAskApi, 'answer').mockResolvedValue({ queued: true });
    render(<DeskHq />);
    const q = await screen.findByTestId('hq-agent-question');
    expect(within(q).getByText('Дозволити теку ~/phantom-test?')).toBeTruthy();
    fireEvent.click(within(q).getByRole('button', { name: 'Дозволити' }));
    await waitFor(() => expect(answer).toHaveBeenCalledWith('t1', 'approve'));
  });
});

const task = (id: string, goal: string, status: ForgeTask['status'], extra: Partial<ForgeTask> = {}): ForgeTask => ({
  id, goal, status, depth: 0, attempts: 1, failures: [], proof: null, from_recipe: false, check_by_model: false, children: [], ...extra,
});

const RUN: ForgeRun = {
  id: 'r1', goal: 'Гра «Життя» з тестами', project: null, status: 'running', error: null,
  created_at: '2026-09-28T12:00:00Z', finished_at: null, calls: 3, model: 'gemini', waiting: 'модель перевантажена — чекаю',
  tasks: 3, done: 1, proof: null,
  tree: {
    calls: 3, model: 'gemini',
    root: task('root', 'Гра «Життя» з тестами', 'split', {
      children: [
        task('a', 'life.py', 'done', { attempts: 2, proof: { ok: true, strength: 'executed', detail: '`python3 -m unittest` → 0' } }),
        task('b', 'Опис гри', 'done', { proof: { ok: true, strength: 'judged', detail: 'зрозуміло' }, check_by_model: true }),
        task('c', 'Рендер поля', 'running'),
      ],
    }),
  },
  files: [{ path: 'life.py', size: 862 }],
};

describe('Штаб — Кузня', () => {
  it('рахує лише доведені кроки', () => {
    expect(countTree(RUN.tree?.root ?? null)).toEqual({ all: 4, proven: 2 });
  });

  it('показує дерево робіт і чим доведено кожен крок, чесно про слабкі докази й паузу', async () => {
    vi.spyOn(polisApi, 'state').mockResolvedValue({ ...STATE, missions: [], gates: [] });
    vi.spyOn(forgeApi, 'list').mockResolvedValue({ runs: [RUN] });
    vi.spyOn(forgeApi, 'get').mockResolvedValue(RUN);
    render(<DeskHq />);
    const view = await screen.findByTestId('forge-run');
    expect(screen.getByTestId('forge-meta').textContent).toMatch(/працює · 2 з 4 кроків доведено · 3 звернення до моделі/);
    expect(within(view).getByText(/доведено запуском · 2 спроби/)).toBeTruthy();
    expect(within(view).getByText(/оцінено моделлю — слабкий доказ \(перевірку склала модель\)/)).toBeTruthy();
    expect(within(view).getByText(/Модель перевантажена — чекаю — продовжу сам/)).toBeTruthy();
    expect(within(view).getByText('life.py', { selector: 'li' , exact: false })).toBeTruthy();
    fireEvent.click(within(view).getByText('life', { selector: 'span' }));
    expect(await within(view).findByText(/Доказ: `python3 -m unittest` → 0/)).toBeTruthy();
  });

  const summary = (r: ForgeRun) => {
    const { tree: _t, files: _f, ...s } = r;
    return s;
  };

  it('Пауза → статус «на паузі» з відповіді вузла; «Продовжити» повертає в роботу', async () => {
    const paused: ForgeRun = { ...RUN, status: 'paused', waiting: null };
    vi.spyOn(polisApi, 'state').mockResolvedValue({ ...STATE, missions: [], gates: [] });
    vi.spyOn(forgeApi, 'list').mockResolvedValue({ runs: [RUN] });
    const get = vi.spyOn(forgeApi, 'get').mockResolvedValue(RUN);
    const pause = vi.spyOn(forgeApi, 'pause').mockImplementation(async () => {
      get.mockResolvedValue(paused);
      return summary(paused);
    });
    const resume = vi.spyOn(forgeApi, 'resume').mockResolvedValue(summary(RUN));
    render(<DeskHq />);
    const view = await screen.findByTestId('forge-run');
    expect(within(view).queryByRole('button', { name: 'Продовжити' })).toBeNull();
    fireEvent.click(within(view).getByRole('button', { name: 'Пауза' }));
    await waitFor(() => expect(pause).toHaveBeenCalledWith('r1'));
    await waitFor(() => expect(screen.getByTestId('forge-meta').textContent).toMatch(/^на паузі · 2 з 4 кроків доведено/));
    expect(within(view).getByText(/На паузі: нових звернень до моделі нема/)).toBeTruthy();
    // Під час паузи рядок «модель перевантажена — чекаю» не показується.
    expect(within(view).queryByText(/продовжу сам/)).toBeNull();
    fireEvent.click(within(view).getByRole('button', { name: 'Продовжити' }));
    await waitFor(() => expect(resume).toHaveBeenCalledWith('r1'));
  });

  it('Скасувати: статус «скасовано», кнопок керування більше нема; відмову вузла видно', async () => {
    vi.spyOn(polisApi, 'state').mockResolvedValue({ ...STATE, missions: [], gates: [] });
    vi.spyOn(forgeApi, 'list').mockResolvedValue({ runs: [RUN] });
    vi.spyOn(forgeApi, 'get').mockResolvedValue(RUN);
    const cancel = vi.spyOn(forgeApi, 'cancel').mockRejectedValueOnce(new Error('прогін уже завершено (стан done)'));
    render(<DeskHq />);
    const view = await screen.findByTestId('forge-run');
    fireEvent.click(within(view).getByRole('button', { name: 'Скасувати' }));
    expect(await within(view).findByRole('alert')).toBeTruthy();
    expect(within(view).getByRole('alert').textContent).toMatch(/прогін уже завершено/);

    const cancelled: ForgeRun = { ...RUN, status: 'cancelled', waiting: null, finished_at: '2026-09-28T13:00:00Z' };
    cancel.mockResolvedValueOnce(summary(cancelled));
    vi.spyOn(forgeApi, 'get').mockResolvedValue(cancelled);
    fireEvent.click(within(view).getByRole('button', { name: 'Скасувати' }));
    await waitFor(() => expect(screen.getByTestId('forge-meta').textContent).toMatch(/^скасовано ·/));
    expect(within(view).queryByRole('button', { name: 'Пауза' })).toBeNull();
    expect(within(view).queryByRole('button', { name: 'Скасувати' })).toBeNull();
  });

  it('пошкоджений запис прогону — чесний стан, а не падіння', async () => {
    const broken = { id: 'r9', goal: '', project: null, status: 'corrupt', error: 'run.json не читається: [1, 2]',
      created_at: '2026-09-28T12:00:00Z', finished_at: null, calls: 0, model: '', waiting: null, tasks: 0, done: 0,
      proof: null } as ForgeRun;
    vi.spyOn(polisApi, 'state').mockResolvedValue({ ...STATE, missions: [], gates: [] });
    vi.spyOn(forgeApi, 'list').mockResolvedValue({ runs: [broken] });
    vi.spyOn(forgeApi, 'get').mockResolvedValue(broken);
    render(<DeskHq />);
    const view = await screen.findByTestId('forge-run');
    expect(screen.getByTestId('forge-meta').textContent).toMatch(/^запис прогону пошкоджено/);
    expect(within(view).getByText(/run.json не читається/)).toBeTruthy();
    expect(within(view).queryByRole('button')).toBeNull();
  });
});
