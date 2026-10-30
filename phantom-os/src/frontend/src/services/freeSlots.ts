import type { DayItem } from './dayApi';
import { findAsk, findTime } from './understand';

export const QUIET_FROM_H = 23;
export const QUIET_TO_H = 7;
export const SLOT_MIN_MIN = 30;
export const SLOT_STEP_MIN = 15;

export interface FreeSlot {
  startMs: number;
  endMs: number;
}

export interface FreeSlotRing {
  padMin: number;
  aheadDays: number;
}

export interface PeerMessageRef {
  id: string;
  text: string;
  sentAtIso?: string | null;
}

export const DEFAULT_RING: FreeSlotRing = { padMin: 30, aheadDays: 2 };

export const FREE_SLOTS_RING_KEY = 'np.freeSlots.rings.v1';

const MIN_MS = SLOT_MIN_MIN * 60_000;

const TIME_CUE =
  /(?<![\p{L}\p{N}])(коли|о\s+котрій|вільн\p{L}*|встигн\p{L}*|сьогодні|завтра|післязавтра|ввечері|увечері|вранці|зранку|вдень|час|годин\p{L}*|when|free|available|today|tomorrow|tonight|time|kdy|voln\p{L}*|čas|dnes|zítra|večer)(?![\p{L}\p{N}])/iu;



function clampInt(val: unknown, min: number, max: number, fallback: number): number {
  const n = typeof val === 'number' && Number.isFinite(val) ? Math.round(val) : fallback;
  return Math.min(max, Math.max(min, n));
}

function normalizeRing(raw: unknown): FreeSlotRing | null {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  const r = raw as Partial<FreeSlotRing>;
  if (typeof r.padMin !== 'number' && typeof r.aheadDays !== 'number') return null;
  return {
    padMin: clampInt(r.padMin, 0, 120, DEFAULT_RING.padMin),
    aheadDays: clampInt(r.aheadDays, 1, 7, DEFAULT_RING.aheadDays),
  };
}

export function loadRing(chatId?: string): FreeSlotRing {
  try {
    if (typeof localStorage === 'undefined') return { ...DEFAULT_RING };
    const raw = localStorage.getItem(FREE_SLOTS_RING_KEY);
    if (!raw) return { ...DEFAULT_RING };
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return { ...DEFAULT_RING };
    }
    const map = parsed as Record<string, unknown>;
    if (chatId && chatId in map) {
      const hit = normalizeRing(map[chatId]);
      if (hit) return hit;
    }
    if ('*' in map) {
      const fallback = normalizeRing(map['*']);
      if (fallback) return fallback;
    }
    return { ...DEFAULT_RING };
  } catch {
    return { ...DEFAULT_RING };
  }
}

export function saveRing(ring: FreeSlotRing, chatId = '*'): void {
  const clean: FreeSlotRing = {
    padMin: clampInt(ring.padMin, 0, 120, DEFAULT_RING.padMin),
    aheadDays: clampInt(ring.aheadDays, 1, 7, DEFAULT_RING.aheadDays),
  };
  try {
    if (typeof localStorage === 'undefined') return;
    let map: Record<string, FreeSlotRing> = {};
    try {
      const raw = localStorage.getItem(FREE_SLOTS_RING_KEY);
      if (raw) {
        const parsed: unknown = JSON.parse(raw);
        if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
          map = parsed as Record<string, FreeSlotRing>;
        }
      }
    } catch {
      map = {};
    }
    map[chatId || '*'] = clean;
    localStorage.setItem(FREE_SLOTS_RING_KEY, JSON.stringify(map));
  } catch {
    /* ignore */
  }
}

function nextLocalStepMs(ms: number): number {
  const d = new Date(ms);
  const min = d.getMinutes();
  const sec = d.getSeconds();
  const millis = d.getMilliseconds();
  const rem = min % SLOT_STEP_MIN;
  if (rem === 0 && sec === 0 && millis === 0) {
    return ms;
  }
  return new Date(
    d.getFullYear(),
    d.getMonth(),
    d.getDate(),
    d.getHours(),
    min + (SLOT_STEP_MIN - rem),
    0,
    0,
  ).getTime();
}

function floorLocalStepMs(ms: number): number {
  const d = new Date(ms);
  const min = d.getMinutes();
  const sec = d.getSeconds();
  const millis = d.getMilliseconds();
  const rem = min % SLOT_STEP_MIN;
  if (rem === 0 && sec === 0 && millis === 0) {
    return ms;
  }
  return new Date(
    d.getFullYear(),
    d.getMonth(),
    d.getDate(),
    d.getHours(),
    min - rem,
    0,
    0,
  ).getTime();
}

