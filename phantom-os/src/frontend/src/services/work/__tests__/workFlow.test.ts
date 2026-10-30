import { describe, it, expect } from 'vitest';
import type { MissingTaskFields, MissingSwapFields, ClockEvent } from '@shared/types/work';
import { WORK_SCHEMA, WORK_FIELDS } from '@shared/types/work';
import { newTask, taskMove, taskDecide, mergeTask, approvals, parseTask } from '../taskFlow';
import { swapStep, swapExpiry, mergeSwap, parseSwap } from '../swapFlow';
import type { ShiftSwap, WorkTask } from '@shared/types/work';

const T0 = Date.UTC(2026, 9, 22, 9, 0);
const H = 36e5;

const allTaskFields: [MissingTaskFields] extends [never] ? true : false = true;
const allSwapFields: [MissingSwapFields] extends [never] ? true : false = true;

const base = () =>
  newTask(
    { id: 't1', chatId: 'c1', spaceId: 'sp1', sourceMsg: 'm1', title: 'Зібрати реліз', createdBy: 'mara', createdAt: T0 },
    { l: 1, by: 'mara' },
  );

describe('work.* схема заморожена', () => {
  it('версія й поля не змінились без міграції', () => {
    expect(allTaskFields && allSwapFields).toBe(true);
    expect(WORK_SCHEMA).toBe(1);
    expect(WORK_FIELDS).toMatchInlineSnapshot(`
      {
        "clock": [
          "shiftId",
          "member",
          "at",
          "method",
          "result",
        ],
        "swap": [
          "id",
          "shiftA",
          "shiftB",
          "from",
          "to",
          "state",
          "leadApproves",
          "expiresAt",
          "log",
        ],
        "task": [
          "id",
          "spaceId",
          "chatId",
          "sourceMsg",
          "title",
          "assignee",
          "watchers",
          "due",
          "dueFrom",
          "status",
          "blocked",
          "checklist",
          "signoff",
          "decisions",
          "links",
          "boardOrder",
          "createdBy",
          "createdAt",
          "clock",
        ],
      }
    `);
  });

  it('відмітка входу не має полів координат', () => {
    const e: ClockEvent = { shiftId: 's1', member: 'jana', at: T0, method: 'location-once', result: 'inside' };
    expect(Object.keys(e).some((k) => /lat|lon|coord|geo/i.test(k))).toBe(false);
    expect(WORK_FIELDS.clock.some((k) => /lat|lon|coord|geo/i.test(k))).toBe(false);
  });
});

describe('задача: стани', () => {
  it('TODO → DOING → REVIEW → DONE і назад зі «Зміни»', () => {
    let t = base();
    for (const [to, l] of [['DOING', 2], ['REVIEW', 3], ['DONE', 4]] as const) {
      const r = taskMove(t, to, { l, by: 'mara' });
      expect(r.ok).toBe(true);
      if (r.ok) t = r.task;
    }
    expect(t.status).toBe('DONE');
    expect(taskMove(t, 'REVIEW', { l: 5, by: 'mara' })).toEqual({ ok: false, reason: 'illegal' });
    expect(taskMove(t, 'DONE', { l: 5, by: 'mara' })).toEqual({ ok: false, reason: 'same' });
  });

  it('зі схваленням DONE лише після кворуму', () => {
    let t: WorkTask = { ...base(), status: 'REVIEW', signoff: { approvers: ['ivo', 'lena', 'oto'], quorum: 2 } };
    expect(taskMove(t, 'DONE', { l: 2, by: 'mara' })).toEqual({ ok: false, reason: 'quorum' });
    const a = taskDecide(t, 'ivo', 'approve', T0 + H, { l: 3, by: 'ivo' });
    expect(a.ok).toBe(true);
    if (a.ok) t = a.task;
    expect(approvals(t)).toBe(1);
    expect(taskDecide(t, 'mara', 'approve', T0 + H, { l: 4, by: 'mara' })).toEqual({ ok: false, reason: 'not-approver' });
    const b = taskDecide(t, 'lena', 'approve', T0 + 2 * H, { l: 5, by: 'lena' });
    if (b.ok) t = b.task;
    expect(taskMove(t, 'DONE', { l: 6, by: 'mara' }).ok).toBe(true);
  });

  it('«Зміни» повертає з REVIEW у DOING, і останнє слово схвалювача перемагає', () => {
    let t: WorkTask = { ...base(), status: 'REVIEW', signoff: { approvers: ['ivo'], quorum: 1 } };
    const a = taskDecide(t, 'ivo', 'approve', T0, { l: 2, by: 'ivo' });
    if (a.ok) t = a.task;
    const c = taskDecide(t, 'ivo', 'changes', T0 + H, { l: 3, by: 'ivo' });
    if (c.ok) t = c.task;
    expect(t.status).toBe('DOING');
    expect(approvals(t)).toBe(0);
  });
});

