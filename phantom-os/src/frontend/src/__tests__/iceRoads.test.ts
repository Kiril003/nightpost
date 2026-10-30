import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

/**
 * Одна дорога до списку ICE для обох рушіїв. Взірець сторожа — callsAreHonest:
 * поведінку доводимо поведінкою (який список отримує RTCPeerConnection), а
 * межу збірки — обходом дерева (адреси STUN у коді немає).
 */

const DEFAULT_ICE = { iceServers: [{ urls: 'stun:node.example:3478' }], turn: false, ttl: 0 };
let iceReply: unknown = DEFAULT_ICE;
let iceRefuses = false;

vi.mock('../services/api', () => ({
  request: vi.fn(async (_method: string, path: string) => {
    if (path.startsWith('/messenger/ice')) {
      if (iceRefuses) throw new Error('404');
      return iceReply;
    }
    return {};
  }),
  ApiError: class extends Error {},
}));

class CapturingPC {
  static built: RTCConfiguration[] = [];
  static last: CapturingPC | null = null;
  localDescription: { type: string; sdp: string } | null = null;
  onicecandidate: ((e: { candidate: unknown }) => void) | null = null;
  constructor(config: RTCConfiguration) {
    CapturingPC.built.push(config);
    CapturingPC.last = this;
  }
  createDataChannel() {
    return {};
  }
  async createOffer() {
    return { type: 'offer', sdp: 'v=0\r\n' };
  }
  async setLocalDescription(d: { type: string; sdp: string }) {
    this.localDescription = d;
  }
  close() {}
}

const ROOT = join(process.cwd(), 'src');
const walk = (dir: string): string[] => {
  const out: string[] = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) {
      if (name === '__tests__' || name === 'node_modules') continue;
      out.push(...walk(p));
      continue;
    }
    if (/\.(ts|tsx)$/.test(name) && !/\.(test|spec)\.tsx?$/.test(name)) out.push(p);
  }
  return out;
};

describe('дороги ICE словами', () => {
  it('називає, що вузол віддав, і мовчить про те, чого не питали', async () => {
    const { iceRoadLabel } = await import('../services/iceRoads');
    expect(iceRoadLabel(null)).toBe('вузол ще не питали');
    expect(iceRoadLabel({ iceServers: [], turn: false, ttl: 0 })).toBe('без STUN і TURN — лише своя мережа');
    expect(
      iceRoadLabel({ iceServers: [{ urls: 'stun:stun.l.google.com:19302' }], turn: false, ttl: 0 }),
    ).toBe('STUN stun.l.google.com:19302 (Google) · без TURN');
    expect(
      iceRoadLabel({
        iceServers: [
          { urls: ['stun:relay.example:3478'] },
          { urls: ['turn:relay.example:3478?transport=udp'], username: 'u', credential: 'c' },
        ],
        turn: true,
        ttl: 3600,
      }),
    ).toBe('STUN relay.example:3478 · TURN relay.example:3478');
    expect(
      iceRoadLabel({ iceServers: [{ urls: 'stun:relay.example:3478' }], turn: false, ttl: 0, inbound: false }),
    ).toBe('STUN relay.example:3478 · без TURN · вхідні дзвінки в цій збірці не приймаються');
    expect(iceRoadLabel({ iceServers: [], turn: false, ttl: 0, inbound: false })).toBe(
      'без STUN і TURN — лише своя мережа · вхідні дзвінки в цій збірці не приймаються',
    );
  });

  it('прапорець inbound читається лише з явного false від вузла', async () => {
    const { iceConfig, resetIceForTest } = await import('../services/iceRoads');
    resetIceForTest();
    iceReply = { iceServers: [], turn: false, ttl: 0, inbound: false };
    expect((await iceConfig()).inbound).toBe(false);
    resetIceForTest();
    iceReply = { iceServers: [], turn: false, ttl: 0 };
    expect((await iceConfig()).inbound).toBeUndefined();
    resetIceForTest();
    iceReply = { iceServers: [], turn: false, ttl: 0, inbound: true };
    expect((await iceConfig()).inbound).toBe(true);
  });

  /**
   * Телефон кешує мережеву недосяжність (DNS/TLS/таймаут) коротко
   * (WebMessengerIce.NEGATIVE_CACHE_MS = 30 с), але ту саму відповідь — з
   * `detail` і без жодного сервера — цей шар раніше тримав ще NO_TURN_RETRY_MS
   * (60 с) ПОВЕРХ того. Мережа, що повернулась за кілька секунд, лишала
   * дзвінок без жодного ICE на майже 90 с. `detail`+порожні сервери тепер не
   * кешуються тут взагалі — наступний виклик питає вузол знову.
   */
  it('відповідь із причиною і без серверів не кешується — наступний виклик питає знову', async () => {
    const { request } = await import('../services/api');
    const { iceConfig, resetIceForTest } = await import('../services/iceRoads');
    resetIceForTest();
    iceReply = { iceServers: [], turn: false, ttl: 0, detail: 'ICE: адресу node.example не знайдено' };
    await iceConfig();
    const callsAfterFirst = vi.mocked(request).mock.calls.length;
    await iceConfig();
    expect(vi.mocked(request).mock.calls.length).toBe(callsAfterFirst + 1);
  });

  it('turn:false без detail — це чесне «немає TURN», і це далі кешується', async () => {
    const { request } = await import('../services/api');
    const { iceConfig, resetIceForTest } = await import('../services/iceRoads');
    resetIceForTest();
    iceReply = { iceServers: [{ urls: 'stun:node.example:3478' }], turn: false, ttl: 0 };
    await iceConfig();
    const callsAfterFirst = vi.mocked(request).mock.calls.length;
    await iceConfig();
    expect(vi.mocked(request).mock.calls.length).toBe(callsAfterFirst);
  });
});

