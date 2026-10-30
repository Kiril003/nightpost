/**
 * ПОГЛЯД досяжний із реального входу: `callEngine.startCall(…, 'video')`
 * відкриває канал `phantom-view`, шле свій кадр `view`, а чужий кадр `view`
 * лягає на відправника відео й повертається відповіддю `serve` з виміряним
 * МБ/год. Без кадрів (старий клієнт) — типовий план, а не 2,5 Мбіт/с.
 *
 * Червоніє на коді до 02.10: каналу не було зовсім, відправник отримував
 * сталі 2,5 Мбіт/с / 60 fps.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { readQuality } from '../components/messenger/call/quality';

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
  offers = 0;
  async createOffer() {
    this.offers += 1;
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

const PEER = { peerNodeId: 'f0e1d2c3b4a59687', displayName: 'Марта', verified: false };

const frame = (patch: Record<string, unknown>) =>
  JSON.stringify({
    t: 'view',
    v: 1,
    seq: 1,
    tiles: [{ w: 320, h: 180, vis: true }],
    screen: { w: 1080, h: 2340 },
    saveData: false,
    budgetMBh: null,
    batt: null,
    fg: 'visible',
    ...patch,
  });

describe('ПОГЛЯД у рушії дзвінка', () => {
  let engine: typeof import('../services/callEngine');
  let track: ReturnType<typeof videoTrack>;

  beforeEach(async () => {
    vi.useFakeTimers();
    lastPc = null;
    statsRows = [];
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

  const start = async () => {
    await engine.callEngine.startCall(PEER, 'video');
    const pc = lastPc!;
    const channel = pc.channels.find((c) => c.label === 'phantom-view');
    const video = pc.senders.find((s) => (s.track as { kind?: string })?.kind === 'video')!;
    return { pc, channel, video };
  };

  it('відеодзвінок відкриває канал ПОГЛЯДУ й одразу шле свій кадр view', async () => {
    const { channel } = await start();
    expect(channel).toBeDefined();
    channel!.open();
    const hello = JSON.parse(channel!.sent[0]);
    expect(hello).toMatchObject({ t: 'view', v: 1 });
    expect(Array.isArray(hello.tiles)).toBe(true);
  });

  it('без кадрів співрозмовника — 720p30 / 2,0 Мбіт/с, а не 2,5 Мбіт/с / 60 fps', async () => {
    const { pc, video } = await start();
    pc.connect();
    await vi.advanceTimersByTimeAsync(10);
    const enc = (video.params.encodings as Array<Record<string, unknown>>)[0];
    expect(enc.maxBitrate).toBe(2_000_000);
    expect(enc.maxFramerate).toBe(30);
  });

  /**
   * D7: 720p — ТИПОВО, навіть на весь екран. 1080p30 / 3,5 Мбіт/с — лише
   * коли приймач показує 1080 px І драбина піднялась нагору після рівного
   * каналу (8 добрих вимірів поспіль, ~16 с). Канал просів — перший крок
   * униз повертає 720p / 2,0, відео живе.
   *
   * Червоніє на 37831192: дзвінок стартував на верхній сходинці
   * (`videoCapped: false` у IDLE), і кожен свіжий відеодзвінок на мобільних
   * даних ішов 1080p30 / 3,5 Мбіт/с (~1,5 ГБ/год) з першої секунди.
   */
  it('весь екран: старт — 2,0 (720p); рівний канал — 3,5; просів — знову 2,0 і відео живе', async () => {
    const { pc, channel, video } = await start();
    pc.connect();
    channel!.open();
    channel!.onmessage!({ data: frame({ seq: 1, tiles: [{ w: 1080, h: 2340, vis: true }] }) });
    await vi.advanceTimersByTimeAsync(10);
    const enc = () => (video.params.encodings as Array<Record<string, unknown>>)[0];
    expect(enc().maxBitrate).toBe(2_000_000);
    expect(enc().maxFramerate).toBe(30);
    expect(engine.callEngine.getSnapshot().videoCapped).toBe(true);

    // Рівний канал: перший вимір — точка відліку, ще вісім добрих — крок угору.
    let n = 0;
    const sample = (rttS: number) => {
      n += 1;
      statsRows = [
        { type: 'candidate-pair', nominated: true, currentRoundTripTime: rttS },
        { type: 'inbound-rtp', kind: 'audio', packetsLost: 0, packetsReceived: n * 100, bytesReceived: n * 4000 },
      ];
    };
    for (let i = 0; i < 8; i += 1) {
      sample(0.04);
      await vi.advanceTimersByTimeAsync(2000);
    }
    expect(enc().maxBitrate).toBe(2_000_000);
    sample(0.04);
    await vi.advanceTimersByTimeAsync(2000);
    expect(engine.callEngine.getSnapshot().videoCapped).toBe(false);
    expect(enc().maxBitrate).toBe(3_500_000);

    // Два погані виміри (RTT 800 мс) — крок униз: 720p, а не смерть відео.
    for (let i = 0; i < 2; i += 1) {
      sample(0.8);
      await vi.advanceTimersByTimeAsync(2000);
    }
    expect(engine.callEngine.getSnapshot().videoDropped).toBe(false);
    expect(video.track).not.toBeNull();
    expect(enc().maxBitrate).toBe(2_000_000);
  });

  /**
   * Смужки не мають показувати сильний сигнал, коли розмова стоїть: Wi-Fi
   * зник, ICE `disconnected`, а пара кандидатів ще `succeeded` зі старим
   * RTT. Пакети не ростуть — `stalled`, і смужка одна.
   *
   * Червоніє на 37831192: втрати за проміжок `null` (нічого не приїхало),
   * смужки бралися зі старого RTT 40 мс — чотири смужки над завислою картинкою.
   */
  it('пакети перестали приходити — stats.stalled, одна смужка, хоч RTT старий і добрий', async () => {
    const { pc } = await start();
    pc.connect();
    const rows = (got: number) => [
      { type: 'candidate-pair', state: 'succeeded', currentRoundTripTime: 0.04 },
      { type: 'inbound-rtp', kind: 'audio', packetsLost: 0, packetsReceived: got, bytesReceived: got * 40 },
    ];
    statsRows = rows(100);
    await vi.advanceTimersByTimeAsync(2000);
    statsRows = rows(200);
    await vi.advanceTimersByTimeAsync(2000);
    expect(engine.callEngine.getSnapshot().stats?.stalled ?? false).toBe(false);
    expect(readQuality(engine.callEngine.getSnapshot().stats)?.bars).toBe(4);

    statsRows = rows(200);
    await vi.advanceTimersByTimeAsync(2000);
    expect(engine.callEngine.getSnapshot().stats?.stalled).toBe(true);
    expect(readQuality(engine.callEngine.getSnapshot().stats)?.bars).toBe(1);

    // Пакети пішли знову — смужки знову з виміру.
    statsRows = rows(300);
    await vi.advanceTimersByTimeAsync(2000);
    expect(engine.callEngine.getSnapshot().stats?.stalled ?? false).toBe(false);
    expect(readQuality(engine.callEngine.getSnapshot().stats)?.bars).toBe(4);
  });

  /**
   * Що кодує апарат — у знімок, з getStats: апаратний енкодер чи програмний
   * вирішує, чи A52 гріється на відео. Червоніє на коді до 08.10: поля не
   * читались зовсім.
   */
  it('encoderImplementation і powerEfficientEncoder — у знімку дзвінка', async () => {
    const { pc } = await start();
    pc.connect();
    statsRows = [
      {
        type: 'outbound-rtp', kind: 'video', bytesSent: 10_000,
        encoderImplementation: 'ExternalEncoder (MediaCodec)', powerEfficientEncoder: true,
      },
    ];
    await vi.advanceTimersByTimeAsync(2000);
    const stats = engine.callEngine.getSnapshot().stats;
    expect(stats?.encoderImpl).toBe('ExternalEncoder (MediaCodec)');
    expect(stats?.powerEfficientEncoder).toBe(true);
  });

  /**
   * Смужки якості читають втрати ЗА ПРОМІЖОК, а не сумарні з початку
   * дзвінка: погана хвилина не має назавжди лишати одну смужку.
   */
  it('lossPct — втрати за останній проміжок getStats', async () => {
    const { pc } = await start();
    pc.connect();
    statsRows = [{ type: 'inbound-rtp', kind: 'audio', packetsLost: 0, packetsReceived: 100 }];
    await vi.advanceTimersByTimeAsync(2000);
    expect(engine.callEngine.getSnapshot().stats?.lossPct ?? null).toBeNull();
    statsRows = [{ type: 'inbound-rtp', kind: 'audio', packetsLost: 10, packetsReceived: 190 }];
    await vi.advanceTimersByTimeAsync(2000);
    expect(engine.callEngine.getSnapshot().stats?.lossPct).toBeCloseTo(10, 5);
    statsRows = [{ type: 'inbound-rtp', kind: 'audio', packetsLost: 10, packetsReceived: 290 }];
    await vi.advanceTimersByTimeAsync(2000);
    expect(engine.callEngine.getSnapshot().stats?.lossPct).toBe(0);
  });

  /**
   * Перемикання камери — `replaceTrack` на живому відправнику, без нової
   * пропозиції: переговори посеред розмови коштують ключового кадру й
   * секунди тиші. Червоніє на коді до 08.10: перемикання не було зовсім.
   */
  it('камера перемикається через replaceTrack без нової пропозиції', async () => {
    const { pc, video } = await start();
    pc.connect();
    await vi.advanceTimersByTimeAsync(10);
    const api = await import('../services/api');
    const offersBefore = pc.offers;
    const postsBefore = (api.request as unknown as { mock: { calls: unknown[][] } }).mock.calls.filter(
      (c) => c[1] === '/messenger/call/offer',
    ).length;

    const back = { ...videoTrack(), id: 'v2' };
    const stopOld = vi.spyOn(track, 'stop');
    const gum = vi.fn(async () => new FakeMediaStream([back]));
    Object.defineProperty(globalThis.navigator, 'mediaDevices', { configurable: true, value: { getUserMedia: gum } });

    expect(engine.callEngine.getSnapshot().facing).toBe('user');
    expect(await engine.callEngine.flipCamera()).toBe(true);

    expect(video.replaced).toEqual([back]);
    expect(video.track).toBe(back);
    expect(stopOld).toHaveBeenCalled();
    expect(gum).toHaveBeenCalledWith(
      expect.objectContaining({ video: expect.objectContaining({ facingMode: { exact: 'environment' } }) }),
    );
    expect(engine.callEngine.getSnapshot().facing).toBe('environment');
    expect(engine.callEngine.getSnapshot().localStream?.getVideoTracks()[0]).toBe(back);
    // Жодних нових переговорів.
    expect(pc.offers).toBe(offersBefore);
    expect(
      (api.request as unknown as { mock: { calls: unknown[][] } }).mock.calls.filter((c) => c[1] === '/messenger/call/offer').length,
    ).toBe(postsBefore);
  });

  /**
   * Перемкнути не вийшло ЗОВСІМ: нова камера зайнята, стару вже погасили
   * (апарат двох разом не тримає), і повернути стару теж не дали. Мертва
   * доріжка не лишається на відправнику: співрозмовник отримав би завислий
   * кадр, а в нас стояла б кнопка «Обернути» над камерою, якої немає.
   *
   * Червоніє на 37831192: відправник лишався зі зупиненою доріжкою, знімок
   * казав `cameraOn: true`, і «Обернути» лишалась на картці.
   */
  it('камера не відкрилась ні з того боку, ні назад — доріжку знято, камери в дзвінку немає', async () => {
    const { pc, video } = await start();
    pc.connect();
    await vi.advanceTimersByTimeAsync(10);
    const stopOld = vi.spyOn(track, 'stop');
    const busy = Object.assign(new Error('Could not start video source'), { name: 'NotReadableError' });
    const gum = vi.fn(async () => {
      throw busy;
    });
    Object.defineProperty(globalThis.navigator, 'mediaDevices', { configurable: true, value: { getUserMedia: gum } });

    expect(await engine.callEngine.flipCamera()).toBe(false);
    expect(gum).toHaveBeenCalledTimes(3);
    expect(stopOld).toHaveBeenCalled();
    expect(video.track).toBeNull();
    const s = engine.callEngine.getSnapshot();
    expect(s.cameraOn).toBe(false);
    expect(s.hasCamera).toBe(false);
    expect(s.localStream?.getVideoTracks() ?? []).toEqual([]);
    expect(s.state).not.toBe('ended');
  });

  it('нової камери немає (OverconstrainedError) — стара знімає далі, нічого не знято', async () => {
    const { pc, video } = await start();
    pc.connect();
    await vi.advanceTimersByTimeAsync(10);
    const stopOld = vi.spyOn(track, 'stop');
    const none = Object.assign(new Error('no such camera'), { name: 'OverconstrainedError' });
    Object.defineProperty(globalThis.navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia: vi.fn(async () => { throw none; }) },
    });
    expect(await engine.callEngine.flipCamera()).toBe(false);
    expect(stopOld).not.toHaveBeenCalled();
    expect(video.track).toBe(track);
    expect(engine.callEngine.getSnapshot().hasCamera).toBe(true);
  });

  it('чужий кадр view: мініатюра → 100 кбіт/с, сховано → пауза, відповідь serve з виміром', async () => {
    const { pc, channel, video } = await start();
    pc.connect();
    channel!.open();
    channel!.onmessage!({ data: frame({ seq: 1 }) });
    await vi.advanceTimersByTimeAsync(10);
    let enc = (video.params.encodings as Array<Record<string, unknown>>)[0];
    expect(enc.maxBitrate).toBe(100_000);
    expect(enc.active).toBe(true);
    expect(track.applyConstraints).toHaveBeenCalled();

    channel!.onmessage!({ data: frame({ seq: 2, tiles: [{ w: 320, h: 180, vis: false }] }) });
    await vi.advanceTimersByTimeAsync(10);
    enc = (video.params.encodings as Array<Record<string, unknown>>)[0];
    expect(enc.active).toBe(false);

    // Сміття не зсуває план.
    channel!.onmessage!({ data: '{"t":"view","v":1,"tiles":"lots"}' });
    await vi.advanceTimersByTimeAsync(10);
    expect((video.params.encodings as Array<Record<string, unknown>>)[0].active).toBe(false);

    // Лічильник: 450 кБ за 2 с — ~810 МБ/год, і саме це їде у відповідь.
    statsRows = [{ type: 'outbound-rtp', kind: 'video', bytesSent: 1_000_000 }];
    await vi.advanceTimersByTimeAsync(2000);
    statsRows = [{ type: 'outbound-rtp', kind: 'video', bytesSent: 1_450_000 }];
    await vi.advanceTimersByTimeAsync(2000);
    const serves = channel!.sent.map((s) => JSON.parse(s)).filter((m) => m.t === 'serve');
    const lastServe = serves[serves.length - 1];
    expect(lastServe.active).toBe(false);
    expect(lastServe.mbh).toBeCloseTo(810, 0);
  });

  it('кадр serve від співрозмовника доходить до знімка — звідти картка бере «~N МБ/год»', async () => {
    const { channel } = await start();
    channel!.open();
    channel!.onmessage!({
      data: JSON.stringify({ t: 'serve', v: 1, seq: 1, active: true, h: 360, fps: 24, kbps: 250, mbh: 42 }),
    });
    expect(engine.callEngine.getSnapshot().served?.mbh).toBe(42);
  });

  /**
   * «Найкраще» + камера, що вміє 4K, + приймач на 4K-екрані: після рівного
   * каналу план піднімається до 2160p / 16 Мбіт/с і камеру просять про 2160.
   * Та сама розмова з камерою на 1080 — стеля 1080p / 3,5. Червоніє до 19.10:
   * драбина закінчувалась на 1080.
   */
  const climb = async (camMax: { width: number; height: number }) => {
    localStorage.setItem('np.callPrefs.v1', JSON.stringify({ wifi: 'best', mobile: 'best', video: 'auto' }));
    (track as unknown as Record<string, unknown>).getCapabilities = () => ({
      width: { min: 1, max: camMax.width },
      height: { min: 1, max: camMax.height },
    });
    const { pc, channel, video } = await start();
    pc.connect();
    channel!.open();
    channel!.onmessage!({
      data: frame({ seq: 1, tiles: [{ w: 2160, h: 3840, vis: true }], screen: { w: 2160, h: 3840 } }),
    });
    await vi.advanceTimersByTimeAsync(10);
    for (let n = 1; n <= 9; n += 1) {
      statsRows = [
        { type: 'candidate-pair', nominated: true, currentRoundTripTime: 0.04 },
        { type: 'inbound-rtp', kind: 'audio', packetsLost: 0, packetsReceived: n * 100, bytesReceived: n * 4000 },
      ];
      await vi.advanceTimersByTimeAsync(2000);
    }
    return (video.params.encodings as Array<Record<string, unknown>>)[0];
  };

  it('«Найкраще», камера 4K, приймач 4K — 2160p / 16 Мбіт/с', async () => {
    try {
      const enc = await climb({ width: 3840, height: 2160 });
      expect(enc.maxBitrate).toBe(16_000_000);
      const asked = track.applyConstraints.mock.calls.map((c) => (c[0].height as ConstrainULongRange).ideal);
      expect(asked).toContain(2160);
    } finally {
      localStorage.removeItem('np.callPrefs.v1');
    }
  });

  it('«Найкраще», але камера лише 1080 — стеля 1080p / 3,5 Мбіт/с, 2160 не просимо', async () => {
    try {
      const enc = await climb({ width: 1920, height: 1080 });
      expect(enc.maxBitrate).toBe(3_500_000);
      const asked = track.applyConstraints.mock.calls.map((c) => (c[0].height as ConstrainULongRange).ideal);
      expect(asked).not.toContain(2160);
    } finally {
      localStorage.removeItem('np.callPrefs.v1');
    }
  });

  /**
   * Правдиві числа дзвінка з getStats: частота мікрофона, такт кодека, кбіт/с
   * звуку назовні, реальна коротка сторона й кадри/с у обидва боки.
   * Червоніє до 19.10: цих полів у знімку не було.
   */
  it('stats: micHz, audioHz, audioKbpsOut, sentP/sentFps, recvP/recvFps — з getStats', async () => {
    const { pc } = await start();
    const stream = (engine.callEngine as unknown as { localStream: FakeMediaStream }).localStream;
    (stream.getAudioTracks()[0] as Record<string, unknown>).getSettings = () => ({ sampleRate: 48000 });
    pc.connect();
    const rows = (audioBytes: number) => [
      { type: 'codec', id: 'opus', mimeType: 'audio/opus', clockRate: 48000 },
      { type: 'outbound-rtp', kind: 'audio', codecId: 'opus', bytesSent: audioBytes },
      { type: 'outbound-rtp', kind: 'video', bytesSent: 1, frameWidth: 1080, frameHeight: 1920, framesPerSecond: 29.6 },
      { type: 'inbound-rtp', kind: 'video', packetsLost: 0, packetsReceived: audioBytes, bytesReceived: audioBytes, frameWidth: 1280, frameHeight: 720, framesPerSecond: 24 },
    ];
    statsRows = rows(0);
    (pc as unknown as { getStats: () => Promise<Map<string, unknown>> }).getStats = async () =>
      new Map(statsRows.map((r) => [String((r as Record<string, unknown>).id ?? Math.random()), r]));
    await vi.advanceTimersByTimeAsync(2000);
    statsRows = rows(16_000);
    await vi.advanceTimersByTimeAsync(2000);
    const stats = engine.callEngine.getSnapshot().stats!;
    expect(stats.micHz).toBe(48000);
    expect(stats.audioHz).toBe(48000);
    expect(stats.audioKbpsOut).toBe(64);
    expect(stats.sentP).toBe(1080);
    expect(stats.sentFps).toBe(30);
    expect(stats.recvP).toBe(720);
    expect(stats.recvFps).toBe(24);
  });
});
