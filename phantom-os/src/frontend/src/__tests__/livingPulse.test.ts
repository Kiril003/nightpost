/**
 * Жива смуга (25.09): кожне слово має джерело, а відсутність джерела —
 * мовчання, а не «спокійно», «нікого» чи «у мережі».
 */
import { describe, it, expect } from 'vitest';
import {
  durationWord,
  nearWord,
  presenceLine,
  readFaces,
  readPulse,
} from '../services/livingPulse';

const NOW = Date.parse('2026-09-25T20:00:00Z');
const min = (n: number) => NOW - n * 60_000;

describe('час', () => {
  it('час із майбутнього не стає «щойно»', () => {
    expect(durationWord(NOW + 60_000, NOW)).toBeNull();
  });
});

describe('люди', () => {
  it('слово на рівні того, що людина дала', () => {
    expect(nearWord({ near: 'same', precision: 'quarter' })).toBe('поруч');
    expect(nearWord({ near: 'same', precision: 'district' })).toBe('у вашому районі');
    expect(nearWord({ near: 'far', precision: 'exact' })).toBe('далеко');
    expect(nearWord({ near: null, precision: null })).toBe('показує місце');
  });

  it('шапка: показує місце — першим; інакше «коли чули»; ніколи «у мережі»', () => {
    const person = { peerId: 'p', name: 'Оксана', near: 'same' as const, precision: 'quarter' as const, atMs: NOW };
    expect(presenceLine(person, null, NOW)).toBe('поруч · показує місце');
    expect(presenceLine(null, { avatar: null, heardAtMs: min(5), lanSeenAtMs: null }, NOW)).toBe('5 хв тому');
    expect(presenceLine(null, { avatar: null, heardAtMs: min(5 * 60 + 54), lanSeenAtMs: null }, NOW)).toBe('5 год тому');
    expect(presenceLine(null, { avatar: null, heardAtMs: NOW - 10_000, lanSeenAtMs: null }, NOW)).toBe('на звʼязку щойно');
    expect(presenceLine(null, { avatar: null, heardAtMs: NOW - 10_000, lanSeenAtMs: NOW - 10_000 }, NOW)).toBe('поруч');
    // Нічого не чули — нічого не кажемо.
    expect(presenceLine(null, { avatar: null, heardAtMs: null, lanSeenAtMs: null }, NOW)).toBeNull();
    // Тиждень тиші — не «присутність», а історія.
    expect(presenceLine(null, { avatar: null, heardAtMs: min(8 * 24 * 60), lanSeenAtMs: null }, NOW)).toBeNull();
    for (const line of [presenceLine(person, null, NOW), presenceLine(null, { avatar: null, heardAtMs: NOW, lanSeenAtMs: NOW }, NOW)]) {
      expect(line).not.toMatch(/(^|\s)у мережі|онлайн|online/i);
    }
  });
});

describe('дріт', () => {
  it('сміття з мосту — відсутність, а не здогадка', () => {
    const p = readPulse({ sky: { oblast: '  ', since_at: null, feed_silent: false }, near: [{ peer_id: '', name: 'x', near: 'same', precision: 'exact', at: '' }] } as never);
    expect(p.sky).toBeNull();
    expect(p.near).toHaveLength(0);
    expect(p.link.online).toBeNull();
    const q = readPulse({ near: [{ peer_id: 'a', name: 'Ая', near: 'moon', precision: 'x', at: '2026-09-25T19:59:00' }] } as never);
    expect(q.near[0]).toMatchObject({ peerId: 'a', near: null, precision: null });
  });

  it('фото — лише готова data:image, решта — ініціал', () => {
    const f = readFaces([
      { peer_node_id: 'a', avatar_state: 'set', avatar_thumb: 'data:image/webp;base64,AAAA', heard_at: '2026-09-25T19:55:00Z' },
      { peer_node_id: 'b', avatar_state: 'set', avatar_thumb: 'https://evil.example/x.png' },
      { peer_node_id: 'c', avatar_state: 'cleared', avatar_thumb: 'data:image/webp;base64,AAAA' },
    ]);
    expect(f.a.avatar).toMatch(/^data:image\/webp/);
    expect(f.a.heardAtMs).toBe(min(5));
    expect(f.b.avatar).toBeNull();
    expect(f.c.avatar).toBeNull();
  });
});