describe('задача: злиття без сервера', () => {
  it('поле за полем, у будь-якому порядку', () => {
    const t = base();
    const left = { ...t, title: 'Реліз 2.1', clock: { ...t.clock, title: { l: 5, by: 'mara' } } };
    const right = { ...t, assignee: 'ivo', clock: { ...t.clock, assignee: { l: 4, by: 'oto' } } };
    const m1 = mergeTask(left, right);
    const m2 = mergeTask(right, left);
    expect(m1).toEqual(m2);
    expect(m1.title).toBe('Реліз 2.1');
    expect(m1.assignee).toBe('ivo');
  });

  it('спостерігачі й пункти зливаються поштучно, видалене лишається видаленим', () => {
    const t = base();
    const a = { ...t, watchers: { ivo: { on: true, s: { l: 2, by: 'ivo' } } } };
    const b = { ...t, watchers: { ivo: { on: false, s: { l: 3, by: 'mara' } }, lena: { on: true, s: { l: 2, by: 'lena' } } } };
    const m = mergeTask(a, b);
    expect(m).toEqual(mergeTask(b, a));
    expect(m.watchers.ivo.on).toBe(false);
    expect(m.watchers.lena.on).toBe(true);
  });

  it('рішення схвалювачів не губляться при злитті', () => {
    const t = { ...base(), status: 'REVIEW' as const, signoff: { approvers: ['ivo', 'lena'], quorum: 2 } };
    const a = taskDecide(t, 'ivo', 'approve', T0, { l: 2, by: 'ivo' });
    const b = taskDecide(t, 'lena', 'approve', T0 + 1, { l: 2, by: 'lena' });
    if (!a.ok || !b.ok) throw new Error('decide');
    expect(approvals(mergeTask(a.task, b.task))).toBe(2);
  });

  it('злиття не дає DONE без кворуму', () => {
    const t: WorkTask = { ...base(), status: 'REVIEW', signoff: { approvers: ['ivo'], quorum: 1 } };
    const done = { ...t, status: 'DONE' as const, signoff: null, clock: { ...t.clock, status: { l: 5, by: 'mara' }, signoff: { l: 2, by: 'mara' } } };
    const gated = { ...t, clock: { ...t.clock, signoff: { l: 6, by: 'ivo' } } };
    const m = mergeTask(done, gated);
    expect(m).toEqual(mergeTask(gated, done));
    expect(m.signoff).not.toBeNull();
    expect(m.status).toBe('REVIEW');
  });

  it('розбір чужої задачі відкидає биту, приймає свою', () => {
    const t = base();
    expect(parseTask(JSON.parse(JSON.stringify(t)))).toEqual(t);
    expect(parseTask({ ...t, status: 'MAYBE' })).toBeNull();
    expect(parseTask({ ...t, title: '' })).toBeNull();
    expect(parseTask({ ...t, watchers: { ivo: { on: 'yes' } } })).toBeNull();
  });
});