export function formatHm(ms: number): string {
  const d = new Date(ms);
  const hh = d.getHours().toString().padStart(2, '0');
  const mm = d.getMinutes().toString().padStart(2, '0');
  return `${hh}:${mm}`;
}

export function formatSlotRange(slot: FreeSlot): string {
  return `${formatHm(slot.startMs)}–${formatHm(slot.endMs)}`;
}

/** Пошук вільних вікон між реальними подіями з буфером та без нічних годин. */
export function freeSlots(
  items: readonly DayItem[],
  opts: { nowMs: number; ring: FreeSlotRing; max?: number },
): FreeSlot[] {
  const max = opts.max ?? 3;
  if (max <= 0 || !Number.isFinite(opts.nowMs)) return [];

  const padMin = clampInt(opts.ring.padMin, 0, 120, DEFAULT_RING.padMin);
  const aheadDays = clampInt(opts.ring.aheadDays, 1, 7, DEFAULT_RING.aheadDays);
  const padMs = padMin * 60_000;

  const padded: FreeSlot[] = [];
  for (const item of items) {
    if (
      item.kind === 'event' &&
      !item.allDay &&
      typeof item.atMs === 'number' &&
      typeof item.endMs === 'number' &&
      item.endMs > item.atMs
    ) {
      padded.push({
        startMs: item.atMs - padMs,
        endMs: item.endMs + padMs,
      });
    }
  }
  if (padded.length === 0) return [];

  padded.sort((a, b) => a.startMs - b.startMs || a.endMs - b.endMs);
  const busy: FreeSlot[] = [];
  for (const span of padded) {
    const last = busy[busy.length - 1];
    if (last && span.startMs <= last.endMs) {
      if (span.endMs > last.endMs) last.endMs = span.endMs;
    } else {
      busy.push({ startMs: span.startMs, endMs: span.endMs });
    }
  }

  const horizonStartMs = nextLocalStepMs(opts.nowMs);
  const nowDate = new Date(opts.nowMs);
  const y = nowDate.getFullYear();
  const m = nowDate.getMonth();
  const d = nowDate.getDate();
  const restOfTodayEndMs = new Date(y, m, d, QUIET_FROM_H, 0, 0, 0).getTime();
  const horizonEndMs = Math.max(
    restOfTodayEndMs,
    new Date(y, m, d + aheadDays, 0, 0, 0, 0).getTime(),
  );

  const slots: FreeSlot[] = [];
  for (let dayOffset = 0; dayOffset < aheadDays; dayOffset++) {
    const wakeStart = new Date(y, m, d + dayOffset, QUIET_TO_H, 0, 0, 0).getTime();
    const wakeEnd = Math.min(
      new Date(y, m, d + dayOffset, QUIET_FROM_H, 0, 0, 0).getTime(),
      horizonEndMs,
    );
    let cursor = Math.max(wakeStart, horizonStartMs);
    if (cursor >= wakeEnd) continue;

    for (const b of busy) {
      if (b.endMs <= cursor) continue;
      if (b.startMs >= wakeEnd) break;
      if (b.startMs > cursor) {
        const segStart = nextLocalStepMs(cursor);
        const segEnd = floorLocalStepMs(Math.min(wakeEnd, b.startMs));
        if (segEnd - segStart >= MIN_MS) {
          slots.push({ startMs: segStart, endMs: segEnd });
          if (slots.length >= max) return slots;
        }
      }
      cursor = Math.max(cursor, b.endMs);
      if (cursor >= wakeEnd) break;
    }

    if (cursor < wakeEnd) {
      const segStart = nextLocalStepMs(cursor);
      const segEnd = floorLocalStepMs(wakeEnd);
      if (segEnd - segStart >= MIN_MS) {
        slots.push({ startMs: segStart, endMs: segEnd });
        if (slots.length >= max) return slots;
      }
    }
  }

  return slots;
}

export function wantsTime(text: string, sentAt: Date): boolean {
  if (!text) return false;
  if (findAsk(text) === null && !/\p{L}[^?]*\?/u.test(text)) return false;
  return findTime(text, sentAt) !== null || TIME_CUE.test(text);
}

export function slotDay(startMs: number, nowMs: number): 'today' | 'tomorrow' | 'later' {
  const s = new Date(startMs);
  const n = new Date(nowMs);
  const sMidnight = new Date(s.getFullYear(), s.getMonth(), s.getDate()).getTime();
  const todayMidnight = new Date(n.getFullYear(), n.getMonth(), n.getDate()).getTime();
  if (sMidnight === todayMidnight) return 'today';
  const tomorrowMidnight = new Date(n.getFullYear(), n.getMonth(), n.getDate() + 1).getTime();
  if (sMidnight === tomorrowMidnight) return 'tomorrow';
  return 'later';
}
