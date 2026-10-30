import { beforeEach, describe, expect, it } from 'vitest';
import type { MeetupData } from '../../../types/meetup';
import { expirySweep, expiryState, keptBack, sweptMeetups } from '../expiry';

const H = 3600_000;
const AT = Date.UTC(2026, 9, 9, 17, 0);

const meetup = (id: string, expiresAt: number | null, at = AT): MeetupData => ({
  v: 1,
  id,
  venue: { id: 'cafe-x', name: 'Cafe X', address: 'Stodolní 1', lat: 49.83, lon: 18.28, rating: null, menuVersion: 1 },
  at,
  organizer: 'olena',
  invitees: [{ id: 'petro', name: 'Петро' }],
  expiresAt,
});

describe('стан строку на картці', () => {
  it('звичайна група — не ховається', () => {
    expect(expiryState(meetup('m', null), {}, AT + 100 * H)).toEqual({ kind: 'none' });
  });

  it('до строку — «сховається о …»; після — сховано', () => {
    expect(expiryState(meetup('m', AT + 24 * H), {}, AT)).toEqual({ kind: 'will_hide', at: AT + 24 * H });
    expect(expiryState(meetup('m', AT + 24 * H), {}, AT + 24 * H)).toEqual({ kind: 'expired', at: AT + 24 * H });
  });

  it('будь-хто натиснув «Лишити» — лишається в усіх', () => {
    expect(expiryState(meetup('m', AT + 24 * H), { 'keep:1': ['petro'] }, AT + 30 * H)).toEqual({ kind: 'kept', by: ['petro'] });
  });
});

describe('прохід архіву', () => {
  const group = (chatId: string, cards: Array<{ m: MeetupData; marks?: Record<string, string[]>; sentAt: number }>) => ({
    chatId,
    cards: cards.map((c) => ({ meetup: c.m, marks: c.marks, sentAt: c.sentAt })),
  });

  it('ховає лише прострочені й не залишені', () => {
    const out = expirySweep(
      [
        group('expired', [{ m: meetup('m1', AT + 24 * H), sentAt: 1 }]),
        group('kept', [{ m: meetup('m2', AT + 24 * H), marks: { 'keep:1': ['olena'] }, sentAt: 1 }]),
        group('fresh', [{ m: meetup('m3', AT + 48 * H), sentAt: 1 }]),
        group('plain', [{ m: meetup('m4', null), sentAt: 1 }]),
        group('empty', []),
      ],
      AT + 25 * H,
      new Set(),
    );
    expect(out).toEqual([{ chatId: 'expired', meetupId: 'm1' }]);
  });

  it('вирішує НАЙНОВІША картка зустрічі в групі', () => {
    const g = group('g', [
      { m: meetup('old', AT + 24 * H), sentAt: 1 },
      { m: meetup('new', AT + 72 * H, AT + 48 * H), sentAt: 2 },
    ]);
    expect(expirySweep([g], AT + 25 * H, new Set())).toEqual([]);
    const reversed = group('g', [
      { m: meetup('new', AT + 24 * H), sentAt: 5 },
      { m: meetup('old', null), sentAt: 1 },
    ]);
    expect(expirySweep([reversed], AT + 25 * H, new Set())).toEqual([{ chatId: 'g', meetupId: 'new' }]);
  });

  it('ховає раз: дістану з архіву — наступний прохід не сховає знову', () => {
    const g = group('g', [{ m: meetup('m1', AT + 24 * H), sentAt: 1 }]);
    expect(expirySweep([g], AT + 25 * H, new Set(['m1']))).toEqual([]);
  });

  it('сховану проходом групу лишили пізніше — повернути; сховану вручну чи не лишену — ні', () => {
    const kept = { 'keep:1': ['petro'] };
    expect(
      keptBack(
        [
          group('swept-kept', [{ m: meetup('m1', AT + 24 * H), marks: kept, sentAt: 1 }]),
          group('swept-not-kept', [{ m: meetup('m2', AT + 24 * H), sentAt: 1 }]),
          group('manual-kept', [{ m: meetup('m3', AT + 24 * H), marks: kept, sentAt: 1 }]),
          group('old-kept-new-not', [
            { m: meetup('m1', AT + 24 * H), marks: kept, sentAt: 1 },
            { m: meetup('m4', AT + 24 * H), sentAt: 2 },
          ]),
        ],
        AT + 30 * H,
        new Set(['m1', 'm2', 'm4']),
      ),
    ).toEqual([{ chatId: 'swept-kept', meetupId: 'm1' }]);
  });
});

describe('памʼять про сховані зустрічі', () => {
  beforeEach(() => {
    localStorage.clear();
    sweptMeetups.reset();
  });

  it('переживає перезапуск і не росте без меж', () => {
    sweptMeetups.add('m1');
    expect(sweptMeetups.all().has('m1')).toBe(true);
    sweptMeetups.reload();
    expect(sweptMeetups.all().has('m1')).toBe(true);
    for (let i = 0; i < 600; i++) sweptMeetups.add(`x${i}`);
    expect(sweptMeetups.all().size).toBeLessThanOrEqual(500);
    expect(sweptMeetups.all().has('x599')).toBe(true);
  });
});
