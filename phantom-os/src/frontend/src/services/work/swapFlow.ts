import type { ShiftSwap, SwapState } from '@shared/types/work';
import { SWAP_STATES } from '@shared/types/work';

/**
 * Обмін змінами (11-work §3): OFFERED → ACCEPTED → APPROVED → APPLIED,
 * виходи DECLINED, WITHDRAWN, EXPIRED. Кожен крок робить конкретна людина;
 * після строку можливий лише EXPIRED.
 */

const TWO_HOURS = 2 * 36e5;
const TERMINAL: readonly SwapState[] = ['APPLIED', 'DECLINED', 'WITHDRAWN', 'EXPIRED'];
/**
 * Порядок при злитті. Кінцеві стани мають сталу перевагу, не час: «Скасовано»
 * будь-якого роду бʼє «Застосовано», бо час події — слово відправника і
 * зворотною датою вирішувати результат не можна.
 */
const RANK: Record<SwapState, number> = {
  OFFERED: 0,
  ACCEPTED: 1,
  APPROVED: 2,
  APPLIED: 3,
  EXPIRED: 4,
  WITHDRAWN: 5,
  DECLINED: 6,
};

export interface SwapActor {
  id: string;
  /** Має `swap.approve` у цій команді. */
  lead: boolean;
}

export type SwapRefusal = 'terminal' | 'expired' | 'illegal' | 'not-yours';
export type SwapResult = { ok: true; swap: ShiftSwap } | { ok: false; reason: SwapRefusal };

export function swapExpiry(startA: number, startB: number | null): number {
  return Math.min(startA, startB ?? startA) - TWO_HOURS;
}

/** Хто може зробити крок `from → to`; null — такого кроку немає. */
function who(s: ShiftSwap, to: SwapState, a: SwapActor): boolean | null {
  const party = a.id === s.from || a.id === s.to;
  switch (`${s.state}>${to}`) {
    case 'OFFERED>ACCEPTED':
      return a.id === s.to;
    case 'OFFERED>DECLINED':
      return a.id === s.to || a.lead;
    case 'OFFERED>WITHDRAWN':
      return a.id === s.from;
    case 'ACCEPTED>APPROVED':
      return s.leadApproves ? a.lead : null;
    case 'ACCEPTED>DECLINED':
      return s.leadApproves ? a.lead : null;
    case 'ACCEPTED>APPLIED':
      return s.leadApproves ? null : party;
    case 'ACCEPTED>WITHDRAWN':
      return party;
    case 'APPROVED>APPLIED':
      return party || a.lead;
    default:
      return null;
  }
}

export function swapStep(s: ShiftSwap, to: SwapState, actor: SwapActor, now: number): SwapResult {
  if (TERMINAL.includes(s.state)) return { ok: false, reason: 'terminal' };
  const late = now >= s.expiresAt;
  if (to === 'EXPIRED') {
    return late ? { ok: true, swap: logged(s, to, actor.id, now) } : { ok: false, reason: 'illegal' };
  }
  if (late) return { ok: false, reason: 'expired' };
  const allowed = who(s, to, actor);
  if (allowed === null) return { ok: false, reason: 'illegal' };
  if (!allowed) return { ok: false, reason: 'not-yours' };
  return { ok: true, swap: logged(s, to, actor.id, now) };
}

function logged(s: ShiftSwap, state: SwapState, by: string, at: number): ShiftSwap {
  return { ...s, state, log: [...s.log, { state, by, at }] };
}

const entryKey = (e: ShiftSwap['log'][number]) => `${String(e.at).padStart(16, '0')}|${e.state}|${e.by}`;

/** Дальший стан перемагає за сталим порядком; журнал — обʼєднання. */
export function mergeSwap(a: ShiftSwap, b: ShiftSwap): ShiftSwap {
  if (a.id !== b.id) return a;
  const log = [...new Map([...a.log, ...b.log].map((e) => [entryKey(e), e])).entries()]
    .sort(([x], [y]) => (x < y ? -1 : x > y ? 1 : 0))
    .map(([, e]) => e);
  return { ...a, state: RANK[a.state] >= RANK[b.state] ? a.state : b.state, log };
}

type Raw = Record<string, unknown>;
const isObj = (v: unknown): v is Raw => v !== null && typeof v === 'object' && !Array.isArray(v);
const isStr = (v: unknown): v is string => typeof v === 'string' && v.length > 0 && v.length <= 128;
const isNum = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v);
const isState = (v: unknown): v is SwapState => (SWAP_STATES as readonly unknown[]).includes(v);

export function parseSwap(raw: unknown): ShiftSwap | null {
  if (!isObj(raw) || !isStr(raw.id) || !isStr(raw.shiftA) || !isStr(raw.from) || !isStr(raw.to)) return null;
  if (!(raw.shiftB === null || isStr(raw.shiftB)) || !isState(raw.state)) return null;
  if (typeof raw.leadApproves !== 'boolean' || !isNum(raw.expiresAt) || !Array.isArray(raw.log)) return null;
  const log: ShiftSwap['log'] = [];
  for (const e of raw.log) {
    if (!isObj(e) || !isState(e.state) || !isStr(e.by) || !isNum(e.at)) return null;
    log.push({ state: e.state, by: e.by, at: e.at });
  }
  return {
    id: raw.id,
    shiftA: raw.shiftA,
    shiftB: raw.shiftB,
    from: raw.from,
    to: raw.to,
    state: raw.state,
    leadApproves: raw.leadApproves,
    expiresAt: raw.expiresAt,
    log,
  };
}
