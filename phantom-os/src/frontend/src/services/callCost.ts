import { callEngine } from './callEngine';
import { currentNet, type CallNet } from './callPrefs';

export type CallKind = 'audio' | 'video';
export type CallCostStore = Record<CallNet, Record<CallKind, number[]>>;

export const CALL_COST_KEY = 'np.callCost.v1';
export const MIN_SAMPLES = 3;
const MAX_SAMPLES = 20;

const emptyStore = (): CallCostStore => ({
  wifi: { audio: [], video: [] },
  mobile: { audio: [], video: [] },
});

const cleanSamples = (raw: unknown): number[] => {
  if (!Array.isArray(raw)) return [];
  return raw.filter((n): n is number => typeof n === 'number' && Number.isFinite(n) && n > 0);
};

const cleanBucket = (raw: unknown): Record<CallKind, number[]> => {
  if (!raw || typeof raw !== 'object') return { audio: [], video: [] };
  const rec = raw as Record<string, unknown>;
  return {
    audio: cleanSamples(rec.audio),
    video: cleanSamples(rec.video),
  };
};

export const loadCallCost = (): CallCostStore => {
  try {
    if (typeof localStorage === 'undefined') return emptyStore();
    const raw = localStorage.getItem(CALL_COST_KEY);
    if (!raw) return emptyStore();
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object') return emptyStore();
    const rec = parsed as Record<string, unknown>;
    return {
      wifi: cleanBucket(rec.wifi),
      mobile: cleanBucket(rec.mobile),
    };
  } catch {
    return emptyStore();
  }
};

export const recordCallSample = (net: CallNet, kind: CallKind, mbPerMin: number): void => {
  if (typeof mbPerMin !== 'number' || !Number.isFinite(mbPerMin) || mbPerMin <= 0) return;
  if (net !== 'wifi' && net !== 'mobile') return;
  if (kind !== 'audio' && kind !== 'video') return;
  try {
    const store = loadCallCost();
    store[net][kind] = [...store[net][kind], mbPerMin].slice(-MAX_SAMPLES);
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(CALL_COST_KEY, JSON.stringify(store));
    }
  } catch {
    // Ігноруємо помилки локального сховища
  }
};

export const estimateMB10 = (samples: readonly number[]): number | null => {
  if (!Array.isArray(samples)) return null;
  const valid = samples.filter((n) => typeof n === 'number' && Number.isFinite(n) && n > 0);
  if (valid.length < MIN_SAMPLES) return null;
  const sorted = [...valid].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  const median =
    sorted.length % 2 === 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
  return median * 10;
};

export const callCostEstimate = (
  net: CallNet,
  kind: CallKind,
  store: CallCostStore = loadCallCost(),
): number | null => estimateMB10(store[net]?.[kind] ?? []);

export interface TrackCallCostDeps {
  subscribe?: (listener: () => void) => () => void;
  getSnapshot?: () => {
    state: string;
    media?: 'audio' | 'video';
    hasCamera?: boolean;
    peeking?: boolean;
    audioOnly?: boolean;
  };
  readBytes?: () => Promise<number | null> | number | null;
  now?: () => number;
  conn?: () => { type?: unknown } | undefined;
}

interface ActiveSession {
  startMs: number;
  video: boolean;
  baseline: number | null;
  lastBytes: number;
  basePromise: Promise<number | null>;
  timer: ReturnType<typeof setInterval> | null;
}

export const trackCallCost = (deps: TrackCallCostDeps = {}): (() => void) => {
  const subscribe = deps.subscribe ?? callEngine.subscribe;
  const getSnapshot = deps.getSnapshot ?? callEngine.getSnapshot;
  const readBytes = deps.readBytes ?? (() => callEngine.dataUsedBytes());
  const now = deps.now ?? Date.now;
  const conn =
    deps.conn ??
    (() =>
      typeof navigator !== 'undefined'
        ? (navigator as Navigator & { connection?: { type?: unknown } }).connection
        : undefined);

  const safeRead = async (): Promise<number | null> => {
    try {
      const val = await readBytes();
      return typeof val === 'number' && Number.isFinite(val) && val >= 0 ? val : null;
    } catch {
      return null;
    }
  };

  let session: ActiveSession | null = null;

  const onStateChange = () => {
    try {
      const snap = getSnapshot();
      if (snap.state === 'active') {
        const isVideo =
          snap.media === 'audio'
            ? Boolean(snap.hasCamera || snap.peeking) && !snap.audioOnly
            : !snap.audioOnly;
        if (!session) {
          const current: ActiveSession = {
            startMs: now(),
            video: isVideo,
            baseline: null,
            lastBytes: 0,
            basePromise: Promise.resolve(null),
            timer: null,
          };
          session = current;
          current.basePromise = safeRead().then((val) => {
            if (val !== null) {
              current.baseline = val;
              if (val > current.lastBytes) current.lastBytes = val;
            }
            return val;
          });
          current.timer = setInterval(() => {
            void safeRead().then((val) => {
              if (val !== null && val >= current.lastBytes) {
                current.lastBytes = val;
              }
            });
          }, 2000);
        } else if (isVideo) {
          session.video = true;
        }
      } else if (session) {
        const finished = session;
        session = null;
        if (finished.timer !== null) {
          clearInterval(finished.timer);
          finished.timer = null;
        }
        const endMs = now();
        const finalPromise = safeRead();
        void Promise.all([finished.basePromise, finalPromise]).then(([, finalVal]) => {
          try {
            const last =
              finalVal !== null && finalVal >= finished.lastBytes
                ? finalVal
                : finished.lastBytes;
            if (finished.baseline === null) return;
            const delta = last - finished.baseline;
            const minutes = (endMs - finished.startMs) / 60000;
            if (minutes >= 0.5 && delta > 0) {
              recordCallSample(
                currentNet(conn()),
                finished.video ? 'video' : 'audio',
                delta / 1e6 / minutes,
              );
            }
          } catch {
            // ignore
          }
        });
      }
    } catch {
      // ignore
    }
  };

  const unsub = subscribe(onStateChange);
  onStateChange();

  return () => {
    if (session?.timer !== null && session?.timer !== undefined) {
      clearInterval(session.timer);
      session.timer = null;
    }
    session = null;
    unsub();
  };
};

let trackingStarted = false;

export const ensureCallCostTracking = (): void => {
  if (trackingStarted || typeof window === 'undefined') return;
  trackingStarted = true;
  try {
    trackCallCost();
  } catch {
    // ignore
  }
};
