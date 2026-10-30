/**
 * Одна лійка для локального SDP (P0-5, 08.10).
 *
 * До 08.10 кожен шлях переговорів правив опис по-своєму: набір і прийом
 * ставили і Opus, і стелю відео, а повторні переговори (`onReoffer`,
 * `tryRevive`, `rebuildAndOffer`) — лише Opus, тож після першого ж
 * перепідключення відео втрачало стелю `b=AS`. А сам `tuneVideoSdp` клав
 * `b=` ПЕРЕД `c=` — порушення порядку RFC 4566 (m= i= c= b= … a=), який
 * частина стеків просто ігнорує.
 *
 * Червоніє на коді до 08.10: повторні шляхи без `b=AS`, а там, де він є, —
 * не після `c=`.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { AUDIO_PROFILES } from '../services/callOpus';
import { finalizeLocalSdp, tuneVideoSdp } from '../services/callVideo';

const SDP = [
  'v=0',
  'o=- 4611731400430051336 2 IN IP4 127.0.0.1',
  's=-',
  't=0 0',
  'a=group:BUNDLE 0 1',
  'm=audio 9 UDP/TLS/RTP/SAVPF 111',
  'c=IN IP4 0.0.0.0',
  'a=rtcp:9 IN IP4 0.0.0.0',
  'a=mid:0',
  'a=rtpmap:111 opus/48000/2',
  'a=fmtp:111 minptime=10;useinbandfec=1',
  'm=video 9 UDP/TLS/RTP/SAVPF 96 102',
  'c=IN IP4 0.0.0.0',
  'a=rtcp:9 IN IP4 0.0.0.0',
  'a=mid:1',
  'a=rtpmap:96 VP8/90000',
  'a=rtpmap:102 H264/90000',
  '',
].join('\r\n');

/** Секція відео: рівно один b=AS, після c= і до першого a=. */
const videoBandwidthIsInPlace = (sdp: string): string | null => {
  const lines = sdp.split(/\r\n|\n/);
  const start = lines.findIndex((l) => l.startsWith('m=video '));
  if (start < 0) return 'немає m=video';
  let end = lines.findIndex((l, i) => i > start && l.startsWith('m='));
  if (end < 0) end = lines.length;
  const section = lines.slice(start, end);
  const as = section.map((l, i) => (l.startsWith('b=AS:') ? i : -1)).filter((i) => i >= 0);
  if (as.length !== 1) return `b=AS у секції відео: ${as.length}`;
  const c = section.findIndex((l) => l.startsWith('c='));
  const firstA = section.findIndex((l) => l.startsWith('a='));
  if (c >= 0 && as[0] < c) return 'b=AS стоїть перед c=';
  if (firstA >= 0 && as[0] > firstA) return 'b=AS стоїть після a=';
  return null;
};

const opusRate = (sdp: string): string | null =>
  /a=fmtp:111 [^\r\n]*maxaveragebitrate=(\d+)/.exec(sdp)?.[1] ?? null;

describe('tuneVideoSdp: b= після c=, і рівно один', () => {
  it('ставить b=AS одразу після c= у секції відео', () => {
    expect(videoBandwidthIsInPlace(tuneVideoSdp(SDP))).toBeNull();
  });

  it('повторний прохід не множить b= (пропозиція поверх пропозиції)', () => {
    const twice = tuneVideoSdp(tuneVideoSdp(SDP));
    expect(videoBandwidthIsInPlace(twice)).toBeNull();
    expect(twice.split('\r\n').filter((l) => l.startsWith('b=TIAS:'))).toHaveLength(1);
  });

  it('без c= у секції — одразу після m=, до атрибутів', () => {
    const noC = SDP.replace('m=video 9 UDP/TLS/RTP/SAVPF 96 102\r\nc=IN IP4 0.0.0.0\r\n', 'm=video 9 UDP/TLS/RTP/SAVPF 96 102\r\n');
    expect(videoBandwidthIsInPlace(tuneVideoSdp(noC))).toBeNull();
  });

  it('аудіо секцію не чіпає', () => {
    const out = tuneVideoSdp(SDP).split('\r\n');
    const audio = out.slice(out.indexOf('m=audio 9 UDP/TLS/RTP/SAVPF 111'), out.findIndex((l) => l.startsWith('m=video')));
    expect(audio.some((l) => l.startsWith('b='))).toBe(false);
  });

  it('лійка = Opus сходинки + стеля відео', () => {
    const out = finalizeLocalSdp(SDP, 'crystal');
    expect(opusRate(out)).toBe(String(AUDIO_PROFILES.crystal.bitrate));
    expect(videoBandwidthIsInPlace(out)).toBeNull();
  });
});

