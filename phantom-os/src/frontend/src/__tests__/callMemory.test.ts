import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  CALL_MEMORY_KEY,
  MEMORY_TTL_MS,
  MIN_VIDEO_KBPS,
  STEADY_MS,
  forgetCallMemory,
  openingCap,
  recordSteady,
  startPlan,
} from '../services/callMemory';

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
  getTrackById(id: string) {
    return this.tracks.find((t) => t.id === id) ?? null;
  }
  addTrack(t: Record<string, unknown>) {
    this.tracks.push(t);
  }
  removeTrack(t: Record<string, unknown>) {
    this.tracks = this.tracks.filter((x) => x !== t);
  }
  addEventListener() {}
  removeEventListener() {}
}

const makeVideoTrack = () => ({
  id: 'v1',
  kind: 'video',
  enabled: true,
  stop: () => undefined,
  applyConstraints: vi.fn(async () => undefined),
  getSettings: () => ({ width: 1280, height: 720 }),
});

class FakeChannel {
  label: string;
  readyState = 'connecting';
  sent: string[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((e: { data: unknown }) => void) | null = null;
  onclose: (() => void) | null = null;
  constructor(label: string) {
    this.label = label;
  }
  send(data: string) {
    this.sent.push(data);
  }
  close() {
    this.readyState = 'closed';
  }
  open() {
    this.readyState = 'open';
    this.onopen?.();
  }
}

class FakeSender {
  track: unknown;
  params: Record<string, unknown> = { encodings: [{}] };
  setParameters = vi.fn(async (p: Record<string, unknown>) => {
    this.params = p;
  });
  constructor(track: unknown) {
    this.track = track;
  }
  getParameters() {
    return JSON.parse(JSON.stringify(this.params));
  }
  async replaceTrack(t: unknown) {
    this.track = t;
  }
}

let lastPc: FakePC | null = null;
let statsRows: Array<Record<string, unknown>> = [];

class FakePC {
  connectionState = 'new';
  iceConnectionState = 'new';
  signalingState = 'stable';
  localDescription: { sdp: string } | null = null;
  remoteDescription: { sdp: string } | null = null;
  onicecandidate: unknown = null;
  ontrack: unknown = null;
  onconnectionstatechange: (() => void) | null = null;
  oniceconnectionstatechange: unknown = null;
  ondatachannel: unknown = null;
  channels: FakeChannel[] = [];
  senders: FakeSender[] = [];
  constructor() {
    lastPc = this;
  }
  addEventListener() {}
  removeEventListener() {}
  createDataChannel(label: string) {
    const ch = new FakeChannel(label);
    this.channels.push(ch);
    return ch;
  }
  addTrack(track: unknown) {
    const s = new FakeSender(track);
    this.senders.push(s);
    return s;
  }
  getSenders() {
    return this.senders;
  }
  getTransceivers() {
    return [];
  }
  async createOffer() {
    return { type: 'offer', sdp: 'v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\n' };
  }
  async createAnswer() {
    return { type: 'answer', sdp: 'v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\n' };
  }
  async setLocalDescription(d: { sdp: string }) {
    this.localDescription = d;
    this.signalingState = 'have-local-offer';
  }
  async setRemoteDescription(d: { sdp: string }) {
    this.remoteDescription = d;
    this.signalingState = 'stable';
  }
  async addIceCandidate() {}
  async getStats() {
    return new Map(statsRows.map((r, i) => [`r${i}`, r]));
  }
  close() {
    this.connectionState = 'closed';
  }
  connect() {
    this.connectionState = 'connected';
    this.onconnectionstatechange?.();
  }
}

vi.mock('../services/api', () => ({
  request: vi.fn(async (_m: string, path: string) => {
    if (path.startsWith('/messenger/ice')) return { iceServers: [], turn: false, ttl: 0 };
    if (path.startsWith('/messenger/call/room')) throw new Error('no bridge');
    return { delivered: true, call_id: 'x', detail: '' };
  }),
  ApiError: class extends Error {},
}));

vi.mock('../services/websocket', () => ({
  wsClient: { on: () => () => undefined, send: () => undefined },
}));

const PEER_P = {
  peerNodeId: 'f0e1d2c3b4a59687',
  contactId: 'contact-marta',
  displayName: 'Марта',
  verified: false,
};
const PEER_Q = { peerNodeId: '1122334455667788', displayName: 'Олег', verified: false };

describe('памʼять каналу дзвінка (callMemory)', () => {
  beforeEach(() => {
    forgetCallMemory();
  });

  afterEach(() => {
    forgetCallMemory();
  });

  it('записує стабільний прогін і повертає план для тієї ж мережі', () => {
    recordSteady('peer-1', 'wifi', { kbps: 1800, h: 1080, steadyMs: 25_000 }, 1_000);
    expect(startPlan('peer-1', 'wifi', 2_000)).toEqual({ h: 1080, kbps: 1800 });
    expect(openingCap(['peer-1'], 'video', 'wifi', 2_000)).toBe(false);
  });

  it('короткий прогін (5 с < STEADY_MS) нічого не записує', () => {
    recordSteady('peer-1', 'wifi', { kbps: 1800, h: 1080, steadyMs: 5_000 }, 1_000);
    expect(STEADY_MS).toBe(20_000);
    expect(startPlan('peer-1', 'wifi', 2_000)).toBeNull();
    expect(openingCap(['peer-1'], 'video', 'wifi', 2_000)).toBe(true);
  });

  it('бітрейт рівня аудіо (kbps < MIN_VIDEO_KBPS) нічого не записує', () => {
    expect(MIN_VIDEO_KBPS).toBe(300);
    recordSteady('peer-1', 'wifi', { kbps: 100, h: 1080, steadyMs: 25_000 }, 1_000);
    expect(startPlan('peer-1', 'wifi', 2_000)).toBeNull();
  });

  it('openingCap бере перший ключ із планом і повертає true для порожнього списку', () => {
    recordSteady('contact-1', 'wifi', { kbps: 1800, h: 1080, steadyMs: 25_000 }, 1_000);
    expect(openingCap(['', 'contact-1'], 'video', 'wifi', 2_000)).toBe(false);
    expect(openingCap([], 'video', 'wifi', 2_000)).toBe(true);
  });

  it('інша мережа (mobile замість wifi) повертає null', () => {
    recordSteady('peer-1', 'wifi', { kbps: 1800, h: 1080, steadyMs: 25_000 }, 1_000);
    expect(startPlan('peer-1', 'mobile', 2_000)).toBeNull();
  });

  it('запис, старший за 30 діб (31 доба), повертає null і прибирається зі сховища', () => {
    const t0 = 1_000_000;
    recordSteady('peer-1', 'wifi', { kbps: 1800, h: 1080, steadyMs: 25_000 }, t0);
    const thirtyOneDays = 31 * 24 * 3600 * 1000;
    expect(thirtyOneDays).toBeGreaterThan(MEMORY_TTL_MS);
    expect(startPlan('peer-1', 'wifi', t0 + thirtyOneDays)).toBeNull();
    expect(startPlan('peer-1', 'wifi', t0 + 1_000)).toBeNull();
  });

  it('пошкоджений JSON у localStorage повертає null і не кидає помилку', () => {
    localStorage.setItem(CALL_MEMORY_KEY, '{broken-json');
    expect(() => startPlan('peer-1', 'wifi')).not.toThrow();
    expect(startPlan('peer-1', 'wifi')).toBeNull();
    expect(() =>
      recordSteady('peer-1', 'wifi', { kbps: 1500, h: 1080, steadyMs: 22_000 }),
    ).not.toThrow();
    expect(startPlan('peer-1', 'wifi')).toEqual({ h: 1080, kbps: 1500 });
  });

  it('тримає щонайбільше 200 записів і витісняє найстаріший за at', () => {
    for (let i = 0; i < 205; i += 1) {
      recordSteady(`peer-${i}`, 'wifi', { kbps: 1000 + i, h: 1080, steadyMs: 25_000 }, 1_000 + i);
    }
    for (let i = 0; i < 5; i += 1) {
      expect(startPlan(`peer-${i}`, 'wifi', 10_000)).toBeNull();
    }
    expect(startPlan('peer-5', 'wifi', 10_000)).toEqual({ h: 1080, kbps: 1005 });
    expect(startPlan('peer-204', 'wifi', 10_000)).toEqual({ h: 1080, kbps: 1204 });
  });
});

describe('callEngine відкриває відеодзвінок із памʼяті каналу', () => {
  let engine: typeof import('../services/callEngine');
  let prevConnection: PropertyDescriptor | undefined;

  beforeEach(async () => {
    vi.useFakeTimers();
    vi.setSystemTime(1_000_000);
    forgetCallMemory();
    lastPc = null;
    statsRows = [];
    prevConnection = Object.getOwnPropertyDescriptor(globalThis.navigator, 'connection');
    Object.defineProperty(globalThis.navigator, 'connection', {
      value: { type: 'wifi' },
      configurable: true,
    });
    (globalThis as unknown as Record<string, unknown>).MediaStream = FakeMediaStream;
    (globalThis as unknown as Record<string, unknown>).RTCPeerConnection = FakePC;
    Object.defineProperty(globalThis.navigator, 'mediaDevices', {
      configurable: true,
      value: {
        getUserMedia: async () =>
          new FakeMediaStream([
            { id: 'a1', kind: 'audio', enabled: true, stop: () => undefined },
            makeVideoTrack(),
          ]),
      },
    });
    vi.resetModules();
    engine = await import('../services/callEngine');
  });

  afterEach(() => {
    engine.callEngine.hangup();
    forgetCallMemory();
    if (prevConnection) {
      Object.defineProperty(globalThis.navigator, 'connection', prevConnection);
    } else {
      delete (globalThis.navigator as unknown as Record<string, unknown>).connection;
    }
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('після запису 1080 для співрозмовника новий відеодзвінок стартує з videoCapped: false, а без памʼяті — true', async () => {
    recordSteady(PEER_P.peerNodeId, 'wifi', { kbps: 2200, h: 1080, steadyMs: 25_000 });

    await engine.callEngine.startCall(PEER_P, 'video');
    expect(engine.callEngine.getSnapshot().videoCapped).toBe(false);
    engine.callEngine.hangup();

    await engine.callEngine.startCall(PEER_Q, 'video');
    expect(engine.callEngine.getSnapshot().videoCapped).toBe(true);
  });

  it('рівний відеодзвінок ≥ 20 с на верхній сходинці записує 1080 для обох ключів співрозмовника', async () => {
    await engine.callEngine.startCall(PEER_P, 'video');
    expect(engine.callEngine.getSnapshot().videoCapped).toBe(true);
    statsRows = [{ type: 'inbound-rtp', kind: 'video', bytesReceived: 0 }];
    lastPc!.connect();

    (engine.callEngine as unknown as { patch: (n: Record<string, unknown>) => void }).patch({
      videoCapped: false,
      ladderPinned: true,
    });

    for (let i = 1; i <= 12; i += 1) {
      statsRows = [{ type: 'inbound-rtp', kind: 'video', bytesReceived: i * 450_000 }];
      await vi.advanceTimersByTimeAsync(2000);
    }

    expect(engine.callEngine.getSnapshot().videoCapped).toBe(false);
    engine.callEngine.hangup();

    expect(startPlan(PEER_P.peerNodeId, 'wifi')).toEqual({ h: 1080, kbps: 1800 });
    expect(startPlan(PEER_P.contactId, 'wifi')).toEqual({ h: 1080, kbps: 1800 });

    await engine.callEngine.startCall(PEER_P, 'video');
    expect(engine.callEngine.getSnapshot().videoCapped).toBe(false);
  });

  it('відеодзвінок, де 25 с стояла стеля 720p і лише 5 с — 1080p, записує h: 720', async () => {
    await engine.callEngine.startCall(PEER_P, 'video');
    expect(engine.callEngine.getSnapshot().videoCapped).toBe(true);
    (engine.callEngine as unknown as { patch: (n: Record<string, unknown>) => void }).patch({
      ladderPinned: true,
    });
    statsRows = [{ type: 'inbound-rtp', kind: 'video', bytesReceived: 0 }];
    lastPc!.connect();

    for (let i = 1; i <= 12; i += 1) {
      statsRows = [{ type: 'inbound-rtp', kind: 'video', bytesReceived: i * 450_000 }];
      await vi.advanceTimersByTimeAsync(2000);
    }
    await vi.advanceTimersByTimeAsync(1000);

    (engine.callEngine as unknown as { patch: (n: Record<string, unknown>) => void }).patch({
      videoCapped: false,
    });
    expect(engine.callEngine.getSnapshot().videoCapped).toBe(false);

    statsRows = [{ type: 'inbound-rtp', kind: 'video', bytesReceived: 13 * 450_000 }];
    await vi.advanceTimersByTimeAsync(1000);
    statsRows = [{ type: 'inbound-rtp', kind: 'video', bytesReceived: 14 * 450_000 }];
    await vi.advanceTimersByTimeAsync(2000);
    statsRows = [{ type: 'inbound-rtp', kind: 'video', bytesReceived: 15 * 450_000 }];
    await vi.advanceTimersByTimeAsync(2000);

    engine.callEngine.hangup();

    expect(startPlan(PEER_P.peerNodeId, 'wifi')).toEqual({ h: 720, kbps: 1800 });
  });
});
