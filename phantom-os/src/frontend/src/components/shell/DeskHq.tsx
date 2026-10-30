/**
 * Штаб — PHANTOM як організація: доручення → команда агентів із ролями →
 * результат, а людина наглядає й вирішує там, де без неї не можна.
 *
 * Уся «організація» живе у вузлі («Поліс», agent/fabric): місія
 * розкладається на граф робіт, у кожної — команда з ролей (23 ролі в 5
 * відділах), бюджет запитів і токенів, «ворота», що чекають на людину.
 * Перевірено наживо 27.09 з ключем власника: 6 робіт, 15 запитів до моделі,
 * звіти на 4–6 КБ. Екрана до того не було — «місто» сховали з доку, бо
 * воно перекривало навігацію й нічого не пояснювало людині.
 *
 * 28.09: нове доручення йде в Кузню (agent/forge) — одне поле «Що треба?»,
 * без вибору «напрямку» (власник: «наскільки цей штаб тупорилий, що треба ще
 * обирати напрям»). Кузня сама дробить роботу під силу моделі й показує, чим
 * доведено кожен крок. Місії «Поліса» лишаються в списку як були.
 */
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { CheckCircle2, Circle, CircleDot, CirclePause, CircleX, GitBranch, Hourglass, Users } from 'lucide-react';
import {
  polisApi,
  agentAskApi,
  forgeApi,
  ApiError,
  type AgentQuestion,
  type ForgeRun,
  type ForgeRunSummary,
  type ForgeTask,
  type PolisMission,
  type PolisNode,
  type PolisState,
} from '../../services/api';
import { currentLocale, pluralKey, tNow, tPlural } from '../chat/tNow';
const ROLE: Record<string, string> = {
  get team_lead_engineering() { return tNow('deskHq.role.team_lead_engineering'); },
  get team_lead_product() { return tNow('deskHq.role.team_lead_product'); },
  get team_lead_qa() { return tNow('deskHq.role.team_lead_qa'); },
  get team_lead_research() { return tNow('deskHq.role.team_lead_research'); },
  get team_lead_operations() { return tNow('deskHq.role.team_lead_operations'); },
  get senior_architect() { return tNow('deskHq.role.senior_architect'); },
  get senior_backend() { return tNow('deskHq.role.senior_backend'); },
  get senior_frontend() { return tNow('deskHq.role.senior_frontend'); },
  get senior_security() { return tNow('deskHq.role.senior_security'); },
  get senior_devops() { return tNow('deskHq.role.senior_devops'); },
  get senior_perf() { return tNow('deskHq.role.senior_perf'); },
  get product_manager() { return tNow('deskHq.role.product_manager'); },
  get ux_researcher() { return tNow('deskHq.role.ux_researcher'); },
  get designer() { return tNow('deskHq.role.designer'); },
  get senior_test() { return tNow('deskHq.role.senior_test'); },
  get pen_tester() { return tNow('deskHq.role.pen_tester'); },
  get manual_qa() { return tNow('deskHq.role.manual_qa'); },
  get domain_researcher() { return tNow('deskHq.role.domain_researcher'); },
  get data_analyst() { return tNow('deskHq.role.data_analyst'); },
  get osint() { return tNow('deskHq.role.osint'); },
  get incident_responder() { return tNow('deskHq.role.incident_responder'); },
  get documentation_writer() { return tNow('deskHq.role.documentation_writer'); },
  get translator() { return tNow('deskHq.role.translator'); },
};

export const roleName = (r: string): string => ROLE[r] ?? r.replace(/_/g, ' ');

const NODE_STATUS: Record<string, { word: string; icon: React.ReactNode }> = {
  pending: { get word() { return tNow('deskHq.st.queued'); }, icon: <Circle className="w-4 h-4 text-f-ink-3" strokeWidth={1.75} /> },
  ready: { get word() { return tNow('deskHq.st.queued'); }, icon: <Circle className="w-4 h-4 text-f-ink-3" strokeWidth={1.75} /> },
  running: { get word() { return tNow('deskHq.st.running'); }, icon: <CircleDot className="w-4 h-4 text-f-accent animate-pulse" strokeWidth={1.75} /> },
  review: { get word() { return tNow('deskHq.st.review'); }, icon: <CircleDot className="w-4 h-4 text-f-accent" strokeWidth={1.75} /> },
  awaiting_gate: { get word() { return tNow('deskHq.st.awaitingGate'); }, icon: <Hourglass className="w-4 h-4 text-f-accent" strokeWidth={1.75} /> },
  done: { get word() { return tNow('deskHq.st.done'); }, icon: <CheckCircle2 className="w-4 h-4 text-f-live" strokeWidth={1.75} /> },
  skipped: { get word() { return tNow('deskHq.st.skipped'); }, icon: <Circle className="w-4 h-4 text-f-ink-3" strokeWidth={1.75} /> },
  failed: { get word() { return tNow('deskHq.st.failed'); }, icon: <CircleX className="w-4 h-4 text-f-danger" strokeWidth={1.75} /> },
  paused: { get word() { return tNow('deskHq.st.paused'); }, icon: <CirclePause className="w-4 h-4 text-f-ink-2" strokeWidth={1.75} /> },
  killed: { get word() { return tNow('deskHq.st.killed'); }, icon: <CircleX className="w-4 h-4 text-f-ink-3" strokeWidth={1.75} /> },
};

