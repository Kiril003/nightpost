import type {
  ChecklistItem,
  SignoffDecision,
  Stamp,
  TaskScalar,
  TaskStatus,
  WorkTask,
} from '@shared/types/work';
import { TASK_SCALARS, TASK_STATUSES } from '@shared/types/work';

/**
 * Задача: стани, схвалення і злиття без сервера (11-work §2–3).
 *
 * Злиття — поле за полем за міткою Лемпорта, словники — поштучно, рішення
 * схвалювачів — обʼєднанням. Тож дві копії, змінені на різних телефонах без
 * звʼязку, сходяться в одну незалежно від порядку доставки.
 */

const MOVES: Record<TaskStatus, readonly TaskStatus[]> = {
  TODO: ['DOING', 'DONE', 'CANCELLED'],
  DOING: ['TODO', 'REVIEW', 'DONE', 'CANCELLED'],
  REVIEW: ['DOING', 'DONE', 'CANCELLED'],
  DONE: ['DOING'],
  CANCELLED: ['TODO'],
};

export type TaskRefusal = 'same' | 'illegal' | 'quorum' | 'not-approver';
export type TaskResult = { ok: true; task: WorkTask } | { ok: false; reason: TaskRefusal };

export function newTask(
  init: Pick<WorkTask, 'id' | 'chatId' | 'spaceId' | 'sourceMsg' | 'title' | 'createdBy' | 'createdAt'>,
  stamp: Stamp,
): WorkTask {
  const clock: WorkTask['clock'] = {};
  for (const f of TASK_SCALARS) clock[f] = stamp;
  return {
    ...init,
    assignee: null,
    watchers: {},
    due: null,
    dueFrom: null,
    status: 'TODO',
    blocked: null,
    checklist: {},
    signoff: null,
    decisions: [],
    links: {},
    boardOrder: null,
    clock,
  };
}

/** Чинні «так» від названих схвалювачів: останнє слово кожного. */
export function approvals(task: WorkTask): number {
  if (!task.signoff) return 0;
  const last = new Map<string, SignoffDecision>();
  for (const d of [...task.decisions].sort((a, b) => a.at - b.at)) {
    if (task.signoff.approvers.includes(d.by)) last.set(d.by, d);
  }
  return [...last.values()].filter((d) => d.verdict === 'approve').length;
}

export function taskMove(task: WorkTask, to: TaskStatus, stamp: Stamp): TaskResult {
  if (task.status === to) return { ok: false, reason: 'same' };
  if (!MOVES[task.status].includes(to)) return { ok: false, reason: 'illegal' };
  if (to === 'DONE' && task.signoff && approvals(task) < task.signoff.quorum) {
    return { ok: false, reason: 'quorum' };
  }
  return { ok: true, task: { ...task, status: to, clock: { ...task.clock, status: stamp } } };
}

export function taskDecide(
  task: WorkTask,
  by: string,
  verdict: SignoffDecision['verdict'],
  at: number,
  stamp: Stamp,
): TaskResult {
  if (!task.signoff || !task.signoff.approvers.includes(by)) return { ok: false, reason: 'not-approver' };
  const next: WorkTask = { ...task, decisions: [...task.decisions, { by, verdict, at }] };
  if (verdict === 'changes' && task.status === 'REVIEW') {
    return { ok: true, task: { ...next, status: 'DOING', clock: { ...next.clock, status: stamp } } };
  }
  return { ok: true, task: next };
}

const NO_STAMP: Stamp = { l: -1, by: '' };

/** a новіша за b? Повна рівність міток розводиться значенням — злиття комутативне. */
function newer(a: Stamp, b: Stamp, va: unknown, vb: unknown): boolean {
  if (a.l !== b.l) return a.l > b.l;
  if (a.by !== b.by) return a.by > b.by;
  return JSON.stringify(va) > JSON.stringify(vb);
}

function mergeMap<T extends { s: Stamp }>(a: Record<string, T>, b: Record<string, T>): Record<string, T> {
  const out: Record<string, T> = {};
  for (const key of [...new Set([...Object.keys(a), ...Object.keys(b)])].sort()) {
    const x = a[key];
    const y = b[key];
    out[key] = !x ? y : !y ? x : newer(y.s, x.s, y, x) ? y : x;
  }
  return out;
}

export function mergeTask(a: WorkTask, b: WorkTask): WorkTask {
  if (a.id !== b.id) return a;
  const out: WorkTask = { ...a, clock: {} };
  const set = <K extends TaskScalar>(k: K, from: WorkTask) => {
    out[k] = from[k];
  };
  for (const f of TASK_SCALARS) {
    const sa = a.clock[f] ?? NO_STAMP;
    const sb = b.clock[f] ?? NO_STAMP;
    const winner = newer(sb, sa, b[f], a[f]) ? b : a;
    set(f, winner);
    const s = winner.clock[f];
    if (s) out.clock[f] = s;
  }
  out.watchers = mergeMap(a.watchers, b.watchers);
  out.links = mergeMap(a.links, b.links);
  out.checklist = mergeMap<ChecklistItem>(a.checklist, b.checklist);
  const seen = new Map<string, SignoffDecision>();
  for (const d of [...a.decisions, ...b.decisions]) seen.set(`${d.at}|${d.by}|${d.verdict}`, d);
  out.decisions = [...seen.entries()].sort(([x], [y]) => (x < y ? -1 : x > y ? 1 : 0)).map(([, d]) => d);
  if (out.status === 'DONE' && out.signoff && approvals(out) < out.signoff.quorum) {
    out.status = 'REVIEW';
  }
  out.createdAt = Math.min(a.createdAt, b.createdAt);
  const aFirst = a.createdAt < b.createdAt || (a.createdAt === b.createdAt && a.createdBy <= b.createdBy);
  out.createdBy = aFirst ? a.createdBy : b.createdBy;
  return out;
}

