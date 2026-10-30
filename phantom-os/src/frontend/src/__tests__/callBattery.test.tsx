import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { CallSnapshot } from '../services/callEngine';
import { CALL_JARGON } from '../services/callWords';
import { capVideoSender } from '../services/callVideo';
import { keepOwnVideo, localVideoCap, ownBatteryCap, ownBatteryOff, watchDevice } from '../services/callView';
import { tNow } from '../components/chat/tNow';

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
      keepVideoOnLowBattery: () => {
        real.callEngine.keepVideoOnLowBattery();
        snap = { ...snap, battOff: ownBatteryOff() };
        listeners.forEach((l) => l());
      },
      toggleMic: vi.fn(),
      toggleSpeaker: vi.fn(),
      toggleCamera: vi.fn(),
      hangup: vi.fn(),
      accept: vi.fn(),
      decline: vi.fn(),
      dropLeg: vi.fn(),
    },
  };
});

const BASE: CallSnapshot = {
  resuming: false,
  state: 'active',
  callId: 'c1',
  peer: { peerNodeId: 'f0e1d2c3b4a59687', displayName: 'Марта', verified: true },
  media: 'video',
  outgoing: true,
  micOn: true,
  cameraOn: true,
  hasCamera: true,
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
};

const fakeSender = () => {
  let settings = { width: 1280, height: 720 };
  let params = { encodings: [{}], transactionId: 't', codecs: [], headerExtensions: [], rtcp: {} } as unknown as RTCRtpSendParameters;
  const track = {
    kind: 'video',
    applyConstraints: vi.fn(async (c: MediaTrackConstraints) => {
      const h = (c.height as ConstrainULongRange).ideal ?? 720;
      settings = { width: Math.round((h * 16) / 9), height: h };
    }),
    getSettings: () => settings,
  };
  const sender = {
    track,
    getParameters: () => JSON.parse(JSON.stringify(params)),
    setParameters: vi.fn(async (p: RTCRtpSendParameters) => {
      params = p;
    }),
  };
  return { sender: sender as unknown as RTCRtpSender, track, last: () => params };
};

const battery = Object.assign(new EventTarget(), { level: 0.8, charging: false });
const setBattery = (level: number, charging = false) => {
  battery.level = level;
  battery.charging = charging;
  battery.dispatchEvent(new Event('levelchange'));
};
const onChange = vi.fn();

beforeAll(async () => {
  Object.defineProperty(navigator, 'getBattery', { configurable: true, value: async () => battery });
  watchDevice(onChange);
  await Promise.resolve();
  await Promise.resolve();
});

afterEach(() => {
  cleanup();
  keepOwnVideo(false);
});

describe('media-27: своя батарея відправника', () => {
  it('межі: <15 % без зарядки — 360p, <5 % — відео стоїть, на зарядці — без стелі', () => {
    expect(ownBatteryCap({ level: 0.12, charging: false })).toEqual({ height: 360, off: false });
    expect(ownBatteryCap({ level: 0.04, charging: false })).toEqual({ height: null, off: true });
    expect(ownBatteryCap({ level: 0.04, charging: true })).toEqual({ height: null, off: false });
    expect(ownBatteryCap({ level: 0.5, charging: false })).toEqual({ height: null, off: false });
    expect(ownBatteryCap(null)).toEqual({ height: null, off: false });
  });

  it('12 % — камера знімає не вище 360p, енкодер живий', async () => {
    setBattery(0.12);
    expect(onChange).toHaveBeenCalled();
    expect(localVideoCap()).toBeLessThanOrEqual(360);
    const { sender, track, last } = fakeSender();
    await capVideoSender(sender);
    expect((track.applyConstraints.mock.calls.at(-1)?.[0].height as ConstrainULongRange).ideal).toBeLessThanOrEqual(360);
    expect(last().encodings[0].active).toBe(true);
  });

  it('4 % — енкодер відео стоїть; поставили на зарядку — знову йде', async () => {
    setBattery(0.04);
    expect(ownBatteryOff()).toBe(true);
    const { sender, last } = fakeSender();
    await capVideoSender(sender);
    expect(last().encodings[0].active).toBe(false);

    setBattery(0.04, true);
    await capVideoSender(sender);
    expect(last().encodings[0].active).toBe(true);
  });

  it('екран дзвінка каже людськими словами, чому відео немає', async () => {
    snap = { ...BASE, battOff: true };
    const { CallOverlay } = await import('../components/messenger/CallOverlay');
    render(React.createElement(CallOverlay));
    const line = tNow('callWords.battOff');
    expect(screen.getByText(line)).toBeTruthy();
    expect(line).not.toMatch(CALL_JARGON);
  });

  it('без battOff рядка немає', async () => {
    snap = { ...BASE };
    const { CallOverlay } = await import('../components/messenger/CallOverlay');
    render(React.createElement(CallOverlay));
    expect(screen.queryByText(tNow('callWords.battOff'))).toBeNull();
  });

  it('тап по рядку — відео знову йде в цьому дзвінку, рядок зникає', async () => {
    setBattery(0.04);
    snap = { ...BASE, battOff: true };
    const { CallOverlay } = await import('../components/messenger/CallOverlay');
    render(React.createElement(CallOverlay));
    const pill = screen.getByTestId('call-batt-keep');
    expect(pill.className).toContain('min-h-[48px]');
    expect(pill.textContent).toBe(tNow('callWords.battOff'));

    act(() => {
      fireEvent.click(pill);
    });
    expect(screen.queryByText(tNow('callWords.battOff'))).toBeNull();
    const { sender, last } = fakeSender();
    await capVideoSender(sender);
    expect(last().encodings[0].active).toBe(true);
  });

  it('новий дзвінок — знову за батареєю: перемикач живе лише до кінця розмови', async () => {
    setBattery(0.04);
    keepOwnVideo(true);
    expect(ownBatteryOff()).toBe(false);
    keepOwnVideo(false);
    expect(ownBatteryOff()).toBe(true);
  });
});
