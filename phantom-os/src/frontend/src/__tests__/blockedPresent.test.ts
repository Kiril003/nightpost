import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../services/messengerApi', () => ({
  messengerApi: {
    listBlocked: vi.fn(),
    blockUser: vi.fn(),
    unblockUser: vi.fn(),
    sendMessage: vi.fn(),
  },
}));

import { messengerApi } from '../services/messengerApi';
import { blockedPresent, getBlockedSnapshot, loadBlocked, resetBlockedForTests } from '../services/blockedPresent';

describe('blockedPresent', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetBlockedForTests();
  });

  it('returns true when any member id is in blocked Set or array', () => {
    expect(blockedPresent(['a', 'b', 'c'], new Set(['b']))).toBe(true);
    expect(blockedPresent(['a', 'b', 'c'], ['b'])).toBe(true);
  });

  it('returns false for empty blocked or empty memberIds', () => {
    expect(blockedPresent(['a', 'b'], [])).toBe(false);
    expect(blockedPresent(['a', 'b'], new Set())).toBe(false);
    expect(blockedPresent([], ['a'])).toBe(false);
    expect(blockedPresent([], new Set(['a']))).toBe(false);
  });

  it('sends nothing during pure check', () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    expect(blockedPresent(['a', 'b'], ['b'])).toBe(true);
    expect(fetchSpy).not.toHaveBeenCalled();
    for (const fn of Object.values(messengerApi)) {
      expect(fn).not.toHaveBeenCalled();
    }
    fetchSpy.mockRestore();
  });

  it('shares one request between concurrent loads and calls nothing else', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    vi.mocked(messengerApi.listBlocked).mockResolvedValue({ blocked: ['b'] });

    await Promise.all([loadBlocked(), loadBlocked()]);

    expect(messengerApi.listBlocked).toHaveBeenCalledTimes(1);
    for (const [name, fn] of Object.entries(messengerApi)) {
      if (name !== 'listBlocked') {
        expect(fn).not.toHaveBeenCalled();
      }
    }
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(getBlockedSnapshot().has('b')).toBe(true);
    fetchSpy.mockRestore();
  });

  it('re-reads the list after a block made later', async () => {
    vi.mocked(messengerApi.listBlocked).mockResolvedValueOnce({ blocked: [] });
    await loadBlocked();
    expect(getBlockedSnapshot().size).toBe(0);

    vi.mocked(messengerApi.listBlocked).mockResolvedValueOnce({ blocked: ['c'] });
    await loadBlocked();
    expect(getBlockedSnapshot().has('c')).toBe(true);
  });

  it('keeps previous snapshot and does not throw when listBlocked rejects', async () => {
    vi.mocked(messengerApi.listBlocked).mockResolvedValue({ blocked: ['b'] });
    await loadBlocked();
    const prev = getBlockedSnapshot();
    expect(prev.has('b')).toBe(true);

    vi.mocked(messengerApi.listBlocked).mockRejectedValue(new Error('offline'));
    await expect(loadBlocked()).resolves.toBeUndefined();

    expect(getBlockedSnapshot()).toBe(prev);
    expect(getBlockedSnapshot().has('b')).toBe(true);
  });
});
