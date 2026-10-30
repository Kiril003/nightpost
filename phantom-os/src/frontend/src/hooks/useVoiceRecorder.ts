import { useCallback, useEffect, useRef, useState } from 'react';
import { useMicStream } from './useMicStream';
import { useInputMode } from '../stores/inputModeStore';
import type { VoiceDraft } from '../services/voiceDraft';
import { pickVoiceMime, voiceNoteProfile, type VoiceNoteMode } from './voiceNoteProfile';

/**
 * useVoiceRecorder — MediaRecorder-backed tap-to-talk hook.
 *
 * Lifecycle:
 *   start()  → requests mic (via shared useMicStream), starts recording,
 *              spins up an AnalyserNode so consumers can poll `amplitude`
 *              for orb-pulse animation.
 *   stop()   → stops the recorder, resolves the returned promise with the
 *              captured Blob (webm/opus by default on Chromium).
 *   cancel() → aborts without producing a blob.
 *
 * Amplitude is a scalar in [0, 1] updated ~30 Hz while recording. When not
 * recording it is 0 so any consumer animation settles to rest.
 *
 * Phase 11b.1: mic stream is acquired from `useMicStream` instead of a
 * direct `getUserMedia` call so the always-on worklet can co-exist.
 */

export type RecorderState = 'idle' | 'requesting' | 'recording' | 'stopping' | 'error';

interface Options {
  /** Preferred MIME type — falls back to browser default if unsupported. */
  mimeType?: string;
  /** Amplitude polling interval in ms (default 33 → ~30 fps). */
  amplitudeIntervalMs?: number;
}

const CONSUMER_ID = 'tap-to-talk';

/**
 * Phase 11c.1.1: hard cap on `getUserMedia` so a hung browser permission
 * prompt or a busy mic device cannot leave the recorder stuck in
 * 'requesting' indefinitely (which makes the sphere read "Listening" with
 * no path back to idle).
 */
export const MIC_ACQUIRE_TIMEOUT_MS = 6000;

