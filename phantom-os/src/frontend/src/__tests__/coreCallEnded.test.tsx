/**
 * Core sweep (core-chat, C12): дзвінок, що не встав, не зникає за 2,6 с —
 * екран лишається з «Передзвонити» й «Закрити». Розмова, що відбулась, чи
 * власне «поклав» — екран іде сам, як і раніше.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { translate } from '../i18n';

const SDP = [
  'v=0',
  'o=- 1 2 IN IP4 127.0.0.1',
  's=-',
  't=0 0',
  'm=audio 9 UDP/TLS/RTP/SAVPF 111',
  'c=IN IP4 0.0.0.0',
  'a=mid:0',
  'a=rtpmap:111 opus/48000/2',
  'a=fmtp:111 minptime=10;useinbandfec=1',
  '',
].join('\r\n');

const PEER_ID = 'f0e1d2c3b4a59687';
let resume: Record<string, unknown> | null = null;
const posts: Array<{ path: string; body: Record<string, unknown> }> = [];

vi.mock('../services/api', () => ({
  request: vi.fn(async (method: string, path: string, body?: Record<string, unknown>) => {
    if (path.startsWith('/messenger/ice')) return { iceServers: [], turn: false, ttl: 0 };
    if (path.startsWith('/messenger/call/room')) throw new Error('no bridge');
    if (method === 'POST') posts.push({ path, body: body ?? {} });
    if (path === '/messenger/call/ready') return { ready: true, resume };
    return { delivered: true, call_id: 'x', detail: '' };
  }),
  ApiError: class extends Error {},
}));

vi.mock('../services/websocket', () => ({
  wsClient: { on: () => () => undefined, send: () => undefined },
}));

vi.mock('../services/callRadio', () => ({
  RadioLink: class {
    start() {}
    stop() {}
    async receive() {}
  },
}));

class FakeMediaStream {
  private tracks: Array<Record<string, unknown>>;
  constructor(tracks: Array<Record<string, unknown>> = []) {
    this.tracks = [...tracks];
  }
  getTracks() {
    return this.tracks;
  }
  getAudioTracks() {
    return this.tracks.filter((t) => t.kind === 'audio');
  }
  getVideoTracks() {
    return this.tracks.filter((t) => t.kind === 'video');
  }
  addEventListener() {}
  removeEventListener() {}
}

let pcs: FakePC[] = [];
/** Перший канал не приймає новий опис — як Chrome, коли змінився відбиток DTLS. */
let firstRefusesReoffer = false;

class FakePC {
  connectionState = 'new';
  iceConnectionState = 'new';
  signalingState = 'stable';
  localDescription: { sdp: string } | null = null;
  onicecandidate: unknown = null;
  ontrack: unknown = null;
  onconnectionstatechange: (() => void) | null = null;
  oniceconnectionstatechange: unknown = null;
  ondatachannel: unknown = null;
  private remotes = 0;
  constructor() {
    pcs.push(this);
  }
  addEventListener() {}
  removeEventListener() {}
  createDataChannel(label: string) {
    return { label, readyState: 'connecting', send() {}, close() {} };
  }
  addTrack(track: unknown) {
    return { track, getParameters: () => ({ encodings: [{}] }), setParameters: async () => undefined, replaceTrack: async () => undefined };
  }
  getSenders() {
    return [];
  }
  getTransceivers() {
    return [];
  }
  async createOffer() {
    return { type: 'offer', sdp: SDP };
  }
  async createAnswer() {
    return { type: 'answer', sdp: SDP };
  }
  async setLocalDescription(d: { type: string; sdp: string }) {
    this.localDescription = d;
    this.signalingState = d.type === 'offer' ? 'have-local-offer' : 'stable';
  }
  async setRemoteDescription(d: { type: string; sdp: string }) {
    this.remotes += 1;
    if (firstRefusesReoffer && this === pcs[0] && d.type === 'offer' && this.remotes > 1) {
      throw new Error('Failed to set remote offer sdp: DTLS fingerprint changed');
    }
    this.signalingState = d.type === 'offer' ? 'have-remote-offer' : 'stable';
  }
  async addIceCandidate() {}
  async getStats() {
    return new Map();
  }
  close() {
    this.connectionState = 'closed';
  }
  setState(state: string) {
    this.connectionState = state;
    this.onconnectionstatechange?.();
  }
}