type Raw = Record<string, unknown>;
const isObj = (v: unknown): v is Raw => v !== null && typeof v === 'object' && !Array.isArray(v);
const isStr = (v: unknown, max = 128): v is string => typeof v === 'string' && v.length > 0 && v.length <= max;
const isNum = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v);
const strOrNull = (v: unknown, max = 128) => v === null || isStr(v, max);
const numOrNull = (v: unknown) => v === null || isNum(v);
const isStamp = (v: unknown): v is Stamp => isObj(v) && isNum(v.l) && typeof v.by === 'string';

function flagMap(v: unknown): WorkTask['watchers'] | null {
  if (!isObj(v)) return null;
  const out: WorkTask['watchers'] = {};
  for (const [k, x] of Object.entries(v)) {
    if (!isStr(k) || !isObj(x) || typeof x.on !== 'boolean' || !isStamp(x.s)) return null;
    out[k] = { on: x.on, s: { l: x.s.l, by: x.s.by } };
  }
  return out;
}

function checklist(v: unknown): WorkTask['checklist'] | null {
  if (!isObj(v)) return null;
  const out: WorkTask['checklist'] = {};
  for (const [k, x] of Object.entries(v)) {
    if (!isStr(k) || !isObj(x) || typeof x.text !== 'string' || x.text.length > 500) return null;
    if (typeof x.done !== 'boolean' || typeof x.gone !== 'boolean' || !isNum(x.order) || !isStamp(x.s)) return null;
    out[k] = { text: x.text, done: x.done, order: x.order, gone: x.gone, s: { l: x.s.l, by: x.s.by } };
  }
  return out;
}

/** Задача з дроту чи сховища; бита — null. */
export function parseTask(raw: unknown): WorkTask | null {
  if (!isObj(raw) || !isStr(raw.id) || !isStr(raw.chatId) || !isStr(raw.title, 300)) return null;
  if (!strOrNull(raw.spaceId) || !strOrNull(raw.sourceMsg) || !strOrNull(raw.assignee)) return null;
  if (!numOrNull(raw.due) || !(raw.dueFrom === null || raw.dueFrom === 'text' || raw.dueFrom === 'picked')) return null;
  if (!(TASK_STATUSES as readonly unknown[]).includes(raw.status) || !strOrNull(raw.blocked, 300)) return null;
  if (!strOrNull(raw.boardOrder) || !isStr(raw.createdBy) || !isNum(raw.createdAt)) return null;
  const watchers = flagMap(raw.watchers);
  const links = flagMap(raw.links);
  const list = checklist(raw.checklist);
  if (!watchers || !links || !list) return null;

  let signoff: WorkTask['signoff'] = null;
  if (raw.signoff !== null) {
    const s = raw.signoff;
    if (!isObj(s) || !Array.isArray(s.approvers) || !s.approvers.every((x) => isStr(x))) return null;
    if (!isNum(s.quorum) || s.quorum < 1 || s.quorum > s.approvers.length) return null;
    signoff = { approvers: [...(s.approvers as string[])], quorum: s.quorum };
  }
  if (!Array.isArray(raw.decisions)) return null;
  const decisions: SignoffDecision[] = [];
  for (const d of raw.decisions) {
    if (!isObj(d) || !isStr(d.by) || !isNum(d.at) || (d.verdict !== 'approve' && d.verdict !== 'changes')) return null;
    decisions.push({ by: d.by, verdict: d.verdict, at: d.at });
  }
  if (!isObj(raw.clock)) return null;
  const clock: WorkTask['clock'] = {};
  for (const f of TASK_SCALARS) {
    const s = raw.clock[f];
    if (s === undefined) continue;
    if (!isStamp(s)) return null;
    clock[f] = { l: s.l, by: s.by };
  }

  return {
    id: raw.id,
    spaceId: raw.spaceId as string | null,
    chatId: raw.chatId,
    sourceMsg: raw.sourceMsg as string | null,
    title: raw.title,
    assignee: raw.assignee as string | null,
    watchers,
    due: raw.due as number | null,
    dueFrom: raw.dueFrom,
    status: raw.status as TaskStatus,
    blocked: raw.blocked as string | null,
    checklist: list,
    signoff,
    decisions,
    links,
    boardOrder: raw.boardOrder as string | null,
    createdBy: raw.createdBy,
    createdAt: raw.createdAt,
    clock,
  };
}
