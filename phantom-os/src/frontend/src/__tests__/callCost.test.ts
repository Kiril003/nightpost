import { beforeEach, describe, expect, it } from 'vitest';
import {
  CALL_COST_KEY,
  callCostEstimate,
  estimateMB10,
  loadCallCost,
  recordCallSample,
  trackCallCost,
} from '../services/callCost';

describe('callCost service', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('computes 10-minute median estimates only with at least 3 samples', () => {
    expect(estimateMB10([4, 5])).toBeNull();
    expect(estimateMB10([3, 4.5, 9])).toBe(45);
    expect(estimateMB10([2, 4, 6, 100])).toBe(50);
  });

  it('records samples per network and kind independently', () => {
    recordCallSample('wifi', 'video', 4.5);
    recordCallSample('wifi', 'video', 4.5);
    recordCallSample('wifi', 'video', 4.5);

    expect(callCostEstimate('wifi', 'video')).toBe(45);
    expect(callCostEstimate('mobile', 'video')).toBeNull();
    expect(callCostEstimate('wifi', 'audio')).toBeNull();
  });

  it('handles corrupted storage and caps history at 20 samples', () => {
    localStorage.setItem(CALL_COST_KEY, '{not-valid-json');
    expect(loadCallCost()).toEqual({
      wifi: { audio: [], video: [] },
      mobile: { audio: [], video: [] },
    });

    for (let i = 1; i <= 21; i += 1) {
      recordCallSample('mobile', 'audio', i);
    }

    const stored = loadCallCost().mobile.audio;
    expect(stored).toHaveLength(20);
    expect(stored[0]).toBe(2);
    expect(stored[19]).toBe(21);
  });

  it('tracks active calls and ignores calls shorter than 30 seconds', async () => {
    const listeners = new Set<() => void>();
    let snap = { state: 'idle', audioOnly: false };
    const readings = [0, 45_000_000, 0, 5_000_000];
    let nowMs = 1_000_000;

    const unsub = trackCallCost({
      subscribe: (cb) => {
        listeners.add(cb);
        return () => listeners.delete(cb);
      },
      getSnapshot: () => snap,
      readBytes: () => Promise.resolve(readings.shift() ?? 0),
      now: () => nowMs,
      conn: () => ({ type: 'wifi' }),
    });

    snap = { state: 'active', audioOnly: false };
    listeners.forEach((cb) => cb());
    await Promise.resolve();

    nowMs += 10 * 60_000;
    snap = { state: 'ended', audioOnly: false };
    listeners.forEach((cb) => cb());
    await new Promise((r) => setTimeout(r, 0));

    expect(loadCallCost().wifi.video).toEqual([4.5]);

    snap = { state: 'active', audioOnly: false };
    listeners.forEach((cb) => cb());
    await Promise.resolve();

    nowMs += 10_000;
    snap = { state: 'ended', audioOnly: false };
    listeners.forEach((cb) => cb());
    await new Promise((r) => setTimeout(r, 0));

    expect(loadCallCost().wifi.video).toEqual([4.5]);
    unsub();
  });

  it('drops the sample when the start counter could not be read', async () => {
    const listeners = new Set<() => void>();
    let snap = { state: 'idle', audioOnly: true };
    const readings: Array<number | null> = [null, 30_000_000];
    let nowMs = 1_000_000;

    const unsub = trackCallCost({
      subscribe: (cb) => {
        listeners.add(cb);
        return () => listeners.delete(cb);
      },
      getSnapshot: () => snap,
      readBytes: () => Promise.resolve(readings.shift() ?? null),
      now: () => nowMs,
      conn: () => ({ type: 'wifi' }),
    });

    snap = { state: 'active', audioOnly: true };
    listeners.forEach((cb) => cb());
    await Promise.resolve();

    nowMs += 10 * 60_000;
    snap = { state: 'ended', audioOnly: true };
    listeners.forEach((cb) => cb());
    await new Promise((r) => setTimeout(r, 0));

    expect(loadCallCost().wifi.audio).toEqual([]);
    unsub();
  });
});
