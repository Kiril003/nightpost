import type { CallNet } from './callPrefs';

export const CALL_MEMORY_KEY = 'np.callMemory.v1';
export const STEADY_MS = 20_000;
export const MIN_VIDEO_KBPS = 300;
export const MEMORY_TTL_MS = 30 * 24 * 3600 * 1000;

const MAX_ENTRIES = 200;

export interface CallMemoryEntry {
  kbps: number;
  h: number;
  at: number;
}

export interface SteadySample {
  kbps: number;
  h: number;
  steadyMs: number;
}

const makeKey = (peerId: string, net: CallNet): string => `${net}:${peerId}`;

const isValidEntry = (v: unknown): v is CallMemoryEntry => {
  if (!v || typeof v !== 'object' || Array.isArray(v)) return false;
  const obj = v as Record<string, unknown>;
  return (
    typeof obj.kbps === 'number' &&
    Number.isFinite(obj.kbps) &&
    obj.kbps > 0 &&
    typeof obj.h === 'number' &&
    Number.isFinite(obj.h) &&
    obj.h > 0 &&
    typeof obj.at === 'number' &&
    Number.isFinite(obj.at)
  );
};

const readStore = (): Record<string, CallMemoryEntry> => {
  try {
    if (typeof localStorage === 'undefined') return {};
    const raw = localStorage.getItem(CALL_MEMORY_KEY);
    if (!raw) return {};
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {};
    const out: Record<string, CallMemoryEntry> = {};
    for (const [k, v] of Object.entries(parsed as Record<string, unknown>)) {
      if (isValidEntry(v)) out[k] = { kbps: v.kbps, h: v.h, at: v.at };
    }
    return out;
  } catch {
    return {};
  }
};

const writeStore = (store: Record<string, CallMemoryEntry>): void => {
  try {
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(CALL_MEMORY_KEY, JSON.stringify(store));
    }
  } catch {
    /* сховище недоступне */
  }
};

export const recordSteady = (
  peerId: string | null | undefined,
  net: CallNet,
  sample: SteadySample,
  now: number = Date.now(),
): void => {
  if (!peerId || typeof peerId !== 'string' || !peerId.trim()) return;
  if (!sample || typeof sample !== 'object') return;
  const { kbps, h, steadyMs } = sample;
  if (
    !Number.isFinite(steadyMs) ||
    steadyMs < STEADY_MS ||
    !Number.isFinite(kbps) ||
    kbps < MIN_VIDEO_KBPS ||
    !Number.isFinite(h) ||
    h <= 0
  ) {
    return;
  }
  const store = readStore();
  store[makeKey(peerId, net)] = { kbps, h, at: now };
  const keys = Object.keys(store);
  if (keys.length > MAX_ENTRIES) {
    keys.sort((a, b) => store[a].at - store[b].at);
    for (let i = 0; i < keys.length - MAX_ENTRIES; i += 1) {
      delete store[keys[i]];
    }
  }
  writeStore(store);
};

export const startPlan = (
  peerId: string | null | undefined,
  net: CallNet,
  now: number = Date.now(),
): { h: number; kbps: number } | null => {
  if (!peerId || typeof peerId !== 'string' || !peerId.trim()) return null;
  const store = readStore();
  const key = makeKey(peerId, net);
  const entry = store[key];
  if (!entry) return null;
  if (now - entry.at >= MEMORY_TTL_MS) {
    delete store[key];
    writeStore(store);
    return null;
  }
  return { h: entry.h, kbps: entry.kbps };
};

export const openingCap = (
  ids: string[],
  media: 'audio' | 'video',
  net: CallNet,
  now: number = Date.now(),
): boolean => {
  if (media !== 'video' || !Array.isArray(ids) || ids.length === 0) return true;
  for (const id of ids) {
    const plan = startPlan(id, net, now);
    if (plan) return plan.h < 1080;
  }
  return true;
};

export const forgetCallMemory = (): void => {
  try {
    if (typeof localStorage !== 'undefined') {
      localStorage.removeItem(CALL_MEMORY_KEY);
    }
  } catch {
    /* сховище недоступне */
  }
};
