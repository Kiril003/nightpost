export type CallMode = 'auto' | 'save' | 'best';
export type CallNet = 'wifi' | 'mobile';
export type VideoCap = 'auto' | 360 | 720 | 1080;

export interface CallPrefs {
  wifi: CallMode;
  mobile: CallMode;
  video: VideoCap;
}

export interface EffectiveCall {
  saveData: boolean;
  budgetMBh: number | null;
  maxHeight: number | null;
  /** «Найкраще» без стелі відео: дозволено 1440p/2160p, якщо камера й приймач тягнуть. */
  uhd: boolean;
}

export const CALL_PREFS_KEY = 'np.callPrefs.v1';

export const DEFAULT_CALL_PREFS: CallPrefs = {
  wifi: 'best',
  mobile: 'auto',
  video: 'auto',
};

const isCallMode = (v: unknown): v is CallMode => v === 'auto' || v === 'save' || v === 'best';
const isVideoCap = (v: unknown): v is VideoCap =>
  v === 'auto' || v === 360 || v === 720 || v === 1080;

const sanitizePrefs = (raw: unknown): CallPrefs => {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    return { ...DEFAULT_CALL_PREFS };
  }
  const obj = raw as Record<string, unknown>;
  return {
    wifi: isCallMode(obj.wifi) ? obj.wifi : DEFAULT_CALL_PREFS.wifi,
    mobile: isCallMode(obj.mobile) ? obj.mobile : DEFAULT_CALL_PREFS.mobile,
    video: isVideoCap(obj.video) ? obj.video : DEFAULT_CALL_PREFS.video,
  };
};

export const loadCallPrefs = (): CallPrefs => {
  try {
    if (typeof localStorage === 'undefined') return { ...DEFAULT_CALL_PREFS };
    const raw = localStorage.getItem(CALL_PREFS_KEY);
    if (!raw) return { ...DEFAULT_CALL_PREFS };
    return sanitizePrefs(JSON.parse(raw));
  } catch {
    return { ...DEFAULT_CALL_PREFS };
  }
};

type PrefsListener = (prefs: CallPrefs) => void;
const listeners = new Set<PrefsListener>();

export const onCallPrefsChange = (fn: PrefsListener): (() => void) => {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
};

export const saveCallPrefs = (prefs: CallPrefs): void => {
  const next = sanitizePrefs(prefs);
  try {
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(CALL_PREFS_KEY, JSON.stringify(next));
    }
  } catch {
    /* storage unavailable */
  }
  listeners.forEach((fn) => {
    try {
      fn(next);
    } catch {
      /* listener error */
    }
  });
};

export const currentNet = (conn?: { type?: unknown } | null): CallNet => {
  const type = conn?.type;
  if (type === 'wifi' || type === 'ethernet') return 'wifi';
  if (type === 'cellular') return 'mobile';
  return 'mobile';
};

export const effectiveCall = (
  prefs: CallPrefs,
  net: CallNet,
  systemSaveData: boolean,
): EffectiveCall => {
  const mode = prefs[net];
  let saveData: boolean;
  let budgetMBh: number | null;
  let maxHeight: number | null;

  if (mode === 'save') {
    saveData = true;
    budgetMBh = 120;
    maxHeight = 360;
  } else if (mode === 'best') {
    saveData = false;
    budgetMBh = null;
    maxHeight = null;
  } else {
    saveData = Boolean(systemSaveData);
    budgetMBh = null;
    maxHeight = null;
  }

  if (prefs.video !== 'auto') {
    maxHeight = Math.min(maxHeight ?? Infinity, prefs.video);
  }

  return { saveData, budgetMBh, maxHeight, uhd: mode === 'best' && prefs.video === 'auto' };
};