const MISSION_STATUS: Record<string, string> = {
  get running() { return tNow('deskHq.st.running'); },
  get done() { return tNow('deskHq.st.done'); },
  get failed() { return tNow('deskHq.st.failed'); },
  get paused() { return tNow('deskHq.st.paused'); },
  get killed() { return tNow('deskHq.st.killed'); },
  get pending() { return tNow('deskHq.st.queued'); },
};

/** Роботи в порядку виконання: спершу ті, від яких залежать інші. */
export function inOrder(nodes: PolisNode[]): PolisNode[] {
  const byId = new Map(nodes.map((n) => [n.id, n]));
  const out: PolisNode[] = [];
  const seen = new Set<string>();
  const visit = (n: PolisNode) => {
    if (seen.has(n.id)) return;
    seen.add(n.id);
    for (const d of n.depends_on) {
      const dep = byId.get(d);
      if (dep) visit(dep);
    }
    out.push(n);
  };
  nodes.forEach(visit);
  return out;
}

export function spent(m: PolisMission): { calls: number; tokens: number } {
  return m.nodes.reduce(
    (a, n) => ({ calls: a.calls + (n.budget?.spent_llm_calls ?? 0), tokens: a.tokens + (n.budget?.spent_tokens ?? 0) }),
    { calls: 0, tokens: 0 },
  );
}

const thousands = (n: number) => (n >= 1000 ? tNow('deskHq.thousands', { n: (n / 1000).toFixed(n >= 10000 ? 0 : 1) }) : String(n));

type Pick = { kind: 'forge' | 'polis'; id: string } | null;

export function DeskHq() {
  const [state, setState] = useState<PolisState | null>(null);
  const [runs, setRuns] = useState<ForgeRunSummary[] | null>(null);
  const [selected, setSelected] = useState<Pick>(null);
  const [questions, setQuestions] = useState<AgentQuestion[]>([]);

  const load = useCallback(() => {
    agentAskApi
      .waiting()
      .then((r) => setQuestions(r.questions))
      .catch(() => setQuestions([]));
    forgeApi
      .list()
      .then((r) => setRuns(r.runs))
      .catch(() => setRuns((prev) => prev ?? []));
    polisApi
      .state()
      .then(setState)
      .catch(() => setState((prev) => prev));
  }, []);

  useEffect(() => {
    load();
    const id = setInterval(load, 3000);
    return () => clearInterval(id);
  }, [load]);

  const missions = useMemo(
    () => [...(state?.missions ?? [])].sort((a, b) => (b.created_at ?? '').localeCompare(a.created_at ?? '')),
    [state],
  );
  const pick: Pick =
    selected ?? (runs && runs[0] ? { kind: 'forge', id: runs[0].id } : missions[0] ? { kind: 'polis', id: missions[0].id } : null);
  const mission = pick?.kind === 'polis' ? missions.find((m) => m.id === pick.id) ?? null : null;

  if (runs === null && !state) {
    return <div className="h-full flex items-center justify-center text-f-read text-f-ink-2">{tNow('deskHq.opening')}</div>;
  }

  return (
    <div className="h-full min-h-0 flex gap-4 px-4 pb-4 pt-2">
      <aside className="w-[300px] shrink-0 min-h-0 flex flex-col gap-3">
        <NewWork onCreated={(id) => { setSelected({ kind: 'forge', id }); load(); }} />
        <div className="flex-1 min-h-0 overflow-y-auto no-scrollbar rounded-f-sheet bg-f-surface p-2" aria-label={tNow('deskHq.assignments')}>
          {(runs ?? []).length === 0 && missions.length === 0 ? (
            <p className="p-3 text-f-body text-f-ink-3">{tNow('deskHq.noTasks')}</p>
          ) : (
            <>
              {(runs ?? []).map((r) => (
                <button
                  key={r.id}
                  type="button"
                  onClick={() => setSelected({ kind: 'forge', id: r.id })}
                  aria-current={pick?.kind === 'forge' && pick.id === r.id}
                  className={`w-full text-left rounded-f-m px-3 py-2.5 min-h-11 ${pick?.kind === 'forge' && pick.id === r.id ? 'bg-f-raise' : 'hover:bg-f-raise/60'}`}
                >
                  <p className="text-f-read text-f-ink truncate">{r.goal}</p>
                  <p className="mt-1 text-f-meta text-f-ink-3">
                    {RUN_STATUS[r.status] ?? r.status}
                    {r.tasks > 1 ? ` · ${tNow('deskHq.stepsProven', { done: r.done, total: r.tasks })}` : ''}
                  </p>
                </button>
              ))}
              {missions.length > 0 && <p className="px-3 pt-3 pb-1 text-f-label text-f-ink-3">{tNow('deskHq.teamMissions')}</p>}
              {missions.map((m) => (
                <button
                  key={m.id}
                  type="button"
                  onClick={() => setSelected({ kind: 'polis', id: m.id })}
                  aria-current={pick?.kind === 'polis' && pick.id === m.id}
                  className={`w-full text-left rounded-f-m px-3 py-2.5 min-h-11 ${pick?.kind === 'polis' && pick.id === m.id ? 'bg-f-raise' : 'hover:bg-f-raise/60'}`}
                >
                  <p className="text-f-read text-f-ink truncate">{m.title}</p>
                  <div className="mt-1.5 flex items-center gap-2">
                    <div className="flex-1 h-1 rounded-full bg-f-raise overflow-hidden">
                      <div className="h-full bg-f-accent" style={{ width: `${Math.round((m.progress ?? 0) * 100)}%` }} />
                    </div>
                    <span className="text-f-meta text-f-ink-3">{MISSION_STATUS[m.status] ?? m.status}</span>
                  </div>
                </button>
              ))}
            </>
          )}
        </div>
      </aside>

      <main className="flex-1 min-w-0 min-h-0 overflow-y-auto no-scrollbar rounded-f-sheet bg-f-surface p-5" aria-label={tNow('deskHq.assignmentDetail')}>
        {pick?.kind === 'forge' ? <ForgeView id={pick.id} /> : mission ? <MissionView mission={mission} onChanged={load} /> : <Intro />}
      </main>

      <aside className="w-[320px] shrink-0 min-h-0 flex flex-col gap-3">
        <Waiting state={state} runs={runs} questions={questions} onDecided={load} />
        {state && <Team state={state} />}
      </aside>
    </div>
  );
}