describe('рушій DataChannel бере дороги у вузла, а не зі збірки', () => {
  beforeEach(() => {
    vi.resetModules();
    CapturingPC.built = [];
    iceRefuses = false;
    iceReply = DEFAULT_ICE;
    (globalThis as unknown as Record<string, unknown>).RTCPeerConnection = CapturingPC;
  });
  afterEach(() => {
    delete (globalThis as unknown as Record<string, unknown>).RTCPeerConnection;
  });

  it('ручний офер будується зі списку вузла', async () => {
    const { messengerNetworkEngine } = await import('../services/messengerNetworkEngine');
    const pending = messengerNetworkEngine.createManualOffer();
    await vi.waitFor(() => expect(CapturingPC.built.length).toBe(1));
    expect(CapturingPC.built[0]).toEqual({ iceServers: [{ urls: 'stun:node.example:3478' }] });
    await vi.waitFor(() => expect(typeof CapturingPC.last?.onicecandidate).toBe('function'));
    CapturingPC.last!.onicecandidate!({ candidate: null });
    expect(JSON.parse(atob(await pending))).toEqual({ type: 'offer', sdp: 'v=0\r\n' });
  });

  it('вузол відмовив — список порожній, і діагностика каже саме це', async () => {
    iceRefuses = true;
    const { iceConfig } = await import('../services/iceRoads');
    const { messengerNetworkEngine } = await import('../services/messengerNetworkEngine');
    expect(await iceConfig()).toEqual({ iceServers: [], turn: false, ttl: 0 });
    expect(messengerNetworkEngine.getDiagnostics().iceRoads).toBe('без STUN і TURN — лише своя мережа');
  });

  it('діагностика повторює відповідь вузла, а не рядок із коду', async () => {
    const { iceConfig } = await import('../services/iceRoads');
    const { messengerNetworkEngine } = await import('../services/messengerNetworkEngine');
    expect(messengerNetworkEngine.getDiagnostics().iceRoads).toBe('вузол ще не питали');
    await iceConfig();
    expect(messengerNetworkEngine.getDiagnostics().iceRoads).toBe('STUN node.example:3478 · без TURN');
  });
});

describe('у коді продукту немає жодної адреси STUN', () => {
  it('stun.l.google.com не трапляється поза тестами', () => {
    const files = walk(ROOT);
    expect(files.length).toBeGreaterThan(200);
    const hits = files
      .filter((f) => /stun\d?\.l\.google\.com/.test(readFileSync(f, 'utf8')))
      .map((f) => relative(ROOT, f));
    expect(hits).toEqual([]);
  });
});