describe('обмін змінами', () => {
  const offer = (leadApproves = true): ShiftSwap => ({
    id: 'sw1',
    shiftA: 'sA',
    shiftB: 'sB',
    from: 'jana',
    to: 'petr',
    state: 'OFFERED',
    leadApproves,
    expiresAt: swapExpiry(T0 + 10 * H, T0 + 30 * H),
    log: [{ state: 'OFFERED', by: 'jana', at: T0 }],
  });
  const lead = { id: 'mara', lead: true };
  const jana = { id: 'jana', lead: false };
  const petr = { id: 'petr', lead: false };

  it('строк — за 2 год до ранішої зміни', () => {
    expect(swapExpiry(T0 + 10 * H, T0 + 30 * H)).toBe(T0 + 8 * H);
    expect(swapExpiry(T0 + 10 * H, null)).toBe(T0 + 8 * H);
  });

  it('OFFERED → ACCEPTED → APPROVED → APPLIED, кожен крок — своєю людиною', () => {
    let s = offer();
    expect(swapStep(s, 'ACCEPTED', jana, T0 + H)).toEqual({ ok: false, reason: 'not-yours' });
    for (const [next, who] of [['ACCEPTED', petr], ['APPROVED', lead], ['APPLIED', lead]] as const) {
      const r = swapStep(s, next, who, T0 + H);
      expect(r.ok).toBe(true);
      if (r.ok) s = r.swap;
    }
    expect(s.log.map((e) => e.state)).toEqual(['OFFERED', 'ACCEPTED', 'APPROVED', 'APPLIED']);
    expect(swapStep(s, 'WITHDRAWN', jana, T0 + H)).toEqual({ ok: false, reason: 'terminal' });
  });

  it('без схвалення лідера APPROVED пропускається', () => {
    const r1 = swapStep(offer(false), 'ACCEPTED', petr, T0 + H);
    if (!r1.ok) throw new Error('accept');
    expect(swapStep(r1.swap, 'APPROVED', lead, T0 + H)).toEqual({ ok: false, reason: 'illegal' });
    expect(swapStep(r1.swap, 'APPLIED', petr, T0 + H).ok).toBe(true);
  });

  it('після строку лише EXPIRED', () => {
    const s = offer();
    expect(swapStep(s, 'ACCEPTED', petr, T0 + 8 * H)).toEqual({ ok: false, reason: 'expired' });
    expect(swapStep(s, 'EXPIRED', petr, T0 + 7 * H)).toEqual({ ok: false, reason: 'illegal' });
    expect(swapStep(s, 'EXPIRED', petr, T0 + 8 * H).ok).toBe(true);
  });

  it('кінцевий стан при злитті не залежить від годинника відправника', () => {
    const acc = swapStep(offer(), 'ACCEPTED', petr, T0 + H);
    if (!acc.ok) throw new Error('accept');
    const approved = swapStep(acc.swap, 'APPROVED', lead, T0 + 2 * H);
    if (!approved.ok) throw new Error('approve');
    const applied = swapStep(approved.swap, 'APPLIED', lead, T0 + 3 * H);
    const declined = swapStep(acc.swap, 'DECLINED', lead, T0 + 2 * H);
    if (!applied.ok || !declined.ok) throw new Error('step');
    const backdated = { ...applied.swap, log: applied.swap.log.map((e) => (e.state === 'APPLIED' ? { ...e, at: T0 } : e)) };
    expect(mergeSwap(applied.swap, declined.swap).state).toBe(mergeSwap(backdated, declined.swap).state);
    expect(mergeSwap(declined.swap, backdated).state).toBe('DECLINED');
  });

  it('злиття: дальший стан перемагає, журнал повний, порядок неважливий', () => {
    const a = swapStep(offer(), 'ACCEPTED', petr, T0 + H);
    const w = swapStep(offer(), 'WITHDRAWN', jana, T0 + 2 * H);
    if (!a.ok || !w.ok) throw new Error('step');
    const m = mergeSwap(a.swap, w.swap);
    expect(m).toEqual(mergeSwap(w.swap, a.swap));
    expect(m.state).toBe('WITHDRAWN');
    expect(m.log).toHaveLength(3);
    expect(parseSwap(JSON.parse(JSON.stringify(m)))).toEqual(m);
    expect(parseSwap({ ...m, state: 'DONE' })).toBeNull();
  });
});