const RUN_STATUS: Record<string, string> = {
  get running() { return tNow('deskHq.st.running'); },
  get paused() { return tNow('deskHq.st.paused'); },
  get asking() { return tNow('deskHq.st.awaitingGate'); },
  get stopped() { return tNow('deskHq.st.killed'); },
  get cancelled() { return tNow('deskHq.st.cancelled'); },
  get declined() { return tNow('deskHq.st.declined'); },
  get done() { return tNow('deskHq.st.doneProven'); },
  get failed() { return tNow('deskHq.st.failed'); },
  get interrupted() { return tNow('deskHq.st.interrupted'); },
  get corrupt() { return tNow('deskHq.st.corrupt'); },
};

const PROOF: Record<string, string> = {
  get executed() { return tNow('deskHq.proof.executed'); },
  get canon() { return tNow('deskHq.proof.canon'); },
  get judged() { return tNow('deskHq.proof.judged'); },
  get none() { return tNow('deskHq.proof.none'); },
};

const TASK_STATUS: Record<string, { word: string; icon: React.ReactNode }> = {
  pending: NODE_STATUS.pending,
  running: NODE_STATUS.running,
  done: NODE_STATUS.done,
  failed: NODE_STATUS.failed,
  split: { get word() { return tNow('deskHq.st.split'); }, icon: <GitBranch className="w-4 h-4 text-f-accent" strokeWidth={1.75} /> },
};

