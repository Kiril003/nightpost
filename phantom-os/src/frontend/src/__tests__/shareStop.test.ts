import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('../services/api', () => ({ request: vi.fn() }));

import { request } from '../services/api';
import {
  onStopPhase,
  onWhereChanged,
  stopPhaseOf,
  stopShare,
  whereApi,
  type StopPhase,
  type WhereState,
} from '../services/peerWhere';

const requestMock = vi.mocked(request);

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const makeState = (mine: WhereState['mine']): WhereState => ({
  now_ms: 1_700_000_000_000,
  eligible: true,
  why_not: null,
  mine,
  theirs: null,
});

describe('peerWhere stopShare phase store', () => {
  afterEach(() => {
    vi.useRealTimers();
    requestMock.mockReset();
  });

  it('sends DELETE once for a peer and deduplicates a second tap while pending', async () => {
    const del = deferred<WhereState>();
    requestMock.mockReturnValueOnce(del.promise);

    const first = stopShare('peer/1');
    const second = stopShare('peer/1');

    expect(second).toBe(first);
    expect(requestMock).toHaveBeenCalledTimes(1);
    expect(requestMock).toHaveBeenCalledWith('DELETE', '/messenger/where/peer%2F1');

    del.resolve(makeState(null));
    await first;
  });

  it('stays in stopping while pending even when timers advance 10s', async () => {
    vi.useFakeTimers();
    const del = deferred<WhereState>();
    requestMock.mockReturnValueOnce(del.promise);

    const pending = stopShare('peer/timer');
    expect(stopPhaseOf('peer/timer')).toBe('stopping');

    vi.advanceTimersByTime(10_000);
    expect(stopPhaseOf('peer/timer')).toBe('stopping');

    del.resolve(makeState(null));
    await pending;
    expect(stopPhaseOf('peer/timer')).toBe('shut');
  });

  it('transitions to shut and notifies phase and where listeners when ack has mine: null', async () => {
    const del = deferred<WhereState>();
    requestMock.mockReturnValueOnce(del.promise);

    const phaseSpy = vi.fn<(peerId: string, phase: StopPhase) => void>();
    const whereSpy = vi.fn<() => void>();
    const offPhase = onStopPhase(phaseSpy);
    const offWhere = onWhereChanged(whereSpy);

    const pending = stopShare('peer/ack');
    expect(phaseSpy).toHaveBeenCalledWith('peer/ack', 'stopping');
    expect(whereSpy).not.toHaveBeenCalled();

    del.resolve(makeState(null));
    await pending;

    expect(stopPhaseOf('peer/ack')).toBe('shut');
    expect(phaseSpy).toHaveBeenLastCalledWith('peer/ack', 'shut');
    expect(whereSpy).toHaveBeenCalledTimes(1);

    offPhase();
    offWhere();
  });

  it('returns to live when ack still has mine present and resets to live on re-share', async () => {
    const del = deferred<WhereState>();
    requestMock.mockReturnValueOnce(del.promise);

    const pending = stopShare('peer/still-live');
    del.resolve(makeState({ precision: 'district', until_ms: null, stand: false }));
    await pending;

    expect(stopPhaseOf('peer/still-live')).toBe('live');

    const delShut = deferred<WhereState>();
    requestMock.mockReturnValueOnce(delShut.promise);
    const pShut = stopShare('peer/still-live');
    delShut.resolve(makeState(null));
    await pShut;
    expect(stopPhaseOf('peer/still-live')).toBe('shut');

    requestMock.mockResolvedValueOnce(makeState({ precision: 'quarter', until_ms: null, stand: false }));
    await whereApi.show('peer/still-live', 'quarter', 15);
    expect(stopPhaseOf('peer/still-live')).toBe('live');
  });

  it('returns to live and rejects when DELETE fails', async () => {
    const del = deferred<WhereState>();
    requestMock.mockReturnValueOnce(del.promise);

    const pending = stopShare('peer/fail');
    expect(stopPhaseOf('peer/fail')).toBe('stopping');

    del.reject(new Error('network error'));
    await expect(pending).rejects.toThrow('network error');
    expect(stopPhaseOf('peer/fail')).toBe('live');
  });
});
