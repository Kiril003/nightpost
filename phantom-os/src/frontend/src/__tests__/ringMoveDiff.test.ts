import { describe, expect, it } from 'vitest';
import { DEFAULT_CIRCLES } from '../stores/circlesStore';
import { ringMoveDiff } from '../services/ringMoveDiff';

describe('ringMoveDiff', () => {
  it('unticking Close with liveWhere and 2 Close-named Moments loses location and 2 moments', () => {
    const diff = ringMoveDiff({
      before: ['close'],
      after: [],
      circles: DEFAULT_CIRCLES,
      liveWhere: true,
      momentsNaming: (ringId) => (ringId === 'close' ? 2 : 0),
    });

    expect(diff.loses).toEqual(['location', 'moments']);
    expect(diff.momentsLost).toBe(2);
    expect(diff.gains).toEqual([]);
  });

  it('unticking Close while Personal stays loses nothing: Personal keeps location and covers Moments', () => {
    const diff = ringMoveDiff({
      before: ['close', 'personal'],
      after: ['personal'],
      circles: DEFAULT_CIRCLES,
      liveWhere: true,
      momentsNaming: (ringId) => {
        if (ringId === 'close') return 2;
        if (ringId === 'personal') return 3;
        return 0;
      },
    });

    expect(diff.loses).toEqual([]);
    expect(diff.momentsLost).toBe(0);
    expect(diff.gains).toEqual([]);
  });

  it('unticking Close while only Social stays loses location and the Close-named Moments', () => {
    const diff = ringMoveDiff({
      before: ['close', 'social'],
      after: ['social'],
      circles: DEFAULT_CIRCLES,
      liveWhere: true,
      momentsNaming: (ringId) => (ringId === 'close' ? 2 : 0),
    });

    expect(diff.loses).toEqual(['location', 'moments']);
    expect(diff.momentsLost).toBe(2);
  });

  it('ticking Social loses nothing', () => {
    const diff = ringMoveDiff({
      before: [],
      after: ['social'],
      circles: DEFAULT_CIRCLES,
      liveWhere: true,
      momentsNaming: () => 0,
    });

    expect(diff.loses).toEqual([]);
    expect(diff.momentsLost).toBe(0);
    expect(diff.gains).toEqual([]);
  });

  it('unknown ring id loses nothing', () => {
    const diff = ringMoveDiff({
      before: ['unknown-ring'],
      after: [],
      circles: DEFAULT_CIRCLES,
      liveWhere: true,
      momentsNaming: () => 2,
    });

    expect(diff.loses).toEqual([]);
    expect(diff.momentsLost).toBe(0);
    expect(diff.gains).toEqual([]);
  });

  it('a staying ring with momentsDefault covers the removed ring Moments', () => {
    const diff = ringMoveDiff({
      before: ['close', 'friends'],
      after: ['friends'],
      circles: DEFAULT_CIRCLES,
      liveWhere: false,
      momentsNaming: (ringId) => (ringId === 'close' ? 4 : 0),
    });

    expect(diff.momentsLost).toBe(0);
    expect(diff.loses).toEqual([]);
  });

  it('dropping the last momentsDefault ring with no active Moments loses nothing', () => {
    const diff = ringMoveDiff({
      before: ['close'],
      after: [],
      circles: DEFAULT_CIRCLES,
      liveWhere: false,
      momentsNaming: () => 0,
    });

    expect(diff.loses).toEqual([]);
  });
});
