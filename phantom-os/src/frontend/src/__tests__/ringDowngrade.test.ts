import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CIRCLES } from '../components/messenger/todayFormat';
import { chatPins } from '../services/chatPins';
import { whereApi } from '../services/peerWhere';
import { apply, isWider, reachOf, type RingPeer } from '../services/ringDowngrade';

const { mockRequest, mockQuiet, mockSetQuiet, mockPhoneBridge } = vi.hoisted(() => ({
  mockRequest: vi.fn(),
  mockQuiet: vi.fn(),
  mockSetQuiet: vi.fn(),
  mockPhoneBridge: vi.fn(),
}));

vi.mock('../services/api', () => ({
  request: mockRequest,
}));

vi.mock('../services/messengerApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/messengerApi')>();
  return {
    ...actual,
    phoneBridge: mockPhoneBridge,
    messengerApi: {
      ...actual.messengerApi,
      quiet: mockQuiet,
      setQuiet: mockSetQuiet,
    },
  };
});

const CLOSE = CIRCLES[0];
const SOCIAL = CIRCLES[1];
const PEER = 'peer-node-1';
const CHAT = 'chat-1';
const peer: RingPeer = { id: PEER, chatId: CHAT, name: 'Оля' };

describe('ringDowngrade', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    chatPins.reset();
    mockPhoneBridge.mockReturnValue({});
    mockQuiet.mockResolvedValue({ enabled: true, except: [] });
    mockSetQuiet.mockResolvedValue(true);
    mockRequest.mockImplementation(async (method: string, path: string) => {
      if (method === 'GET' && path === `/messenger/where/${PEER}`) {
        return { mine: null, peer: null, now_ms: 1000 };
      }
      if (method === 'GET' && path === '/messenger/moments/prefs') {
        return { audience: { kind: 'contacts' } };
      }
      return {};
    });
  });

  it('finds where and quiet rows and applies both', async () => {
    mockRequest.mockImplementation(async (method: string, path: string) => {
      if (method === 'GET' && path === `/messenger/where/${PEER}`) {
        return {
          mine: { precision: 'district', until_ms: null, stand: false },
          peer: null,
          now_ms: 1000,
        };
      }
      if (method === 'DELETE' && path === `/messenger/where/${PEER}`) {
        return { mine: null, peer: null, now_ms: 1000 };
      }
      if (method === 'GET' && path === '/messenger/moments/prefs') {
        return { audience: { kind: 'contacts' } };
      }
      return {};
    });
    mockQuiet.mockResolvedValue({ enabled: true, except: [PEER] });

    const rows = await reachOf(peer);
    expect(rows.map((r) => r.key)).toEqual(['where', 'quiet']);

    const res = await apply(peer, ['where', 'quiet']);
    expect(res).toEqual({ where: 'done', quiet: 'done' });
    expect(mockRequest).toHaveBeenCalledWith('DELETE', `/messenger/where/${PEER}`);
    expect(mockSetQuiet).toHaveBeenCalledWith({ except: [] });
  });

  it('checks ring order in isWider', () => {
    expect(isWider(CLOSE, SOCIAL)).toBe(true);
    expect(isWider(SOCIAL, CLOSE)).toBe(false);
    expect(isWider(CLOSE, CLOSE)).toBe(false);
    expect(isWider(CLOSE, 'all')).toBe(true);
    expect(isWider('custom-ring-1', SOCIAL)).toBe(false);
    expect(isWider(CLOSE, 'custom-ring-1')).toBe(false);
  });

  it('records per-row failure when setQuiet rejects', async () => {
    mockRequest.mockImplementation(async (method: string, path: string) => {
      if (method === 'DELETE' && path === `/messenger/where/${PEER}`) {
        return { mine: null, peer: null, now_ms: 1000 };
      }
      return {};
    });
    mockQuiet.mockResolvedValue({ enabled: true, except: [PEER] });
    mockSetQuiet.mockRejectedValue(new Error('bridge_error'));

    const res = await apply(peer, ['where', 'quiet']);
    expect(res).toEqual({ where: 'done', quiet: 'failed' });
  });

  it('skips where probe when phoneBridge is null', async () => {
    mockPhoneBridge.mockReturnValue(null);
    const whereSpy = vi.spyOn(whereApi, 'get');

    const rows = await reachOf(peer);
    expect(rows.map((r) => r.key)).not.toContain('where');
    expect(whereSpy).not.toHaveBeenCalled();
    whereSpy.mockRestore();
  });

  it('detects and unpins pinned chat via chatPins', async () => {
    chatPins.set(CHAT, true);

    const rows = await reachOf(peer);
    expect(rows.map((r) => r.key)).toEqual(['pin']);

    const res = await apply(peer, ['pin']);
    expect(res).toEqual({ pin: 'done' });
    expect(chatPins.choice(CHAT)).toBe(false);
  });

  it('detects moments audience peer and posts revoke', async () => {
    mockRequest.mockImplementation(async (method: string, path: string) => {
      if (method === 'GET' && path === `/messenger/where/${PEER}`) {
        return { mine: null, peer: null, now_ms: 1000 };
      }
      if (method === 'GET' && path === '/messenger/moments/prefs') {
        return { audience: { kind: 'people' }, to: [PEER] };
      }
      if (method === 'POST' && path === `/messenger/moments/revoke/${PEER}`) {
        return { ok: true };
      }
      return {};
    });

    const rows = await reachOf(peer);
    expect(rows.map((r) => r.key)).toEqual(['moments']);

    const okRes = await apply(peer, ['moments']);
    expect(okRes).toEqual({ moments: 'done' });
    expect(mockRequest).toHaveBeenCalledWith('POST', `/messenger/moments/revoke/${PEER}`, {});

    mockRequest.mockImplementation(async (method: string, path: string) => {
      if (method === 'POST' && path === `/messenger/moments/revoke/${PEER}`) {
        throw new Error('not_implemented');
      }
      return {};
    });

    const failRes = await apply(peer, ['moments']);
    expect(failRes).toEqual({ moments: 'failed' });
  });
});
