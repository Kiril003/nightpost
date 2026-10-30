import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  CALL_PREFS_KEY,
  DEFAULT_CALL_PREFS,
  currentNet,
  effectiveCall,
  loadCallPrefs,
  onCallPrefsChange,
  saveCallPrefs,
} from '../services/callPrefs';

describe('callPrefs', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
  });

  it('returns defaults when storage is empty, throws, or holds junk', () => {
    expect(loadCallPrefs()).toEqual(DEFAULT_CALL_PREFS);

    localStorage.setItem(CALL_PREFS_KEY, '{broken-json');
    expect(loadCallPrefs()).toEqual(DEFAULT_CALL_PREFS);

    localStorage.setItem(CALL_PREFS_KEY, 'null');
    expect(loadCallPrefs()).toEqual(DEFAULT_CALL_PREFS);

    localStorage.setItem(CALL_PREFS_KEY, '[1,2,3]');
    expect(loadCallPrefs()).toEqual(DEFAULT_CALL_PREFS);

    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage blocked');
    });
    expect(loadCallPrefs()).toEqual(DEFAULT_CALL_PREFS);
  });

  it('falls back per field when individual values are unknown', () => {
    localStorage.setItem(
      CALL_PREFS_KEY,
      JSON.stringify({ wifi: 'save', mobile: 'invalid', video: 480 }),
    );
    expect(loadCallPrefs()).toEqual({
      wifi: 'save',
      mobile: DEFAULT_CALL_PREFS.mobile,
      video: DEFAULT_CALL_PREFS.video,
    });

    localStorage.setItem(
      CALL_PREFS_KEY,
      JSON.stringify({ wifi: 123, mobile: 'best', video: 720 }),
    );
    expect(loadCallPrefs()).toEqual({
      wifi: DEFAULT_CALL_PREFS.wifi,
      mobile: 'best',
      video: 720,
    });
  });

  it('saves preferences and notifies subscribers', () => {
    const seen: unknown[] = [];
    const off = onCallPrefsChange((p) => seen.push(p));

    saveCallPrefs({ wifi: 'auto', mobile: 'save', video: 360 });
    expect(loadCallPrefs()).toEqual({ wifi: 'auto', mobile: 'save', video: 360 });
    expect(seen).toEqual([{ wifi: 'auto', mobile: 'save', video: 360 }]);

    off();
    saveCallPrefs({ wifi: 'best', mobile: 'best', video: 1080 });
    expect(seen).toHaveLength(1);
  });

  it('maps connection types in currentNet with mobile as the cautious fallback', () => {
    expect(currentNet({ type: 'wifi' })).toBe('wifi');
    expect(currentNet({ type: 'ethernet' })).toBe('wifi');
    expect(currentNet({ type: 'cellular' })).toBe('mobile');
    expect(currentNet({ type: 'unknown' })).toBe('mobile');
    expect(currentNet({ type: 'other' })).toBe('mobile');
    expect(currentNet({})).toBe('mobile');
    expect(currentNet(undefined)).toBe('mobile');
    expect(currentNet(null)).toBe('mobile');
  });

  it('computes effectiveCall for each mode × systemSaveData', () => {
    const base = { wifi: 'best' as const, mobile: 'auto' as const, video: 'auto' as const };

    expect(effectiveCall({ ...base, mobile: 'save' }, 'mobile', false)).toEqual({
      saveData: true,
      budgetMBh: 120,
      maxHeight: 360,
      uhd: false,
    });
    expect(effectiveCall({ ...base, mobile: 'save' }, 'mobile', true)).toEqual({
      saveData: true,
      budgetMBh: 120,
      maxHeight: 360,
      uhd: false,
    });

    expect(effectiveCall({ ...base, mobile: 'auto' }, 'mobile', false)).toEqual({
      saveData: false,
      budgetMBh: null,
      maxHeight: null,
      uhd: false,
    });
    expect(effectiveCall({ ...base, mobile: 'auto' }, 'mobile', true)).toEqual({
      saveData: true,
      budgetMBh: null,
      maxHeight: null,
      uhd: false,
    });

    expect(effectiveCall({ ...base, wifi: 'best' }, 'wifi', false)).toEqual({
      saveData: false,
      budgetMBh: null,
      maxHeight: null,
      uhd: true,
    });
    expect(effectiveCall({ ...base, wifi: 'best' }, 'wifi', true)).toEqual({
      saveData: false,
      budgetMBh: null,
      maxHeight: null,
      uhd: true,
    });
  });

  it('uhd only for «Найкраще» without a video cap', () => {
    expect(effectiveCall({ wifi: 'best', mobile: 'auto', video: 'auto' }, 'wifi', false).uhd).toBe(true);
    expect(effectiveCall({ wifi: 'best', mobile: 'auto', video: 1080 }, 'wifi', false).uhd).toBe(false);
    expect(effectiveCall({ wifi: 'auto', mobile: 'best', video: 'auto' }, 'wifi', false).uhd).toBe(false);
  });

  it('applies the video cap min rule', () => {
    expect(
      effectiveCall({ wifi: 'best', mobile: 'auto', video: 720 }, 'wifi', false).maxHeight,
    ).toBe(720);
    expect(
      effectiveCall({ wifi: 'best', mobile: 'auto', video: 1080 }, 'mobile', false).maxHeight,
    ).toBe(1080);
    expect(
      effectiveCall({ wifi: 'best', mobile: 'save', video: 720 }, 'mobile', false).maxHeight,
    ).toBe(360);
    expect(
      effectiveCall({ wifi: 'best', mobile: 'save', video: 1080 }, 'mobile', false).maxHeight,
    ).toBe(360);
    expect(
      effectiveCall({ wifi: 'best', mobile: 'best', video: 360 }, 'mobile', false).maxHeight,
    ).toBe(360);
  });
});
