import { describe, expect, it } from 'vitest';
import type { DayItem } from '../services/dayApi';
import { CALL_NEXT_WINDOW_MS, callNextLabel, nextEventFor } from '../services/callHeader';

const NOW = new Date(2025, 0, 10, 14, 46).getTime();

function makeItem(overrides: Partial<DayItem> & Pick<DayItem, 'id' | 'atMs'>): DayItem {
  return {
    id: overrides.id,
    kind: overrides.kind ?? 'event',
    title: overrides.title ?? 'Sync',
    atMs: overrides.atMs,
    endMs: overrides.endMs !== undefined ? overrides.endMs : overrides.atMs + 30 * 60_000,
    allDay: overrides.allDay ?? false,
    peerId: overrides.peerId !== undefined ? overrides.peerId : null,
    overdue: overrides.overdue ?? false,
  };
}

describe('callHeader', () => {
  it('returns next event within 2h and formats the label', () => {
    const atMs = new Date(2025, 0, 10, 15, 0).getTime();
    const item = makeItem({ id: 'ev-1', title: 'Standup', atMs });
    const next = nextEventFor([item], NOW);
    expect(next).toEqual({
      id: 'ev-1',
      title: 'Standup',
      atMs,
      hhmm: '15:00',
      minutes: 14,
    });
    expect(
      callNextLabel(next!, (k) => ({ 'callNext.next': 'Next', 'callNext.min': 'min' })[k] ?? k),
    ).toBe('Next · 15:00 · 14 min');
  });

  it('returns null when the only event is 2h 1m ahead', () => {
    const item = makeItem({ id: 'ev-far', atMs: NOW + 121 * 60_000 });
    expect(nextEventFor([item], NOW)).toBeNull();
  });

  it('returns an event exactly at NOW + CALL_NEXT_WINDOW_MS', () => {
    const atMs = NOW + CALL_NEXT_WINDOW_MS;
    const item = makeItem({ id: 'ev-edge', title: 'Boundary', atMs });
    const next = nextEventFor([item], NOW);
    expect(next).toEqual({
      id: 'ev-edge',
      title: 'Boundary',
      atMs,
      hhmm: '16:46',
      minutes: 120,
    });
  });

  it('returns null for an empty list', () => {
    expect(nextEventFor([], NOW)).toBeNull();
  });

  it('returns null for an allDay event 10 min ahead', () => {
    const item = makeItem({ id: 'ev-allday', atMs: NOW + 10 * 60_000, allDay: true });
    expect(nextEventFor([item], NOW)).toBeNull();
  });

  it('returns null for an event that started 5 min ago', () => {
    const item = makeItem({ id: 'ev-past', atMs: NOW - 5 * 60_000 });
    expect(nextEventFor([item], NOW)).toBeNull();
  });

  it('returns null for a promise and a reminder 10 min ahead', () => {
    const promiseItem = makeItem({ id: 'pr-1', kind: 'promise', atMs: NOW + 10 * 60_000 });
    const reminderItem = makeItem({ id: 'rem-1', kind: 'reminder', atMs: NOW + 10 * 60_000 });
    expect(nextEventFor([promiseItem, reminderItem], NOW)).toBeNull();
  });

  it('picks the earlier event when two events are at +40 min and +14 min', () => {
    const later = makeItem({ id: 'ev-40', title: 'Later', atMs: NOW + 40 * 60_000 });
    const sooner = makeItem({
      id: 'ev-14',
      title: 'Sooner',
      atMs: new Date(2025, 0, 10, 15, 0).getTime(),
    });
    const next = nextEventFor([later, sooner], NOW);
    expect(next?.id).toBe('ev-14');
    expect(next?.hhmm).toBe('15:00');
    expect(next?.minutes).toBe(14);
  });

  it('ceils an event 30s ahead to 1 minute', () => {
    const item = makeItem({ id: 'ev-30s', atMs: NOW + 30_000 });
    const next = nextEventFor([item], NOW);
    expect(next?.minutes).toBe(1);
  });
});