export function shortGoal(goal: string): string {
  const cleaned = goal
    .split(/\s+/)
    .map((tok) => {
      const core = tok.replace(/^[([{'"«„]+|[)\]}'"»“.,;:!?]+$/g, '');
      if (core.includes('_') || /\S\.\S/.test(core)) {
        return /[.:]$/.test(tok) ? '.' : '';
      }
      return tok;
    })
    .filter(Boolean)
    .join(' ');
  const head = (cleaned || goal).split(/[:.]/)[0]?.replace(/\s+/g, ' ').trim() ?? '';
  return (head || goal.slice(0, 60).trim()).slice(0, 60).trim();
}

/** Скільки кроків у дереві й скільки з них доведено. */
export function countTree(t: ForgeTask | null): { all: number; proven: number } {
  if (!t) return { all: 0, proven: 0 };
  return t.children.reduce(
    (a, c) => {
      const x = countTree(c);
      return { all: a.all + x.all, proven: a.proven + x.proven };
    },
    { all: 1, proven: t.status === 'done' && t.proof?.ok ? 1 : 0 },
  );
}

function ForgeView({ id }: { id: string }) {
  const [run, setRun] = useState<ForgeRun | null>(null);
  const [gone, setGone] = useState(false);
  const [busy, setBusy] = useState(false);
  const [refused, setRefused] = useState<string | null>(null);
  const tick = useCallback(
    (alive: () => boolean = () => true) =>
      forgeApi
        .get(id)
        .then((r) => alive() && setRun(r))
        .catch(() => alive() && setGone(true)),
    [id],
  );
  useEffect(() => {
    let alive = true;
    setRun(null);
    setGone(false);
    setRefused(null);
    const poll = () => void tick(() => alive);
    poll();
    const t = setInterval(poll, 2500);
    return () => {
      alive = false;
      clearInterval(t);
    };
  }, [tick]);
  // Статус після дії — лише з відповіді вузла, не вгаданий наперед.
  const control = (act: 'pause' | 'resume' | 'cancel') => {
    setBusy(true);
    setRefused(null);
    forgeApi[act](id)
      .then((s) => setRun((prev) => (prev ? { ...prev, ...s } : prev)))
      .catch((e: unknown) => setRefused(e instanceof Error ? e.message : String(e)))
      .finally(() => {
        setBusy(false);
        void tick();
      });
  };
  if (gone && !run) return <p className="text-f-read text-f-ink-2">{tNow('deskHq.unknownTask')}</p>;
  if (!run) return <p className="text-f-read text-f-ink-2">{tNow('deskHq.opening2')}</p>;
  const n = countTree(run.tree?.root ?? null);
  const live = run.status === 'running' || run.status === 'paused';
  return (
    <div data-testid="forge-run">
      <div className="flex items-start gap-4">
        <h1 className="flex-1 min-w-0 font-sans text-f-title text-f-ink">{run.goal}</h1>
        {live && (
          <div className="shrink-0 flex gap-2">
            {run.status === 'running' ? (
              <button type="button" disabled={busy} onClick={() => control('pause')} className="min-h-11 px-4 rounded-full bg-f-raise text-f-body text-f-ink disabled:opacity-50">
                {tNow('deskHq.pause')}
              </button>
            ) : (
              <button type="button" disabled={busy} onClick={() => control('resume')} className="min-h-11 px-4 rounded-full bg-f-raise text-f-body text-f-ink disabled:opacity-50">
                {tNow('deskHq.resume')}
              </button>
            )}
            <button type="button" disabled={busy} onClick={() => control('cancel')} className="min-h-11 px-4 rounded-full text-f-body text-f-ink-3 hover:bg-f-raise disabled:opacity-50">
              {tNow('deskHq.cancel')}
            </button>
          </div>
        )}
      </div>
      {run.status === 'paused' && (
        <p className="mt-3 rounded-f-m bg-f-raise px-3 py-2 text-f-body text-f-ink-2" role="status">
          {tNow('deskHq.pausedNote')}
        </p>
      )}
      {refused && <p className="mt-3 text-f-body text-f-danger" role="alert">{tNow('deskHq.refused', { reason: refused })}</p>}
      <p className="mt-2 text-f-meta text-f-ink-3" data-testid="forge-meta">
        {RUN_STATUS[run.status] ?? run.status} · {tNow('deskHq.provenOf', { proven: n.proven, all: n.all, steps: tPlural(n.all, 'deskHq.stepsGen') })} ·{' '}
        {tNow('deskHq.modelCalls', { n: run.calls, calls: tPlural(run.calls, 'deskHq.calls') })}
        {run.project ? ` · ${tNow('deskHq.project', { name: run.project })}` : ''}
      </p>
      {(run.status === 'stopped' || run.waiting) && (
        <p className="mt-3 rounded-f-m bg-f-raise px-3 py-2 text-f-body text-f-ink-2" role="status">
          {run.status === 'stopped' || run.waiting === 'stopped'
            ? tNow('deskHq.banner.stopped')
            : run.waiting === 'busy'
              ? tNow('deskHq.banner.busy')
              : tNow('deskHq.waitingContinue', { what: run.waiting![0].toUpperCase() + run.waiting!.slice(1) })}
        </p>
      )}
      {run.error && <p className="mt-3 text-f-body text-f-danger">{tNow('deskHq.whatWentWrong', { error: run.error })}</p>}
      {run.tree?.root && (
        <ol className="mt-4 flex flex-col gap-1" aria-label={tNow('deskHq.progress')}>
          <TaskRow t={run.tree.root} />
        </ol>
      )}
      {run.files && run.files.length > 0 && (
        <section className="mt-5" aria-label={tNow('deskHq.created')}>
          <h2 className="text-f-label text-f-ink-2">{tNow('deskHq.createdH')}</h2>
          <ul className="mt-2 flex flex-col gap-1">
            {run.files.map((f) => (
              <li key={f.path} className="text-f-body text-f-ink font-mono">
                {f.path} <span className="text-f-ink-3">· {f.size < 1024 ? tNow('deskHq.bytes', { n: f.size }) : tNow('deskHq.kb', { n: (f.size / 1024).toFixed(1) })}</span>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}

function TaskRow({ t }: { t: ForgeTask }) {
  const [open, setOpen] = useState(false);
  const st = TASK_STATUS[t.status] ?? { word: t.status, icon: <Circle className="w-4 h-4" /> };
  const how = t.proof?.ok
    ? `${t.from_recipe ? tNow('deskHq.fromRecipe') : ''}${PROOF[t.proof.strength] ?? t.proof.strength}${t.check_by_model ? tNow('deskHq.checkedByModel') : ''}`
    : st.word;
  const label = t.title?.trim() || shortGoal(t.goal);
  return (
    <li>
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        className="w-full min-h-11 px-3 py-2 flex items-start gap-3 text-left rounded-f-m hover:bg-f-raise"
      >
        <span className="mt-0.5">{st.icon}</span>
        <span className="flex-1 min-w-0">
          <span className="block text-f-read text-f-ink">{label}</span>
          <span className="block text-f-meta text-f-ink-3">
            {how}
            {t.attempts > 1 ? ` · ${t.attempts} ${tPlural(t.attempts, 'deskHq.attempts')}` : ''}
          </span>
        </span>
      </button>
      <details
        className="mx-3 mb-2 text-f-meta text-f-ink-3"
        open={open}
        onToggle={(e) => setOpen((e.currentTarget as HTMLDetailsElement).open)}
      >
        <summary
          className="cursor-pointer select-none py-1 text-f-meta text-f-ink-3"
          onClick={(e) => {
            e.preventDefault();
            setOpen((v) => !v);
          }}
        >
          {tNow('deskHq.step.detail')}
        </summary>
        {open && (
          <div className="mt-1 rounded-f-m bg-f-raise p-3 text-f-body text-f-ink-2 whitespace-pre-wrap font-mono">
            <p>{t.goal}</p>
            {t.proof?.detail && <p className="mt-2">{tNow('deskHq.proof', { detail: t.proof.detail })}</p>}
            {t.failures.slice(-2).map((f, i) => (
              <p key={i} className="mt-2">{tNow('deskHq.failure', { text: f.slice(0, 1200) })}</p>
            ))}
          </div>
        )}
      </details>
      {t.children.length > 0 && (
        <ol className="ml-6 border-l border-f-raise pl-2 flex flex-col gap-1">
          {t.children.map((c) => (
            <TaskRow key={c.id} t={c} />
          ))}
        </ol>
      )}
    </li>
  );
}

function Intro() {
  return (
    <div className="h-full flex flex-col items-center justify-center text-center max-w-[520px] mx-auto">
      <Users className="w-8 h-8 text-f-ink-3" strokeWidth={1.5} aria-hidden />
      <h1 className="mt-4 font-sans text-f-title text-f-ink">{tNow('deskHq.title')}</h1>
      <p className="mt-2 text-f-read text-f-ink-2">
        {tNow('deskHq.intro')}
      </p>
    </div>
  );
}

function NewWork({ onCreated }: { onCreated: (id: string) => void }) {
  const [goal, setGoal] = useState('');
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState('');
  const submit = async () => {
    if (goal.trim().length < 3) return setMsg(tNow('deskHq.sayMore'));
    setBusy(true);
    setMsg('');
    try {
      const r = await forgeApi.start(goal.trim());
      setGoal('');
      onCreated(r.id);
    } catch (e) {
      setMsg(e instanceof ApiError && e.status === 403 ? tNow('deskHq.ownerOnly') : tNow('deskHq.notAccepted'));
    } finally {
      setBusy(false);
    }
  };
  return (
    <form
      className="rounded-f-sheet bg-f-surface p-3"
      onSubmit={(e) => {
        e.preventDefault();
        void submit();
      }}
    >
      <label htmlFor="hq-brief" className="text-f-label text-f-ink-2">{tNow('deskHq.whatNeeded')}</label>
      <textarea
        id="hq-brief"
        value={goal}
        onChange={(e) => setGoal(e.target.value)}
        rows={4}
        placeholder={tNow('deskHq.goalPlaceholder')}
        className="mt-2 w-full resize-none rounded-f-m bg-f-raise px-3 py-2 text-f-read text-f-ink focus:outline focus:outline-2 focus:outline-f-accent"
      />
      {msg && <p className="mt-2 text-f-body text-f-danger" role="status">{msg}</p>}
      <button type="submit" disabled={busy} className="mt-3 w-full min-h-11 rounded-full bg-f-accent text-f-on-accent text-f-body disabled:opacity-50">
        {busy ? tNow('deskHq.accepting') : tNow('deskHq.assign')}
      </button>
    </form>
  );
}

function MissionView({ mission, onChanged }: { mission: PolisMission; onChanged: () => void }) {
  const steps = inOrder(mission.nodes);
  const cost = spent(mission);
  const active = mission.status === 'running' || mission.status === 'paused';
  const [open, setOpen] = useState<string | null>(null);
  return (
    <div>
      <div className="flex items-start gap-4">
        <div className="flex-1 min-w-0">
          <h1 className="font-sans text-f-title text-f-ink">{mission.title}</h1>
          <p className="mt-1 text-f-body text-f-ink-2">{mission.brief}</p>
        </div>
        {active && (
          <div className="shrink-0 flex gap-2">
            {mission.status === 'running' ? (
              <button type="button" onClick={() => void polisApi.pause(mission.id).then(onChanged)} className="min-h-11 px-4 rounded-full bg-f-raise text-f-body text-f-ink">
                {tNow('deskHq.pause')}
              </button>
            ) : (
              <button type="button" onClick={() => void polisApi.resume(mission.id).then(onChanged)} className="min-h-11 px-4 rounded-full bg-f-raise text-f-body text-f-ink">
                {tNow('deskHq.resume')}
              </button>
            )}
            <button type="button" onClick={() => void polisApi.kill(mission.id).then(onChanged)} className="min-h-11 px-4 rounded-full text-f-body text-f-ink-3 hover:bg-f-raise">
              {tNow('deskHq.stop')}
            </button>
          </div>
        )}
      </div>
      <p className="mt-3 text-f-meta text-f-ink-3" data-testid="hq-cost">
        {MISSION_STATUS[mission.status] ?? mission.status} · {steps.length} {tPlural(steps.length, 'deskHq.works')} ·{' '}
        {tNow('deskHq.modelCalls', { n: cost.calls, calls: tPlural(cost.calls, 'deskHq.calls') })} · {tNow('deskHq.tokens', { n: thousands(cost.tokens) })}
      </p>

      <ol className="mt-4 flex flex-col gap-1" aria-label={tNow('deskHq.progress')}>
        {steps.map((n) => {
          const st = NODE_STATUS[n.status] ?? { word: n.status, icon: <Circle className="w-4 h-4" /> };
          const roles = (n.crew?.roles ?? []).map(roleName).join(', ');
          return (
            <li key={n.id} className="rounded-f-m">
              <button
                type="button"
                onClick={() => setOpen(open === n.id ? null : n.id)}
                aria-expanded={open === n.id}
                className="w-full min-h-11 px-3 py-2 flex items-start gap-3 text-left rounded-f-m hover:bg-f-raise"
              >
                <span className="mt-0.5">{st.icon}</span>
                <span className="flex-1 min-w-0">
                  <span className="block text-f-read text-f-ink">{n.title}</span>
                  <span className="block text-f-meta text-f-ink-3">
                    {roles || tNow('deskHq.team')} · {st.word}
                    {n.budget?.spent_llm_calls ? ` · ${tNow('deskHq.callsOf', { spent: n.budget.spent_llm_calls, max: n.budget.max_llm_calls })}` : ''}
                  </span>
                </span>
              </button>
              {open === n.id && (n.output_summary || n.error) && (
                <div className="mx-3 mb-2 rounded-f-m bg-f-raise p-3 text-f-body text-f-ink-2 whitespace-pre-wrap">
                  {n.error ? tNow('deskHq.whatWentWrong', { error: n.error }) : n.output_summary}
                </div>
              )}
            </li>
          );
        })}
      </ol>

      {mission.status === 'done' && <Result mission={mission} />}
    </div>
  );
}

/** Підсумок — робота, від якої ніхто не залежить (останній крок графа). */
function Result({ mission }: { mission: PolisMission }) {
  const [text, setText] = useState<string | null>(null);
  const final = useMemo(() => {
    const needed = new Set(mission.nodes.flatMap((n) => n.depends_on));
    const leaves = mission.nodes.filter((n) => !needed.has(n.id) && n.status === 'done');
    return leaves[leaves.length - 1] ?? null;
  }, [mission]);
  useEffect(() => {
    if (!final) return;
    let alive = true;
    polisApi
      .artifacts(mission.id)
      .then((r) => {
        const own = r.artifacts.find((a) => a.node_id === final.id) ?? r.artifacts[0];
        return own ? polisApi.artifact(mission.id, own.name) : null;
      })
      .then((a) => alive && setText(a?.content ?? final.output_summary ?? null))
      .catch(() => alive && setText(final.output_summary ?? null));
    return () => {
      alive = false;
    };
  }, [mission.id, final]);
  if (!text) return null;
  return (
    <section className="mt-6 border-t border-f-line pt-5" aria-label={tNow('deskHq.result')}>
      <h2 className="text-f-label text-f-ink-2">{tNow('deskHq.result')}</h2>
      <div className="mt-3 max-w-[760px]">
        <Markdown text={text} />
      </div>
    </section>
  );
}

/** Невеликий безпечний markdown звітів агентів: без сирого HTML. */
export function Markdown({ text }: { text: string }) {
  const blocks: React.ReactNode[] = [];
  const lines = text.replace(/\r/g, '').split('\n');
  let list: string[] = [];
  const flush = () => {
    if (!list.length) return;
    blocks.push(
      <ul key={`ul${blocks.length}`} className="my-2 pl-5 list-disc text-f-read text-f-ink">
        {list.map((l, i) => (
          <li key={i}>{inline(l)}</li>
        ))}
      </ul>,
    );
    list = [];
  };
  for (const raw of lines) {
    const line = raw.trimEnd();
    const bullet = /^\s*(?:[-*•]|\d+[.)])\s+(.*)$/.exec(line);
    if (bullet) {
      list.push(bullet[1]);
      continue;
    }
    flush();
    const h = /^(#{1,4})\s+(.*)$/.exec(line);
    if (h) {
      const cls = h[1].length <= 1 ? 'font-sans text-f-title' : 'text-f-read font-semibold';
      blocks.push(
        <p key={blocks.length} className={`mt-4 mb-1 text-f-ink ${cls}`}>
          {inline(h[2])}
        </p>,
      );
    } else if (/^-{3,}$/.test(line.trim())) {
      blocks.push(<hr key={blocks.length} className="my-3 border-f-line" />);
    } else if (line.trim()) {
      blocks.push(
        <p key={blocks.length} className="my-1.5 text-f-read text-f-ink">
          {inline(line)}
        </p>,
      );
    }
  }
  flush();
  return <div>{blocks}</div>;
}

function inline(s: string): React.ReactNode[] {
  return s.split(/(\*\*[^*]+\*\*)/g).map((part, i) =>
    part.startsWith('**') && part.endsWith('**') ? <strong key={i}>{part.slice(2, -2)}</strong> : <React.Fragment key={i}>{part}</React.Fragment>,
  );
}

function Waiting({
  state,
  runs,
  questions,
  onDecided,
}: {
  state: PolisState | null;
  runs: ForgeRunSummary[] | null;
  questions: AgentQuestion[];
  onDecided: () => void;
}) {
  const [busy, setBusy] = useState<string | null>(null);
  const gates = state?.gates ?? [];
  const asks = (runs ?? []).filter((r) => r.status === 'asking' && r.ask);
  const titleOf = (id: string) => state?.missions.find((m) => m.id === id)?.title ?? tNow('deskHq.task');
  const answer = (id: string, yes: boolean) => {
    setBusy(id);
    void forgeApi
      .answer(id, yes)
      .then(() => onDecided())
      .catch((e: unknown) => {
        if (e instanceof ApiError && e.status === 409) onDecided();
      })
      .finally(() => setBusy(null));
  };
  return (
    <section className="rounded-f-sheet bg-f-surface p-4" aria-label={tNow('deskHq.waitingForYou')} data-testid="hq-gates">
      <h2 className="text-f-label text-f-ink-2">{tNow('deskHq.waitingForYou')}</h2>
      {questions.map((q) => (
        <AgentAsk key={q.task_id} q={q} onDone={onDecided} />
      ))}
      {asks.map((r) => {
        const text = typeof r.ask?.args?.text === 'string' ? r.ask.args.text : null;
        return (
          <div key={r.id} className="mt-3 rounded-f-m bg-f-raise p-3">
            <p className="text-f-meta text-f-ink-3">{r.goal}</p>
            <p className="mt-1 text-f-read text-f-ink">
              {r.ask?.tool === 'messenger.send' ? tNow('deskHq.ask.send') : tNow('deskHq.ask.other')}
            </p>
            {text && <p className="mt-1 text-f-body text-f-ink-2 line-clamp-4 whitespace-pre-wrap">{text}</p>}
            <div className="mt-3 flex gap-2">
              <button
                type="button"
                disabled={busy === r.id}
                onClick={() => answer(r.id, true)}
                className="min-h-11 px-4 rounded-full bg-f-accent text-f-on-accent text-f-body disabled:opacity-50"
              >
                {tNow('deskHq.yes')}
              </button>
              <button
                type="button"
                disabled={busy === r.id}
                onClick={() => answer(r.id, false)}
                className="min-h-11 px-4 rounded-full text-f-body text-f-ink-2 hover:bg-f-surface"
              >
                {tNow('deskHq.no')}
              </button>
            </div>
          </div>
        );
      })}
      {gates.length === 0 && questions.length === 0 && asks.length === 0 ? (
        <p className="mt-2 text-f-body text-f-ink-3">{tNow('deskHq.noDecisions')}</p>
      ) : (
        gates.map((g) => (
          <div key={g.id} className="mt-3 rounded-f-m bg-f-raise p-3">
            <p className="text-f-meta text-f-ink-3">{titleOf(g.mission_id)}</p>
            <p className="mt-1 text-f-read text-f-ink">{g.question}</p>
            {g.payload_preview && <p className="mt-1 text-f-body text-f-ink-2 line-clamp-4 whitespace-pre-wrap">{g.payload_preview}</p>}
            <div className="mt-3 flex gap-2">
              <button
                type="button"
                disabled={busy === g.id}
                onClick={() => {
                  setBusy(g.id);
                  void polisApi.resolveGate(g.id, true).finally(() => {
                    setBusy(null);
                    onDecided();
                  });
                }}
                className="min-h-11 px-4 rounded-full bg-f-accent text-f-on-accent text-f-body disabled:opacity-50"
              >
                {tNow('deskHq.yes')}
              </button>
              <button
                type="button"
                disabled={busy === g.id}
                onClick={() => {
                  setBusy(g.id);
                  void polisApi.resolveGate(g.id, false).finally(() => {
                    setBusy(null);
                    onDecided();
                  });
                }}
                className="min-h-11 px-4 rounded-full text-f-body text-f-ink-2 hover:bg-f-surface"
              >
                {tNow('deskHq.no')}
              </button>
            </div>
          </div>
        ))
      )}
    </section>
  );
}

function Team({ state }: { state: PolisState }) {
  const g = state.governor;
  const busy = state.citizens.filter((c) => c.activity && c.activity !== 'idle');
  const known = [...state.citizens].filter((c) => c.missions_done > 0).sort((a, b) => b.missions_done - a.missions_done);
  return (
    <section className="flex-1 min-h-0 overflow-y-auto no-scrollbar rounded-f-sheet bg-f-surface p-4" aria-label={tNow('deskHq.team2')}>
      <h2 className="text-f-label text-f-ink-2">{tNow('deskHq.team2')}</h2>
      <p className="mt-2 text-f-body text-f-ink-2" data-testid="hq-governor">
        {g.running_nodes > 0
          ? `${tNow('deskHq.agentsNow', { n: g.running_nodes, agents: tPlural(g.running_nodes, 'deskHq.agents'), max: g.max_wave })}${g.queued_nodes ? tNow('deskHq.queuedN', { n: g.queued_nodes }) : ''}.`
          : tNow(pluralKey(currentLocale(), state.citizens.length, 'deskHq.rolesReady'), { n: state.citizens.length, max: g.max_wave })}
      </p>
      {busy.map((c) => (
        <p key={c.id} className="mt-2 text-f-body text-f-ink">
          {roleName(c.role)} <span className="text-f-ink-3">— {tNow('deskHq.st.running')}</span>
        </p>
      ))}
      {known.length > 0 && (
        <ul className="mt-3 flex flex-col gap-2" aria-label={tNow('deskHq.roleExperience')}>
          {known.map((c) => (
            <li key={c.id} className="flex items-center gap-2 text-f-body">
              <span className="flex-1 min-w-0 truncate text-f-ink">{roleName(c.role)}</span>
              <span className="text-f-ink-3">
                {c.missions_done} {tPlural(c.missions_done, 'deskHq.works')} · {tNow('deskHq.reliability', { n: Math.round(c.reliability * 100) })}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** Агент зупинився й питає людину — відповідь іде назад у його завдання. */
function AgentAsk({ q, onDone }: { q: AgentQuestion; onDone: () => void }) {
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const send = async (instruction: string) => {
    if (!instruction.trim()) return;
    setBusy(true);
    try {
      await agentAskApi.answer(q.task_id, instruction.trim());
      setText('');
      onDone();
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="mt-3 rounded-f-m bg-f-raise p-3" data-testid="hq-agent-question">
      <p className="text-f-meta text-f-ink-3 truncate">{q.goal || tNow('deskHq.nightpostTask')}</p>
      <p className="mt-1 text-f-read text-f-ink">{q.question}</p>
      {q.kind === 'consent' ? (
        <div className="mt-3 flex gap-2">
          <button type="button" disabled={busy} onClick={() => void send('approve')} className="min-h-11 px-4 rounded-full bg-f-accent text-f-on-accent text-f-body disabled:opacity-50">
            {tNow('deskHq.allow')}
          </button>
          <button type="button" disabled={busy} onClick={() => void send('reject')} className="min-h-11 px-4 rounded-full text-f-body text-f-ink-2 hover:bg-f-surface">
            {tNow('deskHq.deny')}
          </button>
        </div>
      ) : (
        <form
          className="mt-3 flex gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            void send(text);
          }}
        >
          <input
            aria-label={tNow('deskHq.replyToAgent')}
            value={text}
            onChange={(e) => setText(e.target.value)}
            placeholder={tNow('deskHq.replyPlaceholder')}
            className="flex-1 min-w-0 h-11 px-3 rounded-f-m bg-f-surface text-f-body text-f-ink focus:outline focus:outline-2 focus:outline-f-accent"
          />
          <button type="submit" disabled={busy || !text.trim()} className="min-h-11 px-4 rounded-full bg-f-accent text-f-on-accent text-f-body disabled:opacity-50">
            {tNow('deskHq.reply')}
          </button>
        </form>
      )}
    </div>
  );
}

