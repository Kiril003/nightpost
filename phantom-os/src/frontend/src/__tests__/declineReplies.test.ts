import { describe, expect, it, vi } from 'vitest';
import type { DayItem, DayState } from '../services/dayApi';
import { busyUntil, declineReplies, hhmm, pick } from '../services/declineReplies';

const at = (h: number, m: number): number => new Date(2025, 9, 20, h, m).getTime();

const t = (key: string, params?: Record<string, string | number>): string =>
  `${key}|${params?.time ?? ''}`;

const makeItem = (overrides: Partial<DayItem> = {}): DayItem => ({
  id: 'item-1',
  kind: 'event',
  title: 'Зустріч',
  atMs: at(14, 0),
  endMs: at(15, 0),
  allDay: false,
  peerId: null,
  overdue: false,
  ...overrides,
});

const makeDay = (items: DayItem[]): DayState => ({ items, calendar: 'granted' });

describe('declineReplies', () => {
  it('formats local time as padded hh:mm', () => {
    expect(hhmm(at(9, 5))).toBe('09:05');
    expect(hhmm(at(15, 0))).toBe('15:00');
  });

  it('gives a busy chip with 15:00 for a running event 14:00–15:00 at 14:20', () => {
    const day = makeDay([makeItem({ id: 'ev-1', atMs: at(14, 0), endMs: at(15, 0) })]);
    const replies = declineReplies({ nowMs: at(14, 20), day, nav: null, t });
    const busy = replies.find((r) => r.kind === 'busy');
    expect(busy).toBeDefined();
    expect(busy?.text).toContain('15:00');
    expect(replies.map((r) => r.kind)).toEqual(['busy', 'later']);
  });

  it('picks the latest end time (16:30) across overlapping running events', () => {
    const day = makeDay([
      makeItem({ id: 'ev-1', atMs: at(14, 0), endMs: at(15, 0) }),
      makeItem({ id: 'ev-2', atMs: at(14, 10), endMs: at(16, 30) }),
    ]);
    expect(busyUntil(day, at(14, 20))).toBe(at(16, 30));
    const replies = declineReplies({ nowMs: at(14, 20), day, nav: null, t });
    const busy = replies.find((r) => r.kind === 'busy');
    expect(busy?.text).toContain('16:30');
  });

  it('gives no busy chip for all-day, already-ended, null-endMs, or non-event items', () => {
    const day = makeDay([
      makeItem({ id: 'all-day', atMs: at(0, 0), endMs: at(23, 59), allDay: true }),
      makeItem({ id: 'ended', atMs: at(12, 0), endMs: at(14, 20) }),
      makeItem({ id: 'open-end', atMs: at(14, 0), endMs: null }),
      makeItem({ id: 'promise', kind: 'promise', atMs: at(14, 0), endMs: at(16, 0) }),
      makeItem({ id: 'reminder', kind: 'reminder', atMs: at(14, 0), endMs: at(16, 0) }),
    ]);
    expect(busyUntil(day, at(14, 20))).toBeNull();
    const replies = declineReplies({ nowMs: at(14, 20), day, nav: null, t });
    expect(replies.find((r) => r.kind === 'busy')).toBeUndefined();
    expect(replies.map((r) => r.kind)).toEqual(['later']);
  });

  it('returns only later when day is null and nav is null', () => {
    const replies = declineReplies({ nowMs: at(14, 20), day: null, nav: null, t });
    expect(replies.map((r) => r.kind)).toEqual(['later']);
  });

  it('gives no driving chip when nav is null', () => {
    const day = makeDay([makeItem()]);
    const replies = declineReplies({ nowMs: at(14, 20), day, nav: null, t });
    expect(replies.find((r) => r.kind === 'driving')).toBeUndefined();
  });

  it('places driving first with arrival time when nav is in the future', () => {
    const replies = declineReplies({
      nowMs: at(18, 10),
      day: null,
      nav: { arriveAtMs: at(18, 40) },
      t,
    });
    expect(replies[0].kind).toBe('driving');
    expect(replies[0].text).toContain('18:40');
    expect(replies.map((r) => r.kind)).toEqual(['driving', 'later']);
  });

  it('gives no driving chip when nav arrival is in the past', () => {
    const replies = declineReplies({
      nowMs: at(14, 20),
      day: null,
      nav: { arriveAtMs: at(13, 50) },
      t,
    });
    expect(replies.find((r) => r.kind === 'driving')).toBeUndefined();
    expect(replies.map((r) => r.kind)).toEqual(['later']);
  });

  it('always includes later as the last reply in driving, busy, later order', () => {
    const day = makeDay([makeItem({ id: 'ev-1', atMs: at(14, 0), endMs: at(15, 0) })]);
    const both = declineReplies({
      nowMs: at(14, 20),
      day,
      nav: { arriveAtMs: at(14, 45) },
      t,
    });
    expect(both.map((r) => r.kind)).toEqual(['driving', 'busy', 'later']);
    expect(both[both.length - 1].kind).toBe('later');
  });

  it('does not call send when building replies and calls send once on pick()', async () => {
    const send = vi.fn();
    const day = makeDay([makeItem({ id: 'ev-1', atMs: at(14, 0), endMs: at(15, 0) })]);
    const first = declineReplies({ nowMs: at(14, 20), day, nav: null, t });
    const second = declineReplies({ nowMs: at(14, 20), day, nav: null, t });
    expect(first).toHaveLength(2);
    expect(second).toHaveLength(2);
    expect(send).not.toHaveBeenCalled();

    await pick(first[0], send);
    expect(send).toHaveBeenCalledTimes(1);
    expect(send).toHaveBeenCalledWith(first[0].text);
  });
});