const track = (kind: string, id: string) => ({
  id,
  kind,
  enabled: true,
  stop: () => undefined,
  applyConstraints: async () => undefined,
  getSettings: () => ({}),
});


describe('C12: кінець дзвінка', () => {
  let engine: typeof import('../services/callEngine');

  beforeEach(async () => {
    vi.useFakeTimers();
    pcs = [];
    posts.length = 0;
    (globalThis as unknown as Record<string, unknown>).MediaStream = FakeMediaStream;
    (globalThis as unknown as Record<string, unknown>).RTCPeerConnection = FakePC;
    Object.defineProperty(globalThis.navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia: async () => new FakeMediaStream([track('audio', 'a1')]) },
    });
    vi.resetModules();
    engine = await import('../services/callEngine');
  });

  afterEach(() => {
    cleanup();
    engine.callEngine.hangup();
    engine.callEngine.dismiss();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  const MARTA = { peerNodeId: PEER_ID, displayName: 'Марта' };

  it('мікрофон не дався — екран лишається, «Передзвонити» набирає знову, «Закрити» прибирає', async () => {
    Object.defineProperty(globalThis.navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia: async () => { throw Object.assign(new Error('busy'), { name: 'NotReadableError' }); } },
    });
    await engine.callEngine.startCall(MARTA, 'audio');
    expect(engine.callEngine.getSnapshot().state).toBe('ended');
    await vi.advanceTimersByTimeAsync(5000);
    expect(engine.callEngine.getSnapshot().state, 'збій зник сам — людина не встигла прочитати причину').toBe('ended');

    Object.defineProperty(globalThis.navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia: async () => new FakeMediaStream([track('audio', 'a1')]) },
    });
    const first = engine.callEngine.getSnapshot().callId;
    await engine.callEngine.redial();
    const s = engine.callEngine.getSnapshot();
    expect(s.state).toBe('calling');
    expect(s.peer?.peerNodeId).toBe(PEER_ID);
    expect(s.callId).not.toBe(first);

    engine.callEngine.hangup();
    expect(engine.callEngine.getSnapshot().state).toBe('ended');
    await vi.advanceTimersByTimeAsync(3000);
    expect(engine.callEngine.getSnapshot().state, 'власне «поклав» — екран іде сам').toBe('idle');
  });

  it('«Передзвонити» після невдалого входу в кімнату групи входить у ту саму кімнату', async () => {
    Object.defineProperty(globalThis.navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia: async () => { throw new Error('no mic'); } },
    });
    await engine.callEngine.startCall({ ...MARTA, callId: 'room-7' }, 'audio');
    expect(engine.callEngine.getSnapshot().state).toBe('ended');
    Object.defineProperty(globalThis.navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia: async () => new FakeMediaStream([track('audio', 'a1')]) },
    });
    await engine.callEngine.redial();
    expect(engine.callEngine.getSnapshot().callId).toBe('room-7');
  });

  it('«Закрити» прибирає екран збою', async () => {
    Object.defineProperty(globalThis.navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia: async () => { throw new Error('no mic'); } },
    });
    await engine.callEngine.startCall(MARTA, 'video');
    engine.callEngine.dismiss();
    expect(engine.callEngine.getSnapshot().state).toBe('idle');
  });

  it('екран завершеного дзвінка має «Закрити» й «Передзвонити»', async () => {
    const { CallOverlay } = await import('../components/messenger/CallOverlay');
    vi.spyOn(engine.callEngine, 'getSnapshot').mockReturnValue({
      ...engine.callEngine.getSnapshot(),
      state: 'ended',
      endedReason: 'не вдалося скласти пропозицію',
      peer: MARTA,
    });
    const redial = vi.spyOn(engine.callEngine, 'redial').mockResolvedValue();
    const dismiss = vi.spyOn(engine.callEngine, 'dismiss').mockImplementation(() => {});
    render(<CallOverlay />);
    expect(document.querySelector('[data-call-ended-controls]')).not.toBeNull();
    fireEvent.click(screen.getByLabelText(translate('coreChat.callAgain', 'uk')));
    expect(redial).toHaveBeenCalled();
    fireEvent.click(screen.getByLabelText(translate('coreChat.close', 'uk')));
    expect(dismiss).toHaveBeenCalled();
  });
});