describe('у рушії немає SDP повз лійку', () => {
  /**
   * Структурний доказ: кожен setLocalDescription рушія і драбини бере опис
   * лише з `finalizeLocalSdp`. Мутація: поверніть у `onReoffer` голе
   * `tuneOpus(answer.sdp…)` — і рахунок розійдеться.
   */
  it('кожен setLocalDescription іде через finalizeLocalSdp', () => {
    for (const file of ['services/callEngine.ts', 'services/callLadder.ts']) {
      const src = readFileSync(join(process.cwd(), 'src', file), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');
      const sets = [...src.matchAll(/setLocalDescription\(\{[\s\S]{0,200}?\}\)/g)].map((m) => m[0]);
      expect(sets.length, file).toBeGreaterThan(0);
      for (const call of sets) expect(call, file).toContain('finalizeLocalSdp(');
      expect(src, file).not.toMatch(/sdp:\s*tuneOpus\(/);
    }
  });
});

/* ── поведінка: кожен шлях переговорів крізь справжній рушій ─────────── */

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

class FakeSender {
  track: unknown;
  params: Record<string, unknown> = { encodings: [{}] };
  constructor(track: unknown) {
    this.track = track;
  }
  getParameters() {
    return JSON.parse(JSON.stringify(this.params));
  }
  async setParameters(p: Record<string, unknown>) {
    this.params = p;
  }
  async replaceTrack(t: unknown) {
    this.track = t;
  }
}

/** Усі локальні описи, які рушій поставив, — по порядку. */
let localSdps: Array<{ type: string; sdp: string; iceRestart: boolean }> = [];
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
  senders: FakeSender[] = [];
  private restart = false;
  constructor() {
    lastPc = this;
  }
  addEventListener() {}
  removeEventListener() {}
  createDataChannel(label: string) {
    return { label, readyState: 'connecting', send() {}, close() {} };
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
  async createOffer(opts?: { iceRestart?: boolean }) {
    this.restart = opts?.iceRestart === true;
    return { type: 'offer', sdp: SDP };
  }
  async createAnswer() {
    this.restart = false;
    return { type: 'answer', sdp: SDP };
  }
  async setLocalDescription(d: { type: string; sdp: string }) {
    localSdps.push({ type: d.type, sdp: d.sdp, iceRestart: this.restart });
    this.localDescription = d;
    this.signalingState = d.type === 'offer' ? 'have-local-offer' : 'stable';
  }
  async setRemoteDescription(d: { type: string; sdp: string }) {
    this.remoteDescription = d;
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

// Рація в jsdom не встала б (MediaRecorder немає) — а нам треба саме те, що
// рушій робить ПІД нею: спроби повернутись у реальний час.
vi.mock('../services/callRadio', () => ({
  RadioLink: class {
    start() {}
    stop() {}
    setMuted() {}
    async receive() {}
  },
}));

const PEER = { peerNodeId: 'f0e1d2c3b4a59687', displayName: 'Марта', verified: false };
const track = (kind: string, id: string) => ({
  id,
  kind,
  enabled: true,
  stop: () => undefined,
  applyConstraints: async () => undefined,
  getSettings: () => ({ width: 1280, height: 720 }),
});

describe('кожен шлях переговорів іде крізь лійку', () => {
  let engine: typeof import('../services/callEngine');

  beforeEach(async () => {
    vi.useFakeTimers();
    localSdps = [];
    lastPc = null;
    (globalThis as unknown as Record<string, unknown>).MediaStream = FakeMediaStream;
    (globalThis as unknown as Record<string, unknown>).RTCPeerConnection = FakePC;
    Object.defineProperty(globalThis.navigator, 'mediaDevices', {
      configurable: true,
      value: {
        getUserMedia: async () => new FakeMediaStream([track('audio', 'a1'), track('video', 'v1')]),
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

  /** Набрали, нам відповіли, доріжка встала. */
  const talking = async () => {
    await engine.callEngine.startCall(PEER, 'video');
    const callId = engine.callEngine.getSnapshot().callId!;
    await engine.callEngine.onSignal({
      data: { kind: 'answer', call_id: callId, sdp: SDP, from_node_id: PEER.peerNodeId },
    });
    lastPc!.setState('connected');
    await vi.advanceTimersByTimeAsync(10);
    expect(engine.callEngine.getSnapshot().state).toBe('active');
    return callId;
  };

  const expectFunnel = (entry: { sdp: string } | undefined, level: keyof typeof AUDIO_PROFILES = 'crystal') => {
    expect(entry).toBeDefined();
    expect(videoBandwidthIsInPlace(entry!.sdp)).toBeNull();
    expect(opusRate(entry!.sdp)).toBe(String(AUDIO_PROFILES[level].bitrate));
  };

  it('набір (startCall)', async () => {
    await engine.callEngine.startCall(PEER, 'video');
    expect(localSdps).toHaveLength(1);
    expect(localSdps[0].type).toBe('offer');
    expectFunnel(localSdps[0]);
  });

  it('прийом (accept)', async () => {
    await engine.callEngine.onSignal({
      data: { kind: 'offer', call_id: 'c_in', sdp: SDP, media: 'video', from_node_id: PEER.peerNodeId, display_name: 'Марта' },
    });
    await engine.callEngine.accept();
    const answer = localSdps.find((d) => d.type === 'answer');
    expectFunnel(answer);
  });

  it('повторні переговори (onReoffer)', async () => {
    const callId = await talking();
    localSdps = [];
    await engine.callEngine.onSignal({
      data: { kind: 'offer', call_id: callId, sdp: SDP, media: 'video', from_node_id: PEER.peerNodeId },
    });
    expect(localSdps.map((d) => d.type)).toEqual(['answer']);
    expectFunnel(localSdps[0]);
  });

  it('крок драбини з новою пропозицією (ptime)', async () => {
    await talking();
    localSdps = [];
    await engine.callEngine.forceLadder('thrifty');
    expect(localSdps.map((d) => d.type)).toEqual(['offer']);
    expectFunnel(localSdps[0], 'thrifty');
  });

  it('повернення з рації перезапуском ICE (tryRevive)', async () => {
    await talking();
    localSdps = [];
    lastPc!.setState('disconnected');
    await vi.advanceTimersByTimeAsync(10_000 + 20_000 + 50);
    const restart = localSdps.find((d) => d.iceRestart);
    expect(restart, 'спроба з iceRestart не відбулась').toBeDefined();
    expectFunnel(restart);
  });

  it('повернення з рації з чистого аркуша (rebuildAndOffer)', async () => {
    await talking();
    const first = lastPc;
    localSdps = [];
    engine.callEngine.killLinkForTest();
    await vi.advanceTimersByTimeAsync(20_000 + 50);
    expect(lastPc).not.toBe(first);
    const offer = localSdps.find((d) => d.type === 'offer' && !d.iceRestart);
    expectFunnel(offer);
  });
});
