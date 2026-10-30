import type { Message } from '../types/messenger';
import { describe, it, expect, beforeEach } from 'vitest';
import {
  forwardPayload,
  ringRank,
  ringDistance,
  needsGuard,
  redact,
  skipKey,
  isSkipped,
  markSkipped,
} from '../services/ringGuard';

const memStore = (): Storage => {
  const map = new Map<string, string>();
  return {
    get length() {
      return map.size;
    },
    clear: () => map.clear(),
    getItem: (k: string) => map.get(k) ?? null,
    key: (i: number) => Array.from(map.keys())[i] ?? null,
    removeItem: (k: string) => {
      map.delete(k);
    },
    setItem: (k: string, v: string) => {
      map.set(k, v);
    },
  };
};

describe('ringGuard', () => {
  beforeEach(() => {
    try {
      window.localStorage?.removeItem(skipKey);
    } catch {
      /* ignore */
    }
  });

  it('orders rings and triggers guard only when moving to a farther social/work ring', () => {
    expect(needsGuard(ringRank(['close']), ringRank(['work']))).toBe(true);
    expect(needsGuard(ringRank(['work']), ringRank(['close']))).toBe(false);
    expect(needsGuard(ringRank(['friends']), ringRank(['friends']))).toBe(false);
    expect(needsGuard(ringRank(['personal']), ringRank(['close']))).toBe(false);
    expect(ringDistance(ringRank(['close']), ringRank(['work']))).toBe(3);
  });

  it('takes the widest ring from circles and defaults unknown/all to rank 3', () => {
    expect(ringRank(['close', 'work'])).toBe(4);
    expect(ringRank(['personal', 'u:family'])).toBe(0);
    expect(ringRank(['close', 'u:trip'])).toBe(1);
    expect(ringRank(['u:trip'])).toBe(3);
    expect(ringRank(undefined, 'all')).toBe(3);
    expect(ringRank([], 'all')).toBe(3);
    expect(ringRank(undefined, 'personal')).toBe(0);
  });

  it('redacts phone numbers, places, money amounts, and @names', () => {
    const r1 = redact('Call me 0991234567, 4 Lime St');
    expect(r1).not.toContain('0991234567');
    expect(r1).not.toContain('Lime St');
    expect(r1).toContain('▢▢▢');

    const r2 = redact('pay €42 to @olek');
    expect(r2).not.toContain('€42');
    expect(r2).not.toContain('42');
    expect(r2).not.toContain('@olek');
    expect(r2).toContain('▢▢▢');

    const r3 = redact('Зустріч вул. Лісова 4, 500 грн або $12');
    expect(r3).not.toContain('Лісова');
    expect(r3).not.toContain('500 грн');
    expect(r3).not.toContain('$12');

    expect(redact('ok')).toBe('ok');
    expect(redact('Дзвони мені, я на Хрещатику 22')).not.toContain('Хрещатику 22');
    expect(redact('о 19:00 у кафе')).toBe('о 19:00 у кафе');
  });

  it('persists skipped pairs per pair and tolerates a throwing Storage', () => {
    const store = memStore();
    expect(isSkipped(1, 4, store)).toBe(false);
    markSkipped(1, 4, store);
    expect(isSkipped(1, 4, store)).toBe(true);
    expect(isSkipped(1, 3, store)).toBe(false);
    expect(isSkipped(2, 4, store)).toBe(false);

    const throwingStore: Storage = {
      length: 0,
      clear: () => {
        throw new Error('denied');
      },
      getItem: () => {
        throw new Error('denied');
      },
      key: () => null,
      removeItem: () => {
        throw new Error('denied');
      },
      setItem: () => {
        throw new Error('denied');
      },
    };
    expect(() => markSkipped(1, 4, throwingStore)).not.toThrow();
    expect(() => isSkipped(1, 4, throwingStore)).not.toThrow();
    expect(isSkipped(1, 4, throwingStore)).toBe(false);
  });

  it('forwardPayload: hidden text and no reply link; other kinds and plain sends unchanged', () => {
    const m = { id: 'm1', type: 'text', text: 'Дзвони 0991234567', replyTo: { id: 'r1', senderName: 'Оксана', text: 'вул. Хрещатик 1' } } as unknown as Message;
    const out = forwardPayload(m, { redact: true });
    expect(out.text).not.toContain('0991234567');
    expect(out.replyTo).toBeUndefined();
    expect(forwardPayload(m)).toBe(m);
    const photo = { ...m, type: 'image' } as unknown as Message;
    expect(forwardPayload(photo, { redact: true })).toBe(photo);
  });
});
