import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';
import type { CallSnapshot } from '../services/callEngine';

let snap: CallSnapshot;
const listeners = new Set<() => void>();
vi.mock('../services/callEngine', async (orig) => {
  const real = await orig<typeof import('../services/callEngine')>();
  return {
    ...real,
    callEngine: {
      subscribe: (cb: () => void) => {
        listeners.add(cb);
        return () => listeners.delete(cb);
      },
      getSnapshot: () => snap,
      dataUsedBytes: vi.fn(async () => null),
    },
  };
});

const today = vi.fn(async () => ({ items: [] }));
vi.mock('../services/dayApi', async (orig) => {
  const real = await orig<typeof import('../services/dayApi')>();
  return { ...real, dayApi: { ...real.dayApi, today: () => today() } };
});

const BASE: CallSnapshot = {
  resuming: false,
  state: 'active',
  callId: 'c1',
  peer: { peerNodeId: 'f0e1d2c3b4a59687', displayName: 'Марта', verified: true },
  media: 'audio',
  outgoing: true,
  micOn: true,
  cameraOn: false,
  hasCamera: false,
  localStream: null,
  remoteStream: null,
  startedAt: Date.now() - 65_000,
  stats: null,
  endedReason: null,
  stall: null,
  audioLevel: 'crystal',
  ladderPinned: false,
  videoDropped: false,
  radio: null,
  radioReason: null,
  linkNote: null,
  legs: [],
  roomMax: null,
  roomCrowded: false,
  speakerOn: true,
  served: null,
  weakSide: null,
};

describe('CallOverlay: наступна подія з календаря', () => {
  afterEach(() => cleanup());

  it('читає день, коли дзвінок почався, а не на старті застосунку', async () => {
    snap = { ...BASE, state: 'idle' };
    const { CallOverlay } = await import('../components/messenger/CallOverlay');
    render(<CallOverlay />);
    expect(today).not.toHaveBeenCalled();
    snap = { ...BASE, state: 'active' };
    act(() => listeners.forEach((cb) => cb()));
    await vi.waitFor(() => expect(today).toHaveBeenCalledTimes(1));
  });
});
