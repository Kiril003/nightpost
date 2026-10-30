import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

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

const videoTrack = () => {
  let settings = { width: 1280, height: 720 };
  return {
    id: 'v1',
    kind: 'video',
    enabled: true,
    contentHint: '',
    stop: () => undefined,
    applyConstraints: vi.fn(async (c: MediaTrackConstraints) => {
      const h = (c.height as ConstrainULongRange).ideal ?? 720;
      settings = { width: Math.round((h * 16) / 9), height: h };
    }),
    getSettings: () => settings,
  };
};

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
  replaced: unknown[] = [];
  async replaceTrack(t: unknown) {
    this.replaced.push(t);
    this.track = t;
  }
}

let lastPc: FakePC | null = null;

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
    return new Map();
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

const PEER = { peerNodeId: 'f0e1d2c3b4a59687', displayName: 'Марта', verified: false };

describe('callEngine flipCamera contentHint', () => {
  let engine: typeof import('../services/callEngine');
  let track: ReturnType<typeof videoTrack>;

  beforeEach(async () => {
    vi.useFakeTimers();
    lastPc = null;
    track = videoTrack();
    (globalThis as unknown as Record<string, unknown>).MediaStream = FakeMediaStream;
    (globalThis as unknown as Record<string, unknown>).RTCPeerConnection = FakePC;
    Object.defineProperty(globalThis.navigator, 'mediaDevices', {
      configurable: true,
      value: {
        getUserMedia: async () =>
          new FakeMediaStream([{ id: 'a1', kind: 'audio', enabled: true, stop: () => undefined }, track]),
      },
    });
    vi.resetModules();
    engine = await import('../services/callEngine');
  });

  afterEach(() => {
    engine.callEngine.hangup();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('sets contentHint to detail on the new track when flipping to the rear camera', async () => {
    await engine.callEngine.startCall(PEER, 'video');
    expect(track.contentHint).toBe('motion');

    lastPc!.connect();
    await vi.advanceTimersByTimeAsync(10);

    const back = { ...videoTrack(), id: 'v2' };
    Object.defineProperty(globalThis.navigator, 'mediaDevices', {
      configurable: true,
      value: {
        getUserMedia: vi.fn(async () => new FakeMediaStream([back])),
      },
    });

    expect(await engine.callEngine.flipCamera()).toBe(true);
    expect(back.contentHint).toBe('detail');
  });
});
