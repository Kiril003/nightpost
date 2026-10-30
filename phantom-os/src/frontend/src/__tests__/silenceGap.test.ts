import { describe, expect, it } from 'vitest';
import { silenceGap, SILENCE_EXTRA_PX } from '../services/silenceGap';
import { buildFeedRows } from '../components/messenger/feed/feedModel';
import type { Message } from '../types/messenger';

const H = 3600_000;

describe('silenceGap', () => {
  it('до години — нічого', () => {
    expect(silenceGap(0)).toBeNull();
    expect(silenceGap(59 * 60_000)).toBeNull();
    expect(silenceGap(-5 * H)).toBeNull();
    expect(silenceGap(NaN)).toBeNull();
  });

  it('сходинки 1–6 год, 6–24 год, понад добу', () => {
    expect(silenceGap(H)).toBeNull();
    expect(silenceGap(5.5 * H)).toBeNull();
    expect(silenceGap(6 * H)).toEqual({ step: 2, unit: 'h', n: 6 });
    expect(silenceGap(23 * H)).toEqual({ step: 2, unit: 'h', n: 23 });
    expect(silenceGap(48 * H)).toEqual({ step: 3, unit: 'd', n: 2 });
  });

  it('відступ росте й не перевищує 32px', () => {
    expect(SILENCE_EXTRA_PX[1]).toBeLessThan(SILENCE_EXTRA_PX[2]);
    expect(SILENCE_EXTRA_PX[2]).toBeLessThan(SILENCE_EXTRA_PX[3]);
    expect(SILENCE_EXTRA_PX[3]).toBeLessThanOrEqual(32);
  });
});

const msg = (id: string, sentAt: string): Message =>
  ({
    id,
    senderId: 'p1',
    senderName: 'Петро',
    senderAvatar: '',
    timestamp: '12:00',
    sentAt,
    type: 'text',
    isSelf: false,
    text: id,
  }) as Message;

describe('buildFeedRows: розрив тиші', () => {
  const today = new Date('2026-03-20T20:00:00Z');

  it('A4: 5 годин мовчки, 7 годин дають роздільник, 10 хвилин ні', () => {
    const rows = buildFeedRows(
      [msg('a', '2026-03-20T08:00:00Z'), msg('b', '2026-03-20T08:10:00Z'), msg('c', '2026-03-20T13:10:00Z')],
      'me',
      today,
    );
    expect(rows[0].silence).toBeUndefined();
    expect(rows[1].silence).toBeUndefined();
    expect(rows[2].silence).toBeUndefined();
    const long = buildFeedRows([msg('a', '2026-03-20T06:00:00Z'), msg('b', '2026-03-20T13:10:00Z')], 'me', today);
    expect(long[1].silence).toEqual({ step: 2, unit: 'h', n: 7 });
  });
});
