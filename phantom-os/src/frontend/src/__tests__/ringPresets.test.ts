import { afterEach, describe, expect, it } from 'vitest';
import { DEFAULT_LOOK, normalizeLook } from '../services/appearance';
import {
  RING_PRESETS_KEY,
  readRingPresets,
  resolveLook,
  useRingPresets,
} from '../services/ringPresets';

afterEach(() => {
  localStorage.clear();
  useRingPresets.setState({ presets: {} });
});

describe('ringPresets', () => {
  it('робоча розмова без власного тла бере щільність і тло з кола «work»', () => {
    useRingPresets.getState().setRingBubbles('work', 'compact');
    useRingPresets.getState().setRingWallpaper('work', { kind: 'none' });

    const resolved = resolveLook(
      { id: 'c_work', circles: ['work'] },
      DEFAULT_LOOK,
      useRingPresets.getState().presets,
    );
    expect(resolved).toEqual({
      ring: 'work',
      bubbles: 'compact',
      wallpaper: { kind: 'none' },
    });
  });

  it('власне тло розмови переживає перехід між колами, а бульбашки переходять на нове коло', () => {
    const look = normalizeLook({
      wallpaper: { kind: 'builtin', id: 'contour' },
      chats: { c1: { kind: 'builtin', id: 'clay' } },
      bubbles: 'comfortable',
    });
    const presets = {
      work: { bubbles: 'compact' as const, wallpaper: { kind: 'none' as const } },
      close: { wallpaper: { kind: 'builtin' as const, id: 'sage' as const } },
    };

    const inWork = resolveLook({ id: 'c1', circles: ['work'] }, look, presets);
    expect(inWork.ring).toBe('work');
    expect(inWork.wallpaper).toEqual({ kind: 'builtin', id: 'clay' });
    expect(inWork.bubbles).toBe('compact');

    const inClose = resolveLook({ id: 'c1', circles: ['close'] }, look, presets);
    expect(inClose.ring).toBe('close');
    expect(inClose.wallpaper).toEqual({ kind: 'builtin', id: 'clay' });
    expect(inClose.bubbles).toBe('comfortable');
  });

  it('розмова поза колами бере загальний вигляд', () => {
    const presets = {
      work: { bubbles: 'compact' as const, wallpaper: { kind: 'none' as const } },
    };
    expect(resolveLook({ id: 'c0', circle: 'all' }, DEFAULT_LOOK, presets)).toEqual({
      ring: null,
      wallpaper: DEFAULT_LOOK.wallpaper,
      bubbles: DEFAULT_LOOK.bubbles,
    });
    expect(resolveLook(undefined, DEFAULT_LOOK, presets)).toEqual({
      ring: null,
      wallpaper: DEFAULT_LOOK.wallpaper,
      bubbles: DEFAULT_LOOK.bubbles,
    });
  });

  it('поламаний JSON у сховищі не кидає помилку і повертає порожні налаштування', () => {
    localStorage.setItem(RING_PRESETS_KEY, '{broken-json');
    expect(() => readRingPresets()).not.toThrow();
    expect(readRingPresets()).toEqual({});
  });

  it('відкидає невідомі поля і фото кола', () => {
    localStorage.setItem(
      RING_PRESETS_KEY,
      JSON.stringify({
        work: {
          bubbles: 'compact',
          wallpaper: { kind: 'photo', url: 'https://evil.example/a.jpg' },
          extra: 'ignored',
        },
        close: {
          bubbles: 'giant',
          wallpaper: { kind: 'builtin', id: 'lava' },
        },
        friends: {
          wallpaper: { kind: 'photo', url: 'data:image/jpeg;base64,AQID' },
        },
      }),
    );

    expect(readRingPresets()).toEqual({ work: { bubbles: 'compact' } });
  });

  it('фото як тло кола не зберігається', () => {
    useRingPresets.getState().setRingWallpaper('friends', { kind: 'photo', url: 'data:image/jpeg;base64,AQID' });
    expect(useRingPresets.getState().presets.friends).toBeUndefined();
    expect(localStorage.getItem(RING_PRESETS_KEY)).toBeNull();
  });
});
