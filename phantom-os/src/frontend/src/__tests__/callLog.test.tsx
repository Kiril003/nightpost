/**
 * P09 — журнал дзвінків: рядок у розмові й Я › Дзвінки.
 *
 * Червоніти вміє: приберіть `installCallLog` чи `callLineFrom` — падає «кінець
 * дзвінка — один рядок із тривалістю»; приберіть гілку `missed` — падає
 * «пропущений»; приберіть `newestFirst` — падає порядок; поверніть me-calls
 * прямо в налаштування — падає «Передзвонити».
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup, within } from '@testing-library/react';
import type { CallSnapshot } from '../services/callEngine';
import { REASON } from '../services/callReasons';
import { callLineRows, installCallLog, newestFirst, useCallLog, withCallLines, type CallLogEntry } from '../services/callLog';
import { CallLogSheet } from '../components/messenger/CallLogSheet';
import { CallLineRow } from '../components/messenger/feed/CallLineRow';
import type { Message } from '../types/messenger';

const peer = { peerNodeId: 'node-taras', displayName: 'Тарас' };

const snap = (over: Partial<CallSnapshot>): CallSnapshot =>
  ({
    state: 'idle',
    callId: 'c1',
    peer,
    media: 'audio',
    outgoing: true,
    startedAt: null,
    endedReason: null,
    ...over,
  }) as CallSnapshot;

class FakeEngine {
  private s: CallSnapshot = snap({});
  private cbs = new Set<() => void>();
  subscribe = (cb: () => void) => {
    this.cbs.add(cb);
    return () => this.cbs.delete(cb);
  };
  getSnapshot = () => this.s;
  set(next: CallSnapshot) {
    this.s = next;
    this.cbs.forEach((cb) => cb());
  }
}

beforeEach(() => {
  localStorage.clear();
  useCallLog.setState({ entries: [] });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('call log', () => {
  it('кінець дзвінка — один рядок із тривалістю', () => {
    const engine = new FakeEngine();
    let now = 1_000_000;
    const stop = installCallLog(engine, () => now);
    engine.set(snap({ state: 'calling' }));
    engine.set(snap({ state: 'active', startedAt: now }));
    now += 125_000;
    engine.set(snap({ state: 'ended', startedAt: 1_000_000, endedReason: REASON.finished }));
    engine.set(snap({ state: 'ended', startedAt: 1_000_000, endedReason: `${REASON.finished}!` }));
    engine.set(snap({ state: 'idle' }));
    stop();

    const { entries } = useCallLog.getState();
    expect(entries).toHaveLength(1);
    expect(entries[0]).toMatchObject({ kind: 'out', durationSec: 125, name: 'Тарас', peerNodeId: 'node-taras' });
    expect(JSON.parse(localStorage.getItem('phantom.callLog.v1') ?? '[]')).toHaveLength(1);

    const rows = callLineRows(entries, { id: 'chat-taras', peerNodeId: 'node-taras' });
    expect(rows).toHaveLength(1);
    expect(callLineRows(entries, { id: 'chat-olena', peerNodeId: 'node-olena' })).toHaveLength(0);
    render(<CallLineRow id={rows[0].id} line={rows[0].callLine!} />);
    expect(screen.getByTestId('call-line').textContent).toBe('Вихідний дзвінок · 2:05');
  });

  it('вхідний без відповіді — пропущений; відхилений — відхилений', () => {
    const engine = new FakeEngine();
    let now = 5_000;
    installCallLog(engine, () => now);
    engine.set(snap({ state: 'ringing', outgoing: false, callId: 'm1' }));
    now += 30_000;
    engine.set(snap({ state: 'ended', outgoing: false, callId: 'm1', endedReason: 'співрозмовник поклав слухавку' }));
    engine.set(snap({ state: 'ringing', outgoing: false, callId: 'm2' }));
    now += 1_000;
    engine.set(snap({ state: 'ended', outgoing: false, callId: 'm2', endedReason: REASON.declined }));

    const kinds = useCallLog.getState().entries.map((e) => [e.callId, e.kind, e.durationSec]);
    expect(kinds).toEqual([
      ['m2', 'declined', 0],
      ['m1', 'missed', 0],
    ]);
    render(<CallLineRow id="x" line={{ kind: 'missed', media: 'audio', durationSec: 0 }} />);
    expect(screen.getByTestId('call-line').textContent).toBe('Пропущений дзвінок');
  });

  it('список — найновіші зверху, рядки стають між листами за часом', () => {
    const e = (id: string, at: number): CallLogEntry => ({ id, kind: 'in', media: 'audio', at, durationSec: 3, name: 'Т', peerNodeId: 'n' });
    expect(newestFirst([e('a', 1), e('c', 3), e('b', 2)]).map((x) => x.id)).toEqual(['c', 'b', 'a']);
    useCallLog.getState().add(e('old', Date.parse('2026-10-29T10:00:00Z')));
    useCallLog.getState().add(e('new', Date.parse('2026-10-29T12:00:00Z')));
    expect(useCallLog.getState().entries.map((x) => x.id)).toEqual(['new', 'old']);

    const msg = (id: string, sentAt: string) => ({ id, sentAt, type: 'text' }) as Message;
    const feed = withCallLines(
      [msg('m1', '2026-10-29T09:00:00.000Z'), msg('m2', '2026-10-29T11:00:00.000Z')],
      callLineRows(useCallLog.getState().entries, { id: 'n' }),
    );
    expect(feed.map((m) => m.id)).toEqual(['m1', 'call-line-old', 'm2', 'call-line-new']);
  });

  it('Я › Дзвінки: список із «Передзвонити», без автонабору', () => {
    useCallLog.getState().add({ id: 'k1', kind: 'missed', media: 'video', at: 2_000, durationSec: 0, name: 'Олена', peerNodeId: 'node-olena' });
    useCallLog.getState().add({ id: 'k2', kind: 'out', media: 'audio', at: 3_000, durationSec: 61, name: 'Тарас', peerNodeId: 'node-taras' });
    const dial = vi.fn();
    window.addEventListener('phantom:start-call', dial);
    const onClose = vi.fn();
    render(<CallLogSheet onClose={onClose} onSettings={vi.fn()} />);
    const rows = screen.getAllByTestId('call-log-row');
    expect(rows.map((r) => r.getAttribute('data-call-kind'))).toEqual(['out', 'missed']);
    expect(within(rows[0]).getByText(/Вихідний дзвінок · 1:01/)).toBeTruthy();
    expect(dial).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId('call-back-k1'));
    expect(dial).toHaveBeenCalledTimes(1);
    expect((dial.mock.calls[0][0] as CustomEvent).detail).toMatchObject({ peerNodeId: 'node-olena', video: true });
    window.removeEventListener('phantom:start-call', dial);
  });

  it('порожній журнал — чесний порожній стан', () => {
    render(<CallLogSheet onClose={vi.fn()} onSettings={vi.fn()} />);
    expect(screen.getByTestId('call-log-empty')).toBeTruthy();
  });
});
