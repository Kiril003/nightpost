import { beforeEach, describe, expect, it } from 'vitest';
import type { DayItem } from '../services/dayApi';
import {
  DEFAULT_RING,
  FREE_SLOTS_RING_KEY,
  QUIET_FROM_H,
  QUIET_TO_H,
  SLOT_STEP_MIN,
  formatSlotRange,
  freeSlots,
  loadRing,
  saveRing,
  slotDay,
  wantsTime,
} from '../services/freeSlots';

const at = (h: number, m = 0, dayOffset = 0) => new Date(2025, 8, 25 + dayOffset, h, m, 0, 0).getTime();

function ev(id: string, startH: number, startM: number, endH: number, endM: number, dayOffset = 0): DayItem {
  return {
    id,
    kind: 'event',
    title: id,
    atMs: at(startH, startM, dayOffset),
    endMs: at(endH, endM, dayOffset),
    allDay: false,
    peerId: null,
    overdue: false,
  };
}

describe('freeSlots', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('starts right at now when now is on a local 15-min boundary and includes the rest of today', () => {
    const items = [ev('e1', 18, 0, 19, 30)];
    const slots = freeSlots(items, {
      nowMs: at(9, 0),
      ring: { padMin: 30, aheadDays: 1 },
      max: 20,
    });

    const busyStart = at(17, 30);
    const busyEnd = at(20, 0);
    for (const s of slots) {
      expect(s.endMs <= busyStart || s.startMs >= busyEnd).toBe(true);
      const contains1745to2000 = s.startMs <= at(17, 45) && s.endMs >= at(20, 0);
      expect(contains1745to2000).toBe(false);
    }

    expect(slots[0]).toEqual({ startMs: at(9, 0), endMs: busyStart });
    expect(slots[1]).toEqual({ startMs: busyEnd, endMs: at(23, 0) });
    expect(formatSlotRange(slots[0])).toBe('09:00–17:30');
  });

  it('includes the rest of today after an ongoing event when aheadDays >= 1', () => {
    const items = [ev('e1', 18, 0, 19, 30)];
    const slots = freeSlots(items, {
      nowMs: at(18, 30),
      ring: { padMin: 30, aheadDays: 1 },
      max: 5,
    });

    expect(slots).toEqual([{ startMs: at(20, 0), endMs: at(23, 0) }]);
  });

  it('stays out of quiet nights across multiple days', () => {
    const items = [ev('e1', 18, 0, 19, 30)];
    const slots = freeSlots(items, {
      nowMs: at(9, 0),
      ring: { padMin: 30, aheadDays: 3 },
      max: 20,
    });

    expect(slots.length).toBeGreaterThan(0);
    const stepMs = SLOT_STEP_MIN * 60_000;
    for (const s of slots) {
      for (let t = s.startMs; t < s.endMs; t += stepMs) {
        const h = new Date(t).getHours();
        expect(h).toBeGreaterThanOrEqual(QUIET_TO_H);
        expect(h).toBeLessThan(QUIET_FROM_H);
      }
      const startDate = new Date(s.startMs);
      const endDate = new Date(s.endMs);
      expect(startDate.getHours()).not.toBe(23);
      expect(
        endDate.getHours() < 23 || (endDate.getHours() === 23 && endDate.getMinutes() === 0),
      ).toBe(true);
      expect(endDate.getDate()).toBe(startDate.getDate());
    }
  });

  it('returns empty when the Day has no items', () => {
    const slots = freeSlots([], {
      nowMs: at(9, 0),
      ring: DEFAULT_RING,
    });
    expect(slots).toEqual([]);
  });

  it('ignores point reminders and all-day events', () => {
    const items: DayItem[] = [
      {
        id: 'r1',
        kind: 'reminder',
        title: 'Call',
        atMs: at(12, 0),
        endMs: null,
        allDay: false,
        peerId: null,
        overdue: false,
      },
      {
        id: 'a1',
        kind: 'event',
        title: 'Holiday',
        atMs: at(0, 0),
        endMs: at(23, 59),
        allDay: true,
        peerId: null,
        overdue: false,
      },
    ];
    expect(freeSlots(items, { nowMs: at(9, 0), ring: DEFAULT_RING })).toEqual([]);
  });

  it('rounds start up to 15-min step and drops gaps shorter than 30 min', () => {
    const items = [ev('e1', 10, 0, 11, 0), ev('e2', 11, 20, 12, 30)];
    const slots = freeSlots(items, {
      nowMs: at(9, 7),
      ring: { padMin: 0, aheadDays: 1 },
      max: 10,
    });

    expect(slots.length).toBeGreaterThan(0);
    expect(slots[0].startMs).toBe(at(9, 15));
    expect(slots.some((s) => s.startMs >= at(11, 0) && s.endMs <= at(11, 20))).toBe(false);
  });

  it('applies larger ring padding after an event', () => {
    const items = [ev('e1', 18, 0, 19, 30)];
    const slots = freeSlots(items, {
      nowMs: at(9, 0),
      ring: { padMin: 60, aheadDays: 1 },
      max: 10,
    });

    const afterEvent = slots.filter((s) => s.startMs >= at(19, 30));
    expect(afterEvent.length).toBeGreaterThan(0);
    expect(afterEvent[0].startMs).toBeGreaterThanOrEqual(at(20, 30));
  });
});

describe('freeSlots ring storage', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('round-trips per-chat ring and falls back to * then DEFAULT_RING', () => {
    expect(loadRing('c1')).toEqual(DEFAULT_RING);

    saveRing({ padMin: 15, aheadDays: 4 });
    saveRing({ padMin: 45, aheadDays: 3 }, 'c1');

    expect(loadRing('c1')).toEqual({ padMin: 45, aheadDays: 3 });
    expect(loadRing('other')).toEqual({ padMin: 15, aheadDays: 4 });
  });

  it('returns DEFAULT_RING when storage holds garbage', () => {
    localStorage.setItem(FREE_SLOTS_RING_KEY, '{not-json');
    expect(loadRing('c1')).toEqual(DEFAULT_RING);
  });

  it('clamps padMin to 0..120 and aheadDays to 1..7', () => {
    saveRing({ padMin: 999, aheadDays: 99 }, 'c1');
    expect(loadRing('c1')).toEqual({ padMin: 120, aheadDays: 7 });
  });
});

describe('wantsTime and slotDay', () => {
  const sentAt = new Date(2025, 8, 25, 9, 0);

  it('detects asks with a time cue including «давай завтра?»', () => {
    expect(wantsTime('коли ти вільний?', sentAt)).toBe(true);
    expect(wantsTime('when are you free?', sentAt)).toBe(true);
    expect(wantsTime('можеш завтра о 19:00?', sentAt)).toBe(true);
    expect(wantsTime('давай завтра?', sentAt)).toBe(true);
  });

  it('rejects asks without a time cue and non-asks', () => {
    expect(wantsTime('що купити?', sentAt)).toBe(false);
    expect(wantsTime('ок, до зустрічі', sentAt)).toBe(false);
    expect(wantsTime('', sentAt)).toBe(false);
  });

  it('classifies today, tomorrow and later by local calendar day', () => {
    const nowMs = at(9, 0, 0);
    expect(slotDay(at(14, 0, 0), nowMs)).toBe('today');
    expect(slotDay(at(10, 0, 1), nowMs)).toBe('tomorrow');
    expect(slotDay(at(10, 0, 2), nowMs)).toBe('later');
  });
});
