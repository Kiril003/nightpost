import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DEFAULT_CALL_PREFS, saveCallPrefs } from '../services/callPrefs';
import { DEFAULT_PLAN, localVideoCap, readViewState, remoteTile, setCallBudget } from '../services/callView';
import { capVideoSender } from '../services/callVideo';

const setNavigatorConnection = (conn: { type?: string; saveData?: boolean } | undefined) => {
  Object.defineProperty(navigator, 'connection', {
    value: conn,
    configurable: true,
    writable: true,
  });
};

describe('callView preferences integration', () => {
  beforeEach(() => {
    localStorage.clear();
    setCallBudget(null);
    remoteTile.setForcedHidden(false);
    remoteTile.set(null);
    setNavigatorConnection(undefined);
  });

  afterEach(() => {
    localStorage.clear();
    setCallBudget(null);
    remoteTile.setForcedHidden(false);
    remoteTile.set(null);
    setNavigatorConnection(undefined);
  });

  it('uses mobile save mode when on cellular even if system saveData is false', () => {
    saveCallPrefs({ ...DEFAULT_CALL_PREFS, mobile: 'save' });
    setNavigatorConnection({ type: 'cellular', saveData: false });

    const state = readViewState();
    expect(state.saveData).toBe(true);
    expect(state.budgetMBh).toBe(120);
    expect(localVideoCap()).toBe(360);
  });

  it('uses wifi best mode when on wifi even if system saveData is true', () => {
    saveCallPrefs({ ...DEFAULT_CALL_PREFS, wifi: 'best' });
    setNavigatorConnection({ type: 'wifi', saveData: true });

    const state = readViewState();
    expect(state.saveData).toBe(false);
    expect(state.budgetMBh).toBeNull();
  });

  it('lets setCallBudget(500) win over the preference budget', () => {
    saveCallPrefs({ ...DEFAULT_CALL_PREFS, mobile: 'save' });
    setNavigatorConnection({ type: 'cellular', saveData: false });
    setCallBudget(500);

    const state = readViewState();
    expect(state.saveData).toBe(true);
    expect(state.budgetMBh).toBe(500);
  });

  it('forces tile visibility to false when remoteTile.setForcedHidden(true) is active', () => {
    const el = document.createElement('div');
    remoteTile.set(el);
    expect(readViewState().tiles[0].vis).toBe(true);

    remoteTile.setForcedHidden(true);
    expect(readViewState().tiles[0].vis).toBe(false);

    remoteTile.setForcedHidden(false);
    expect(readViewState().tiles[0].vis).toBe(true);
  });

  it('clamps capVideoSender height and camera constraints to localVideoCap()', async () => {
    saveCallPrefs({ ...DEFAULT_CALL_PREFS, wifi: 'best', video: 360 });
    setNavigatorConnection({ type: 'wifi', saveData: false });

    const applyConstraints = vi.fn().mockResolvedValue(undefined);
    const setParameters = vi.fn().mockResolvedValue(undefined);
    const sender = {
      track: {
        applyConstraints,
        getSettings: () => ({ width: 1280, height: 720 }),
      },
      getParameters: () => ({ encodings: [{}] }),
      setParameters,
    } as unknown as RTCRtpSender;

    await capVideoSender(sender, DEFAULT_PLAN, true);

    expect(applyConstraints).toHaveBeenCalledWith({
      height: { ideal: 360 },
      frameRate: { ideal: 30, max: 30 },
    });
    expect(setParameters).toHaveBeenCalledTimes(1);
    const applied = setParameters.mock.calls[0][0];
    expect(applied.encodings[0].scaleResolutionDownBy).toBe(2);
  });
});
