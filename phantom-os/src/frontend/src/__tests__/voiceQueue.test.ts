import { beforeEach, describe, expect, it } from 'vitest';
import {
  loadPlayed,
  markPlayed,
  nextInRow,
  ROW_GAP_MS,
  VOICE_PLAYED_KEY,
  type VoiceQueueItem,
} from '../services/voiceQueue';

describe('voiceQueue', () => {
  const baseQueue: VoiceQueueItem[] = [
    { id: 'A', url: 'a.ogg', durationSec: 3, ts: 0 },
    { id: 'B', url: 'b.ogg', durationSec: 4, ts: 60_000 },
    { id: 'C', url: 'c.ogg', durationSec: 5, ts: 120_000 },
  ];

  beforeEach(() => {
    localStorage.clear();
  });

  it('returns the next unplayed item in the row', () => {
    expect(nextInRow(baseQueue, 'A', new Set())).toEqual(baseQueue[1]);
  });

  it('skips already played items', () => {
    expect(nextInRow(baseQueue, 'A', new Set(['B']))).toEqual(baseQueue[2]);
  });

  it('stops when the gap to the next unplayed item exceeds 10 minutes and allows exact 10 minutes', () => {
    const overGapQueue: VoiceQueueItem[] = [
      { id: 'A', url: 'a.ogg', durationSec: 3, ts: 0 },
      { id: 'B', url: 'b.ogg', durationSec: 4, ts: 11 * 60 * 1000 },
    ];
    expect(nextInRow(overGapQueue, 'A', new Set())).toBeNull();

    const exactGapQueue: VoiceQueueItem[] = [
      { id: 'A', url: 'a.ogg', durationSec: 3, ts: 0 },
      { id: 'B', url: 'b.ogg', durationSec: 4, ts: ROW_GAP_MS },
    ];
    expect(nextInRow(exactGapQueue, 'A', new Set())).toEqual(exactGapQueue[1]);
  });

  it('returns null for an unknown currentId or the last item', () => {
    expect(nextInRow(baseQueue, 'unknown', new Set())).toBeNull();
    expect(nextInRow(baseQueue, 'C', new Set())).toBeNull();
  });

  it('persists played ids, handles corrupt storage, and caps at 500 newest items', () => {
    markPlayed('A');
    expect(loadPlayed().has('A')).toBe(true);

    localStorage.setItem(VOICE_PLAYED_KEY, '{not-valid-json');
    expect(loadPlayed().size).toBe(0);

    localStorage.clear();
    for (let i = 0; i < 501; i += 1) {
      markPlayed(`msg-${i}`);
    }
    const played = loadPlayed();
    expect(played.size).toBe(500);
    expect(played.has('msg-0')).toBe(false);
    expect(played.has('msg-1')).toBe(true);
    expect(played.has('msg-500')).toBe(true);
  });
});
