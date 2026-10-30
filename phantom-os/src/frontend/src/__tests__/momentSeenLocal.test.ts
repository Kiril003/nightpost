/**
 * msg-x зріз 4: «переглянуто» живе на телефоні глядача — мітка не зникає,
 * якщо вузол не прийняв POST /seen або віддав старий список.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const bridge = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('../services/api', async (orig) => {
  const real = await orig<typeof import('../services/api')>();
  return { ...real, request: bridge.request };
});

import { useMoments } from '../services/moments';
import { SEEN_KEY, seenLocally } from '../services/momentSeen';

const NOW = Date.now();
const wire = (id: string, seen = false, until = NOW + 3_600_000) => ({
  id, from_id: 'olena', from_name: '', mine: false, at_ms: NOW - 60_000, until_ms: until,
  keep: false, audience: { kind: 'contacts' }, text: id, seen, to_count: 0, media: [],
});

beforeEach(() => {
  localStorage.clear();
  bridge.request.mockReset();
});
afterEach(() => useMoments.setState({ list: null }));

describe('мітка «переглянуто» локально', () => {
  it('вузол не прийняв /seen — після оновлення момент усе одно переглянутий', async () => {
    bridge.request.mockImplementation(async (method: string) => {
      if (method === 'GET') return [wire('a'), wire('b')];
      throw new Error('offline');
    });
    await useMoments.getState().refresh();
    await useMoments.getState().markSeen('a');
    await useMoments.getState().refresh();
    const seen = Object.fromEntries(useMoments.getState().list!.map((m) => [m.id, m.seen]));
    expect(seen).toEqual({ a: true, b: false });
  });

  it('записи прострочених моментів прибираються, бита сховка не ламає список', async () => {
    localStorage.setItem(SEEN_KEY, JSON.stringify({ old: NOW - 1000, a: NOW + 1000 }));
    expect(seenLocally(NOW)('old')).toBe(false);
    expect(seenLocally(NOW)('a')).toBe(true);
    localStorage.setItem(SEEN_KEY, '{oops');
    bridge.request.mockResolvedValue([wire('a', true)]);
    await useMoments.getState().refresh();
    expect(useMoments.getState().list!.map((m) => m.seen)).toEqual([true]);
  });
});
