import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import { callEngine } from '../services/callEngine';
import type { CallSnapshot } from '../services/callEngine';
import { CallOverlay } from '../components/messenger/CallOverlay';

interface FakeSender {
  track: MediaStreamTrack | null;
  replaceTrack: (t: MediaStreamTrack | null) => Promise<void>;
  getParameters: () => RTCRtpSendParameters;
  setParameters: (p: RTCRtpSendParameters) => Promise<void>;
}
interface FakeTx {
  sender: FakeSender;
  direction: RTCRtpTransceiverDirection;
}

class FakeStream {
  constructor(public tracks: MediaStreamTrack[] = []) {}
  getTracks = () => this.tracks;
}

let bytes = 0;
const fakePc = () => {
  const txs: FakeTx[] = [];
  const pc = {
    signalingState: 'stable',
    localDescription: { sdp: 'v=0' },
    addTrack: vi.fn((track: MediaStreamTrack) => {
      const sender: FakeSender = {
        track,
        replaceTrack: vi.fn(async (t: MediaStreamTrack | null) => {
          sender.track = t;
        }),
        getParameters: () => ({ encodings: [{}], transactionId: 't', codecs: [], headerExtensions: [], rtcp: {} }),
        setParameters: vi.fn(async () => undefined),
      };
      txs.push({ sender, direction: 'sendrecv' });
      return sender;
    }),
    getTransceivers: () => txs,
    getSenders: () => txs.map((t) => t.sender),
    createOffer: vi.fn(async () => ({ type: 'offer', sdp: 'v=0' })),
    setLocalDescription: vi.fn(async () => undefined),
    getStats: async () => new Map([['t', { type: 'transport', bytesSent: bytes, bytesReceived: 0 }]]),
  };
  return { pc, txs };
};

const camTrack = () => ({ kind: 'video', stop: vi.fn(), enabled: true }) as unknown as MediaStreamTrack & { stop: ReturnType<typeof vi.fn> };
const patch = (p: Partial<CallSnapshot>) => (Reflect.get(callEngine, 'patch') as (p: Partial<CallSnapshot>) => void).call(callEngine, p);
const videoSenders = (pc: { getSenders: () => FakeSender[] }) => pc.getSenders().filter((s) => s.track?.kind === 'video');

beforeEach(() => {
  vi.stubGlobal('MediaStream', FakeStream);
  Reflect.set(callEngine, 'postTo', vi.fn(async () => ({ delivered: true })));
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'en' } }));
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  Reflect.set(callEngine, 'pc', null);
  Reflect.set(callEngine, 'peekTrack', null);
  Reflect.set(callEngine, 'peekTx', null);
  Reflect.deleteProperty(callEngine, 'postTo');
  patch({ state: 'idle', media: 'audio', peeking: false, peekBytes: null, callId: null });
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
});

describe('media-19: тримай — камера в голосовому дзвінку', () => {
  it('тримають — відеодоріжка йде; відпустили — знята, дзвінок голосовий, байти показу пораховано', async () => {
    const { pc, txs } = fakePc();
    Reflect.set(callEngine, 'pc', pc);
    patch({ state: 'active', media: 'audio', callId: 'c1', hasCamera: false });
    const track = camTrack();
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia: vi.fn(async () => ({ getVideoTracks: () => [track] })) },
    });

    bytes = 1_000;
    await callEngine.peekCamera(true);
    expect(videoSenders(pc)).toHaveLength(1);
    expect(callEngine.getSnapshot().peeking).toBe(true);
    expect(pc.createOffer).toHaveBeenCalledTimes(1);

    bytes = 251_000;
    await callEngine.peekCamera(false);
    expect(videoSenders(pc)).toHaveLength(0);
    expect(track.stop).toHaveBeenCalled();
    expect(txs[0].direction).toBe('recvonly');
    expect(pc.createOffer).toHaveBeenCalledTimes(2);
    const snap = callEngine.getSnapshot();
    expect(snap.media).toBe('audio');
    expect(snap.hasCamera).toBe(false);
    expect(snap.peeking).toBe(false);
    expect(snap.peekBytes).toBe(250_000);
  });

  it('другий показ — та сама відеолінія, нової не додає', async () => {
    const { pc, txs } = fakePc();
    Reflect.set(callEngine, 'pc', pc);
    patch({ state: 'active', media: 'audio', callId: 'c1' });
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia: vi.fn(async () => ({ getVideoTracks: () => [camTrack()] })) },
    });
    await callEngine.peekCamera(true);
    await callEngine.peekCamera(false);
    await callEngine.peekCamera(true);
    expect(pc.addTrack).toHaveBeenCalledTimes(1);
    expect(txs).toHaveLength(1);
    expect(txs[0].direction).toBe('sendrecv');
    expect(videoSenders(pc)).toHaveLength(1);
  });

  it('відпустили раніше, ніж відкрилась камера, — камеру закрито, доріжки немає', async () => {
    const { pc } = fakePc();
    Reflect.set(callEngine, 'pc', pc);
    patch({ state: 'active', media: 'audio', callId: 'c1' });
    const track = camTrack();
    let open: () => void = () => undefined;
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: {
        getUserMedia: vi.fn(
          () => new Promise((resolve) => (open = () => resolve({ getVideoTracks: () => [track] }))),
        ),
      },
    });
    const peek = callEngine.peekCamera(true);
    await callEngine.peekCamera(false);
    open();
    await peek;
    expect(track.stop).toHaveBeenCalled();
    expect(pc.addTrack).not.toHaveBeenCalled();
  });

  it('пропозиція не доїхала — камеру одразу знято', async () => {
    const { pc } = fakePc();
    Reflect.set(callEngine, 'pc', pc);
    Reflect.set(callEngine, 'postTo', vi.fn(async () => ({ delivered: false })));
    patch({ state: 'active', media: 'audio', callId: 'c1' });
    const track = camTrack();
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia: vi.fn(async () => ({ getVideoTracks: () => [track] })) },
    });
    await callEngine.peekCamera(true);
    expect(track.stop).toHaveBeenCalled();
    expect(callEngine.getSnapshot().peeking).toBe(false);
    expect(videoSenders(pc)).toHaveLength(0);
  });

  it('у відеодзвінку показ нічого не робить', async () => {
    const { pc } = fakePc();
    Reflect.set(callEngine, 'pc', pc);
    patch({ state: 'active', media: 'video', callId: 'c1' });
    await callEngine.peekCamera(true);
    expect(pc.addTrack).not.toHaveBeenCalled();
  });

  it('кнопка в голосовому дзвінку: натиснули — показ, відпустили — кінець, потім МБ показу', () => {
    const spy = vi.spyOn(callEngine, 'peekCamera').mockResolvedValue(undefined);
    patch({
      state: 'active',
      media: 'audio',
      callId: 'c1',
      hasCamera: false,
      startedAt: Date.now(),
      peer: { peerNodeId: 'f0e1d2c3b4a59687', displayName: 'Марта', verified: true },
    });
    render(React.createElement(CallOverlay));
    const btn = document.querySelector('[data-call-peek]') as HTMLElement;
    expect(btn).toBeTruthy();
    fireEvent.pointerDown(btn);
    expect(spy).toHaveBeenLastCalledWith(true);
    fireEvent.pointerUp(btn);
    expect(spy).toHaveBeenLastCalledWith(false);

    act(() => patch({ peekBytes: 2_500_000 }));
    expect(screen.getByText('2.5 MB')).toBeTruthy();
  });
});
