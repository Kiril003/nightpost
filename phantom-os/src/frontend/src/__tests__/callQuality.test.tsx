/**
 * Смужки якості на екрані дзвінка — з виміру, не з надії (P0-5, 08.10).
 *
 * Чотири смужки з RTT і втрат за останній проміжок getStats; «через
 * посередника» — коли обрана пара кандидатів іде через ретранслятор. Немає
 * виміру — немає смужок (чесний порожній стан), а не «повна шкала».
 *
 * Червоніє на коді до 08.10: смужок не було, картка не знала про relay.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from '@testing-library/react';
import type { CallSnapshot, CallStats } from '../services/callEngine';
import { readQuality } from '../components/messenger/call/quality';
import { QualityBars } from '../components/messenger/call/QualityBars';
import { CALL_JARGON } from '../services/callWords';

const stats = (patch: Partial<CallStats> = {}): CallStats => ({
  rttMs: 84,
  packetsLost: 0,
  audioCodec: 'opus',
  videoCodec: null,
  kbps: 64,
  localCandidate: 'srflx',
  remoteCandidate: 'srflx',
  lossPct: 0,
  ...patch,
});

describe('readQuality: вимір → смужки', () => {
  it('немає виміру — немає смужок', () => {
    expect(readQuality(null)).toBeNull();
    expect(readQuality(stats({ rttMs: null, lossPct: null }))).toBeNull();
    expect(readQuality(stats({ rttMs: null, lossPct: undefined }))).toBeNull();
  });

  it.each([
    ['84 мс, 0% — чотири', { rttMs: 84, lossPct: 0 }, 4],
    ['220 мс — три', { rttMs: 220, lossPct: 0 }, 3],
    ['420 мс — дві', { rttMs: 420, lossPct: 0.5 }, 2],
    ['900 мс — одна', { rttMs: 900, lossPct: 0 }, 1],
    ['50 мс, але 5% втрат — дві', { rttMs: 50, lossPct: 5 }, 2],
    ['50 мс, 12% втрат — одна', { rttMs: 50, lossPct: 12 }, 1],
    ['втрат ще не міряли, RTT добрий — чотири', { rttMs: 60, lossPct: null }, 4],
    // Пакети за проміжок не приїхали зовсім (Wi-Fi зник, ICE disconnected):
    // RTT пари — старий, добрий, і без цього рядка смужок було б чотири.
    ['пакети стоять, RTT старий і добрий — одна', { rttMs: 40, lossPct: null, stalled: true }, 1],
    ['пакети стоять, виміру RTT нема — одна', { rttMs: null, lossPct: null, stalled: true }, 1],
  ] as Array<[string, Partial<CallStats>, number]>)('%s', (_n, patch, bars) => {
    expect(readQuality(stats(patch))?.bars).toBe(bars);
  });

  it('relay з будь-якого боку пари — «через посередника»', () => {
    expect(readQuality(stats())?.relay).toBe(false);
    expect(readQuality(stats({ localCandidate: 'relay' }))?.relay).toBe(true);
    expect(readQuality(stats({ remoteCandidate: 'relay' }))?.relay).toBe(true);
  });
});

describe('QualityBars: що бачить людина', () => {
  afterEach(() => cleanup());

  it('рівно стільки світлих смужок, скільки дав вимір, і підпис людською мовою', () => {
    render(<QualityBars stats={stats({ rttMs: 420, lossPct: 0.5, remoteCandidate: 'relay' })} />);
    const el = document.querySelector('[data-call-quality]') as HTMLElement;
    expect(el.getAttribute('data-bars')).toBe('2');
    expect(el.querySelectorAll('[data-bar-on="yes"]')).toHaveLength(2);
    expect(el.querySelectorAll('[data-bar-on="no"]')).toHaveLength(2);
    expect(el.getAttribute('aria-label')).toMatch(/Якість звʼязку/);
    const relay = document.querySelector('[data-call-relay]');
    expect(relay?.textContent).toBe('через посередника');
    expect(document.body.textContent).not.toMatch(CALL_JARGON);
  });

  it('без relay — підпису немає; без виміру — нічого', () => {
    const { unmount } = render(<QualityBars stats={stats()} />);
    expect(document.querySelector('[data-call-relay]')).toBeNull();
    unmount();
    render(<QualityBars stats={null} />);
    expect(document.querySelector('[data-call-quality]')).toBeNull();
  });
});

/* ── досяжність: смужки стоять на справжній картці дзвінка ──────────── */

let snap: CallSnapshot;
vi.mock('../services/callEngine', async (orig) => {
  const real = await orig<typeof import('../services/callEngine')>();
  return {
    ...real,
    callEngine: {
      subscribe: () => () => undefined,
      getSnapshot: () => snap,
      toggleMic: vi.fn(),
      toggleSpeaker: vi.fn(),
      toggleCamera: vi.fn(),
      flipCamera: vi.fn(),
      hangup: vi.fn(),
      accept: vi.fn(),
      decline: vi.fn(),
      dropLeg: vi.fn(),
    },
  };
});

vi.mock('../services/api', () => ({
  request: vi.fn(async () => {
    const err = new Error('Not found') as Error & { status: number };
    err.status = 404;
    throw err;
  }),
  ApiError: class extends Error {},
}));

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
  stats: stats({ remoteCandidate: 'relay' }),
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

describe('CallOverlay несе смужки', () => {
  afterEach(() => cleanup());

  it('у розмові — смужки з виміру рушія і «через посередника»', async () => {
    snap = { ...BASE };
    const { CallOverlay } = await import('../components/messenger/CallOverlay');
    render(<CallOverlay />);
    const q = document.querySelector('[data-call-overlay] [data-call-quality]');
    expect(q?.getAttribute('data-bars')).toBe('4');
    expect(document.querySelector('[data-call-overlay] [data-call-relay]')).not.toBeNull();
  });

  /**
   * Досяжність перемикання камери: кнопка стоїть лише у відеодзвінку з
   * увімкненою камерою і кличе рушій (`flipCamera` → replaceTrack).
   */
  it('відеодзвінок — «Перевернути камеру» кличе callEngine.flipCamera; у голосовому кнопки нема', async () => {
    const { fireEvent } = await import('@testing-library/react');
    const { callEngine } = await import('../services/callEngine');
    const { CallOverlay } = await import('../components/messenger/CallOverlay');
    snap = { ...BASE };
    const { unmount } = render(<CallOverlay />);
    expect(document.querySelector('[aria-label="Перевернути камеру"]')).toBeNull();
    unmount();
    snap = { ...BASE, media: 'video', hasCamera: true, cameraOn: true };
    render(<CallOverlay />);
    const flip = document.querySelector('[aria-label="Перевернути камеру"]') as HTMLElement;
    expect(flip).not.toBeNull();
    fireEvent.click(flip);
    expect(callEngine.flipCamera).toHaveBeenCalledTimes(1);
  });

  it('до розмови і без виміру — смужок немає', async () => {
    const { CallOverlay } = await import('../components/messenger/CallOverlay');
    snap = { ...BASE, state: 'calling', startedAt: null };
    const { unmount } = render(<CallOverlay />);
    expect(document.querySelector('[data-call-quality]')).toBeNull();
    unmount();
    snap = { ...BASE, stats: null };
    render(<CallOverlay />);
    expect(document.querySelector('[data-call-quality]')).toBeNull();
  });
});
