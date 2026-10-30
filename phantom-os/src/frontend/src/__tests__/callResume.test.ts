/**
 * Розмова переживає смерть рендерера WebView (10.10).
 *
 * Android під тиском памʼяті вбиває рендер-процес WebView, а з ним і
 * RTCPeerConnection. Телефон пересоздає WebView, і нова сторінка на
 * `POST /messenger/call/ready` дізнається від моста, що розмова ще йде.
 * Рушій сам шле пропозицію ТИМ САМИМ номером дзвінка й чесно каже людині
 * «перервало — відновлюємо». Співрозмовник, чий старий канал уже не приймає
 * новий опис, будує канал заново, а не мовчить.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { callStateLine, linkLine } from '../services/callWords';
import { tNow } from '../components/chat/tNow';

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

describe('сторінка народилась посеред розмови', () => {
  let engine: typeof import('../services/callEngine');

  beforeEach(async () => {
    vi.useFakeTimers();
    pcs = [];
    posts.length = 0;
    resume = null;
    firstRefusesReoffer = false;
    (globalThis as unknown as Record<string, unknown>).MediaStream = FakeMediaStream;
    (globalThis as unknown as Record<string, unknown>).RTCPeerConnection = FakePC;
    Object.defineProperty(globalThis.navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia: async () => new FakeMediaStream([track('audio', 'a1')]) },
    });
    delete (window as unknown as Record<string, unknown>).__phantomCallSignal;
    vi.resetModules();
    engine = await import('../services/callEngine');
  });

  afterEach(() => {
    engine.callEngine.hangup();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('міст каже «розмова йде» — рушій сам шле пропозицію тим самим номером і каже «перервало»', async () => {
    resume = { call_id: 'c_live', peer_id: PEER_ID, name: 'Марта', media: 'audio' };
    engine.callEngine.attach();
    await vi.advanceTimersByTimeAsync(10);

    const offer = posts.find((p) => p.path === '/messenger/call/offer');
    expect(offer, 'після перезапуску сторінки пропозиції немає — розмова мовчки згасла').toBeDefined();
    expect(offer!.body.call_id).toBe('c_live');
    const s = engine.callEngine.getSnapshot();
    expect(s.callId).toBe('c_live');
    expect(s.peer?.peerNodeId).toBe(PEER_ID);
    expect(s.peer?.displayName).toBe('Марта');
    expect(s.state).toBe('connecting');
    expect(callStateLine(s)).toBe(tNow('callWords.resuming'));
  });

  it('канал встав — «відновлено», а не вічне «відновлюємо»', async () => {
    resume = { call_id: 'c_live', peer_id: PEER_ID, name: 'Марта', media: 'audio' };
    engine.callEngine.attach();
    await vi.advanceTimersByTimeAsync(10);
    await engine.callEngine.onSignal({ data: { kind: 'answer', call_id: 'c_live', sdp: SDP, from_node_id: PEER_ID } });
    pcs[pcs.length - 1].setState('connected');
    await vi.advanceTimersByTimeAsync(10);

    const s = engine.callEngine.getSnapshot();
    expect(s.state).toBe('active');
    expect(s.resuming).toBe(false);
    expect(linkLine(s)).toBe(tNow('callWords.restored'));
  });

  it('розмови немає — сторінка нічого не набирає', async () => {
    engine.callEngine.attach();
    await vi.advanceTimersByTimeAsync(10);
    expect(posts.some((p) => p.path === '/messenger/call/offer')).toBe(false);
    expect(engine.callEngine.getSnapshot().state).toBe('idle');
  });

  it('у співрозмовника старий канал не приймає новий опис — він будує новий і відповідає', async () => {
    firstRefusesReoffer = true;
    await engine.callEngine.startCall({ peerNodeId: PEER_ID, displayName: 'Марта' }, 'audio');
    const callId = engine.callEngine.getSnapshot().callId!;
    await engine.callEngine.onSignal({ data: { kind: 'answer', call_id: callId, sdp: SDP, from_node_id: PEER_ID } });
    pcs[0].setState('connected');
    await vi.advanceTimersByTimeAsync(10);
    posts.length = 0;

    await engine.callEngine.onSignal({ data: { kind: 'offer', call_id: callId, sdp: SDP, from_node_id: PEER_ID } });
    await vi.advanceTimersByTimeAsync(10);

    expect(pcs.length, 'новий канал не збудовано').toBe(2);
    expect(posts.find((p) => p.path === '/messenger/call/answer')?.body.call_id).toBe(callId);
    expect(engine.callEngine.getSnapshot().state).not.toBe('ended');
  });

  it('екран дзвінка справді показує «перервало» (CallOverlay передає resuming)', () => {
    const overlay = readFileSync(join(process.cwd(), 'src/components/messenger/CallOverlay.tsx'), 'utf8');
    const call = overlay.slice(overlay.indexOf('callStateLine({'), overlay.indexOf('callStateLine({') + 300);
    expect(call).toContain('resuming: snapshot.resuming');
  });
});