export function useVoiceRecorder(options: Options = {}) {
  const { mimeType = 'audio/webm;codecs=opus', amplitudeIntervalMs = 33 } = options;

  const [state, setState] = useState<RecorderState>('idle');
  const [error, setError] = useState<string | null>(null);
  const [amplitude, setAmplitude] = useState(0);

  const { acquire: micAcquire, release: micRelease } = useMicStream();
  const setInputMode = useInputMode((s) => s.setMode);

  const recorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const resolveRef = useRef<((blob: Blob | null) => void) | null>(null);
  const hasStreamRef = useRef(false);

  const audioCtxRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const sourceRef = useRef<MediaStreamAudioSourceNode | null>(null);
  const rafRef = useRef<number | null>(null);
  const lastAmpTickRef = useRef(0);

  const cleanup = useCallback(() => {
    if (rafRef.current != null) {
      cancelAnimationFrame(rafRef.current);
      rafRef.current = null;
    }
    if (analyserRef.current) {
      try {
        analyserRef.current.disconnect();
      } catch {
        /* ignore */
      }
      analyserRef.current = null;
    }
    if (sourceRef.current) {
      try {
        sourceRef.current.disconnect();
      } catch {
        /* ignore */
      }
      sourceRef.current = null;
    }
    if (audioCtxRef.current) {
      try {
        void audioCtxRef.current.close();
      } catch {
        /* ignore */
      }
      audioCtxRef.current = null;
    }
    if (hasStreamRef.current) {
      micRelease(CONSUMER_ID);
      hasStreamRef.current = false;
    }
    recorderRef.current = null;
    chunksRef.current = [];
    setAmplitude(0);
    setInputMode('idle');
  }, [micRelease, setInputMode]);

  useEffect(() => () => cleanup(), [cleanup]);

  const tickAmplitude = useCallback(() => {
    const analyser = analyserRef.current;
    if (!analyser) return;
    const now = performance.now();
    if (now - lastAmpTickRef.current >= amplitudeIntervalMs) {
      lastAmpTickRef.current = now;
      const buf = new Uint8Array(analyser.fftSize);
      analyser.getByteTimeDomainData(buf);
      // RMS of signal centred on 128 → normalise to [0, 1].
      let sum = 0;
      for (const v of buf) {
        const d = (v - 128) / 128;
        sum += d * d;
      }
      const rms = Math.sqrt(sum / buf.length);
      setAmplitude(Math.min(1, rms * 3)); // amplify a bit for visual snap
    }
    rafRef.current = requestAnimationFrame(tickAmplitude);
  }, [amplitudeIntervalMs]);

  const start = useCallback(async (): Promise<void> => {
    if (state === 'recording' || state === 'requesting') return;
    setError(null);
    setState('requesting');
    // Mark this turn as owned by tap-to-talk BEFORE the mic resolves so
    // a racing wake from always-on is discarded.
    setInputMode('tap');
    let timeoutId: ReturnType<typeof setTimeout> | null = null;
    try {
      const stream = await Promise.race([
        micAcquire(CONSUMER_ID),
        new Promise<never>((_, reject) => {
          timeoutId = setTimeout(
            () => reject(new Error('mic_timeout')),
            MIC_ACQUIRE_TIMEOUT_MS,
          );
        }),
      ]);
      if (timeoutId !== null) clearTimeout(timeoutId);
      hasStreamRef.current = true;

      // Feed the mic into an AnalyserNode for amplitude pulse.
      const AudioCtx =
        window.AudioContext ||
        (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      const ctx = new AudioCtx();
      audioCtxRef.current = ctx;
      const source = ctx.createMediaStreamSource(stream);
      sourceRef.current = source;
      const analyser = ctx.createAnalyser();
      analyser.fftSize = 512;
      source.connect(analyser);
      analyserRef.current = analyser;

      const actualMime = MediaRecorder.isTypeSupported(mimeType) ? mimeType : '';
      const recorder = new MediaRecorder(stream, actualMime ? { mimeType: actualMime } : undefined);
      recorderRef.current = recorder;
      chunksRef.current = [];

      recorder.ondataavailable = (ev) => {
        if (ev.data && ev.data.size > 0) chunksRef.current.push(ev.data);
      };
      recorder.onstop = () => {
        const type = recorder.mimeType || 'audio/webm';
        const blob = new Blob(chunksRef.current, { type });
        const resolve = resolveRef.current;
        resolveRef.current = null;
        cleanup();
        setState('idle');
        resolve?.(blob.size > 0 ? blob : null);
      };
      recorder.onerror = (ev) => {
        const msg = (ev as unknown as { error?: Error }).error?.message ?? 'recorder error';
        setError(msg);
        setState('error');
        const resolve = resolveRef.current;
        resolveRef.current = null;
        cleanup();
        resolve?.(null);
      };
      recorder.start(250); // emit a chunk every 250 ms so onstop sees data quickly
      setState('recording');
      lastAmpTickRef.current = 0;
      rafRef.current = requestAnimationFrame(tickAmplitude);
    } catch (err) {
      if (timeoutId !== null) clearTimeout(timeoutId);
      const isTimeout = err instanceof Error && err.message === 'mic_timeout';
      const msg = isTimeout
        ? 'Mic permission timed out — check browser settings'
        : err instanceof Error
          ? err.message
          : 'Recorder failed to start';
      setError(msg);
      setState('error');
      // The shared mic stream may have registered our consumer before the
      // timeout fired. Drop the reference so a future acquire isn't blocked
      // by a stale refcount.
      try {
        micRelease(CONSUMER_ID);
      } catch {
        /* ignore */
      }
      cleanup();
      throw err;
    }
  }, [state, mimeType, cleanup, tickAmplitude, micAcquire, micRelease, setInputMode]);

  /** Stop and resolve with the captured Blob (or null on empty capture). */
  const stop = useCallback((): Promise<Blob | null> => {
    if (state !== 'recording' && state !== 'requesting') {
      return Promise.resolve(null);
    }
    setState('stopping');
    return new Promise<Blob | null>((resolve) => {
      resolveRef.current = resolve;
      const rec = recorderRef.current;
      if (rec && rec.state !== 'inactive') {
        rec.stop();
      } else {
        // Already stopped or never started — resolve immediately.
        resolveRef.current = null;
        cleanup();
        setState('idle');
        resolve(null);
      }
    });
  }, [state, cleanup]);

  /** Abort the current recording without producing a blob. */
  const cancel = useCallback(() => {
    if (state === 'idle') return;
    const rec = recorderRef.current;
    resolveRef.current?.(null);
    resolveRef.current = null;
    if (rec && rec.state !== 'inactive') {
      try {
        rec.stop();
      } catch {
        /* ignore */
      }
    }
    cleanup();
    setState('idle');
  }, [state, cleanup]);

  return { state, error, amplitude, start, stop, cancel };
}

/* ──────────────────────────────────────────────────────────────────────────
 * Голосове в месенджері (W0-6, ГЛ-01..04, 05_ux_design §2.6, мікро №12–14).
 *
 * Дві частини, навмисно розведені:
 *  1. `voiceGesture` — чиста машина станів жесту: утримання, замок, свайп-
 *     скасування, перегляд. Жодного DOM і таймера — лише події з часом і
 *     координатами, тож пороги перевіряються тестом без пальця.
 *  2. `useVoiceNote` — запис через MediaRecorder з паузою, живою хвилею й
 *     таймером, що не рахує паузи. Окремо від `useVoiceRecorder` (той —
 *     «натисни й говори» асистента на спільному мікрофоні).
 * ────────────────────────────────────────────────────────────────────────── */

/** Утримання, після якого запис почався. Коротше — підказка, а не запис (ГЛ-04). */
export const VOICE_HOLD_MS = 200;
/** Вгору на стільки CSS-пікселів — замок (ГЛ-01). */
export const VOICE_LOCK_PX = 64;
/** Вліво на таку частку ширини — скасування (ГЛ-02). */
export const VOICE_CANCEL_FRACTION = 0.4;
/** Скільки висить підказка «Тримай, щоб записати». */
export const VOICE_HINT_MS = 1500;
/** Запис, коротший за це, не надсилаємо: це був дотик, а не слово. */
export const VOICE_MIN_MS = 500;

/**
 * Рівень, нижче якого огинаюча — цифрова тиша: так пише мікрофон, який
 * система віддала іншому застосунку (дзвінок, демонстрація екрана).
 */
export const VOICE_SILENCE_LEVEL = 0.01;

/** Без огинаючої (нема аналізатора) — не знаємо, тож не тиша. */
export const voiceIsSilent = (envelope: readonly number[]): boolean =>
  envelope.length > 0 && envelope.every((v) => v < VOICE_SILENCE_LEVEL);

/**
 * Чи мікрофон уже дозволено. Лише тоді дотик відкриває його одразу (warm):
 * на свіжому встановленні тап не має викликати системний діалог дозволу.
 */
let micGranted = false;
export const micAlreadyGranted = (): boolean => micGranted;

/** Спитати систему про дозвіл, не відкриваючи мікрофон. Немає Permissions API — лишаємо, що знали. */
export const probeMicGrant = async (): Promise<void> => {
  const perms = typeof navigator !== 'undefined' ? navigator.permissions : undefined;
  if (!perms?.query) return;
  try {
    const status = await perms.query({ name: 'microphone' as PermissionName });
    micGranted = status.state === 'granted';
  } catch {
    /* WebView без запиту про мікрофон: дізнаємось із першого запису */
  }
};

export type VoicePhase = 'idle' | 'pressing' | 'holding' | 'locked' | 'paused' | 'preview';

export interface VoiceGestureState {
  phase: VoicePhase;
  /** Де палець торкнувся, мс і ширина, від якої рахується 40 %. */
  startX: number;
  startY: number;
  downAt: number;
  width: number;
  /** Коли почався запис (мс) — для межі VOICE_MIN_MS. */
  recordAt: number;
  /** Поточний зсув пальця: dx > 0 — уліво, dy > 0 — угору. */
  dx: number;
  dy: number;
  /**
   * До якого моменту видно підказку «Тримай». Дотик, поки вона видна, —
   * запис без утримання (одразу в замку): доступна дорога для тих, кому
   * тримати палець важко.
   */
  hintUntil: number;
}

export type VoiceGestureEvent =
  | { type: 'down'; x: number; y: number; t: number; width: number }
  | { type: 'hold'; t: number }
  | { type: 'move'; x: number; y: number }
  | { type: 'up'; t: number }
  /** Система забрала палець (pointercancel, дозвіл мікрофона, дзвінок). */
  | { type: 'interrupt'; background?: boolean }
  /** Чернетку з IndexedDB повернуто в перегляд. */
  | { type: 'restore' }
  /** Клавіатура, зчитувач екрана або миша: запис без утримання, одразу в замку. */
  | { type: 'activate' }
  | { type: 'pause' }
  | { type: 'resume' }
  /** «Стоп» у замку → перегляд перед надсиланням (ГЛ-03). */
  | { type: 'stop' }
  | { type: 'send' }
  | { type: 'delete' }
  /** Мікрофон не дався — назад у спокій без жодного «запису». */
  | { type: 'fail' };

export type VoiceEffect =
  /** Палець торкнувся: мікрофон відкрито й пише в буфер, поки не ясно — тап чи утримання. */
  | 'warm'
  | 'start'
  | 'pause'
  | 'resume'
  | 'stop'
  | 'send'
  | 'discard'
  | 'keep'
  | 'hint'
  | 'haptic:threshold'
  | 'haptic:reject'
  | 'haptic:confirm';

export const VOICE_IDLE: VoiceGestureState = {
  phase: 'idle',
  startX: 0,
  startY: 0,
  downAt: 0,
  width: 0,
  recordAt: 0,
  dx: 0,
  dy: 0,
  hintUntil: 0,
};

/** Наскільки близько палець до замка й до скасування, 0…1 — для руху капсули й «‹ Відміна». */
export const voiceProgress = (s: VoiceGestureState): { lock: number; cancel: number } => {
  if (s.phase !== 'holding') return { lock: 0, cancel: 0 };
  const cancelAt = Math.max(1, s.width * VOICE_CANCEL_FRACTION);
  return {
    lock: Math.min(1, Math.max(0, s.dy / VOICE_LOCK_PX)),
    cancel: Math.min(1, Math.max(0, s.dx / cancelAt)),
  };
};

/**
 * Один крок жесту. Повертає новий стан і що зробити (запис, гаптика, підказка).
 * Події, що в цьому стані нічого не значать, стан не міняють.
 */
export function voiceGesture(
  s: VoiceGestureState,
  e: VoiceGestureEvent,
): { state: VoiceGestureState; effects: VoiceEffect[] } {
  const same = { state: s, effects: [] as VoiceEffect[] };
  const idle = (effects: VoiceEffect[], hintUntil = 0) => ({ state: { ...VOICE_IDLE, hintUntil }, effects });
  switch (e.type) {
    case 'down':
      if (s.phase !== 'idle') return same;
      return {
        state: { ...VOICE_IDLE, phase: 'pressing', startX: e.x, startY: e.y, downAt: e.t, width: e.width, hintUntil: s.hintUntil },
        effects: ['warm'],
      };
    case 'hold':
      if (s.phase !== 'pressing' || e.t - s.downAt < VOICE_HOLD_MS) return same;
      return { state: { ...s, phase: 'holding', recordAt: e.t }, effects: ['start', 'haptic:threshold'] };
    case 'move': {
      if (s.phase !== 'holding') return same;
      const dx = Math.max(0, s.startX - e.x);
      const dy = Math.max(0, s.startY - e.y);
      // Перемагає вісь, якою палець іде більше: діагональ не замикає і не скасовує випадково.
      if (dy >= VOICE_LOCK_PX && dy >= dx) {
        return { state: { ...s, phase: 'locked', dx: 0, dy: 0 }, effects: ['haptic:threshold'] };
      }
      if (dx >= s.width * VOICE_CANCEL_FRACTION && dx > dy) {
        return idle(['discard', 'haptic:reject']);
      }
      return { state: { ...s, dx, dy }, effects: [] };
    }
    case 'up':
      if (s.phase === 'pressing') {
        // Другий дотик, поки підказка ще видна, — людина просить запис без утримання.
        if (e.t <= s.hintUntil) {
          return { state: { ...VOICE_IDLE, phase: 'locked' }, effects: ['start', 'haptic:threshold'] };
        }
        // Тап: буфер від дотику знищено, не почутий і не збережений.
        return idle(['discard', 'hint'], e.t + VOICE_HINT_MS);
      }
      if (s.phase === 'holding') {
        if (e.t - s.recordAt < VOICE_MIN_MS) return idle(['discard', 'hint'], e.t + VOICE_HINT_MS);
        return idle(['send', 'haptic:confirm']);
      }
      return same;
    case 'interrupt':
      // Застосунок пішов у фон або прийшов дзвінок: довгий запис ставимо на паузу й зберігаємо.
      if (e.background && s.phase === 'locked') return { state: { ...s, phase: 'paused' }, effects: ['pause', 'keep'] };
      if (e.background && s.phase === 'paused') return { state: s, effects: ['keep'] };
      if (s.phase === 'pressing') return idle(['discard']);
      // Палець забрала система — запис НЕ губимо й НЕ шлемо навмання: лишаємо в замку.
      if (s.phase === 'holding') return { state: { ...s, phase: 'locked', dx: 0, dy: 0 }, effects: [] };
      return same;
    case 'restore':
      return s.phase === 'idle' ? { state: { ...VOICE_IDLE, phase: 'preview' }, effects: [] } : same;
    case 'activate':
      if (s.phase !== 'idle') return same;
      return { state: { ...VOICE_IDLE, phase: 'locked' }, effects: ['start', 'haptic:threshold'] };
    case 'pause':
      return s.phase === 'locked' ? { state: { ...s, phase: 'paused' }, effects: ['pause'] } : same;
    case 'resume':
      return s.phase === 'paused' ? { state: { ...s, phase: 'locked' }, effects: ['resume'] } : same;
    case 'stop':
      return s.phase === 'locked' || s.phase === 'paused'
        ? { state: { ...s, phase: 'preview' }, effects: ['stop'] }
        : same;
    case 'send':
      return s.phase === 'locked' || s.phase === 'paused' || s.phase === 'preview'
        ? idle(['send', 'haptic:confirm'])
        : same;
    case 'delete':
      return s.phase === 'locked' || s.phase === 'paused' || s.phase === 'preview'
        ? idle(['discard', 'haptic:reject'])
        : same;
    case 'fail':
      return s.phase === 'idle' ? same : idle([]);
    default:
      return same;
  }
}

/**
 * Гаптика голосового через рідний міст (WebMessengerBridge.haptic), інакше —
 * `navigator.vibrate`. Слова мосту: tap / send / warning.
 */
export function voiceHaptic(kind: 'threshold' | 'reject' | 'confirm'): void {
  if (typeof window === 'undefined') return;
  const bridgeWord = kind === 'reject' ? 'warning' : kind === 'confirm' ? 'send' : 'tap';
  try {
    const bridge = (window as unknown as { PhantomBridge?: { haptic?: (t: string) => void } }).PhantomBridge;
    if (bridge && typeof bridge.haptic === 'function') {
      bridge.haptic(bridgeWord);
      return;
    }
    if (typeof navigator !== 'undefined' && typeof navigator.vibrate === 'function') {
      navigator.vibrate(kind === 'reject' ? [20, 40, 20] : kind === 'confirm' ? 25 : 12);
    }
  } catch {
    /* без вібромотора жест працює так само */
  }
}

/** Стиснути рівні до `bars` стовпчиків за піком. Порожньо — значить порожньо. */
export function compressEnvelope(raw: readonly number[], bars: number): number[] {
  if (raw.length === 0 || bars <= 0) return [];
  const out: number[] = [];
  for (let i = 0; i < bars; i += 1) {
    const from = Math.floor((i * raw.length) / bars);
    const to = Math.max(from + 1, Math.floor(((i + 1) * raw.length) / bars));
    let peak = 0;
    for (let j = from; j < to && j < raw.length; j += 1) peak = Math.max(peak, raw[j]);
    out.push(peak);
  }
  return out;
}

export interface VoiceTake {
  blob: Blob | null;
  durationMs: number;
  /** Повна огинаюча (рівні 0…1 кожні 50 мс) — для хвилі перегляду й листа. */
  envelope: number[];
}

/** Чим скінчився старт: записує / мікрофона немає чи не дали / людина вже передумала. */
export type VoiceStartResult = 'ok' | 'denied' | 'cancelled';

export type VoiceNoteStatus = 'idle' | 'starting' | 'recording' | 'paused';

/** Скільки останніх рівнів показує жива хвиля під час запису. */
export const VOICE_LIVE_BARS = 40;

/**
 * Запис голосового листа: MediaRecorder + замір рівня 20 Гц.
 *
 * `start()` повертає 'denied', якщо мікрофон не дався, — тоді жодного «запису»
 * на екрані не буває (раніше без дозволу йшов таймер і летів порожній лист).
 * Таймер рахує лише записаний час: пауза його зупиняє.
 */
export function useVoiceNote() {
  const [status, setStatus] = useState<VoiceNoteStatus>('idle');
  const [mode, setMode] = useState<VoiceNoteMode>('voice');
  const [elapsedMs, setElapsedMs] = useState(0);
  const [live, setLive] = useState<number[]>([]);
  const [canPause, setCanPause] = useState(false);

  const streamRef = useRef<MediaStream | null>(null);
  const recRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const ctxRef = useRef<AudioContext | null>(null);
  const probeRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const uiRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const envRef = useRef<number[]>([]);
  const accRef = useRef(0);
  const runFromRef = useRef<number | null>(null);
  const pausedRef = useRef(false);
  /** Кожен старт має свій номер: скасований до відповіді мікрофона старт сам прибирає за собою. */
  const genRef = useRef(0);
  const startingRef = useRef<Promise<VoiceStartResult> | null>(null);

  const now = () => (typeof performance !== 'undefined' ? performance.now() : Date.now());
  const elapsed = () => accRef.current + (runFromRef.current !== null ? now() - runFromRef.current : 0);

  const teardown = useCallback(() => {
    if (probeRef.current) clearInterval(probeRef.current);
    if (uiRef.current) clearInterval(uiRef.current);
    probeRef.current = null;
    uiRef.current = null;
    const ctx = ctxRef.current;
    ctxRef.current = null;
    if (ctx) {
      try {
        void ctx.close();
      } catch {
        /* ignore */
      }
    }
    streamRef.current?.getTracks().forEach((t) => t.stop());
    streamRef.current = null;
    recRef.current = null;
    runFromRef.current = null;
    pausedRef.current = false;
    setStatus('idle');
    setMode('voice');
    setElapsedMs(0);
    setLive([]);
  }, []);

  useEffect(() => () => {
    genRef.current += 1;
    const rec = recRef.current;
    if (rec && rec.state !== 'inactive') {
      try {
        rec.stop();
      } catch {
        /* ignore */
      }
    }
    teardown();
  }, [teardown]);

  const start = useCallback((nextMode: VoiceNoteMode = 'voice'): Promise<VoiceStartResult> => {
    const gen = ++genRef.current;
    const profile = voiceNoteProfile(nextMode);
    chunksRef.current = [];
    envRef.current = [];
    accRef.current = 0;
    runFromRef.current = null;
    setMode(nextMode);
    setElapsedMs(0);
    setLive([]);
    setStatus('starting');
    const run = (async () => {
      if (typeof navigator === 'undefined' || !navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
        if (gen === genRef.current) teardown();
        return 'denied' as const;
      }
      let stream: MediaStream;
      try {
        stream = await navigator.mediaDevices.getUserMedia({
          audio: profile.audio,
        });
        micGranted = true;
      } catch {
        micGranted = false;
        if (gen === genRef.current) teardown();
        return 'denied' as const;
      }
      if (gen !== genRef.current) {
        // Людина вже відпустила / скасувала, поки система питала дозвіл.
        stream.getTracks().forEach((t) => t.stop());
        return 'cancelled' as const;
      }
      streamRef.current = stream;
      const mimeType = pickVoiceMime((m) => MediaRecorder.isTypeSupported(m));
      let rec: MediaRecorder;
      try {
        rec = mimeType
          ? new MediaRecorder(stream, { mimeType, audioBitsPerSecond: profile.bitsPerSecond })
          : new MediaRecorder(stream);
      } catch {
        teardown();
        return 'denied' as const;
      }
      recRef.current = rec;
      rec.ondataavailable = (ev) => {
        if (ev.data && ev.data.size > 0) chunksRef.current.push(ev.data);
      };
      setCanPause(typeof rec.pause === 'function' && typeof rec.resume === 'function');

      const AudioCtx =
        typeof window !== 'undefined'
          ? window.AudioContext || (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
          : undefined;
      if (AudioCtx) {
        try {
          const ctx = new AudioCtx();
          ctxRef.current = ctx;
          const analyser = ctx.createAnalyser();
          analyser.fftSize = 512;
          ctx.createMediaStreamSource(stream).connect(analyser);
          const buf = new Uint8Array(analyser.fftSize);
          probeRef.current = setInterval(() => {
            if (pausedRef.current) return;
            analyser.getByteTimeDomainData(buf);
            let sum = 0;
            for (const v of buf) {
              const d = (v - 128) / 128;
              sum += d * d;
            }
            envRef.current.push(Math.min(1, Math.sqrt(sum / buf.length) * 3));
          }, 50);
        } catch {
          /* без аналізатора запис іде, хвиля — порожня (чесно) */
        }
      }
      // Шматок щосекунди: зупинка завжди має що віддати.
      rec.start(1000);
      runFromRef.current = now();
      uiRef.current = setInterval(() => {
        setElapsedMs(elapsed());
        const env = envRef.current;
        setLive(env.slice(Math.max(0, env.length - VOICE_LIVE_BARS)));
      }, 100);
      setStatus('recording');
      return 'ok' as const;
    })();
    startingRef.current = run;
    return run;
  }, [teardown]);

  const pause = useCallback(() => {
    const rec = recRef.current;
    if (!rec || rec.state !== 'recording' || typeof rec.pause !== 'function') return;
    try {
      rec.pause();
    } catch {
      return;
    }
    accRef.current = elapsed();
    runFromRef.current = null;
    pausedRef.current = true;
    setElapsedMs(accRef.current);
    setStatus('paused');
  }, []);

  const resume = useCallback(() => {
    const rec = recRef.current;
    if (!rec || rec.state !== 'paused' || typeof rec.resume !== 'function') return;
    try {
      rec.resume();
    } catch {
      return;
    }
    runFromRef.current = now();
    pausedRef.current = false;
    setStatus('recording');
  }, []);

  /** Зупинити й віддати запис. Якщо мікрофон ще не відповів — дочекатися його. */
  const finish = useCallback(async (): Promise<VoiceTake> => {
    if (startingRef.current) await startingRef.current;
    const rec = recRef.current;
    const durationMs = Math.round(elapsed());
    const envelope = envRef.current.slice();
    if (!rec) {
      teardown();
      return { blob: null, durationMs: 0, envelope: [] };
    }
    const blob = await new Promise<Blob | null>((resolve) => {
      if (rec.state === 'inactive') {
        resolve(chunksRef.current.length ? new Blob(chunksRef.current, { type: rec.mimeType || 'audio/webm' }) : null);
        return;
      }
      rec.onstop = () => {
        resolve(chunksRef.current.length ? new Blob(chunksRef.current, { type: rec.mimeType || 'audio/webm' }) : null);
      };
      try {
        rec.stop();
      } catch {
        resolve(null);
      }
    });
    genRef.current += 1;
    startingRef.current = null;
    chunksRef.current = [];
    teardown();
    return { blob, durationMs, envelope };
  }, [teardown]);

  /** Знімок поточного запису для чернетки; запис не зупиняє. */
  const snapshot = useCallback(async (): Promise<Omit<VoiceDraft, 'savedAt'> | null> => {
    const cur = recRef.current;
    if (!cur) return null;
    const rec: MediaRecorder = cur;
    const mime = rec.mimeType || 'audio/webm';
    if (rec.state !== 'inactive') {
      await new Promise<void>((resolve) => {
        const prev = rec.ondataavailable;
        let timer: ReturnType<typeof setTimeout> | undefined;
        const done = () => {
          clearTimeout(timer);
          rec.ondataavailable = prev;
          resolve();
        };
        timer = setTimeout(done, 250);
        rec.ondataavailable = (ev) => {
          prev?.call(rec, ev);
          done();
        };
        try {
          rec.requestData();
        } catch {
          done();
        }
      });
    }
    if (!chunksRef.current.length) return null;
    return {
      chunks: chunksRef.current.slice(),
      mime,
      durationMs: Math.round(elapsed()),
      envelope: envRef.current.slice(),
    };
  }, []);

  /** Знищити запис: ні байта не лишається й нічого не йде. */
  const discard = useCallback(() => {
    genRef.current += 1;
    startingRef.current = null;
    const rec = recRef.current;
    if (rec) {
      rec.ondataavailable = null;
      rec.onstop = null;
      if (rec.state !== 'inactive') {
        try {
          rec.stop();
        } catch {
          /* ignore */
        }
      }
    }
    chunksRef.current = [];
    envRef.current = [];
    accRef.current = 0;
    teardown();
  }, [teardown]);

  return { status, mode, elapsedMs, live, canPause, start, pause, resume, finish, discard, snapshot };
}
