import { create } from 'zustand';
import {
  WALLPAPERS,
  type BuiltinWallpaper,
  type Density,
  type Look,
  type WallpaperChoice,
} from './appearance';
import { primaryOf } from '../stores/circlesStore';
import type { Chat } from '../types/messenger';

export const RING_PRESETS_KEY = 'np.ringPresets.v1';

export interface RingPreset {
  wallpaper?: WallpaperChoice;
  bubbles?: Density;
}

export type RingPresets = Record<string, RingPreset>;

const isObj = (v: unknown): v is Record<string, unknown> =>
  Boolean(v) && typeof v === 'object' && !Array.isArray(v);

const parseWallpaper = (v: unknown): WallpaperChoice | undefined => {
  if (!isObj(v)) return undefined;
  if (v.kind === 'none') return { kind: 'none' };
  if (v.kind === 'builtin' && (WALLPAPERS as readonly unknown[]).includes(v.id)) {
    return { kind: 'builtin', id: v.id as BuiltinWallpaper };
  }
  return undefined;
};

const parseBubbles = (v: unknown): Density | undefined =>
  v === 'comfortable' || v === 'compact' ? v : undefined;

export const readRingPresets = (): RingPresets => {
  try {
    if (typeof localStorage === 'undefined') return {};
    const raw = localStorage.getItem(RING_PRESETS_KEY);
    if (!raw) return {};
    const parsed: unknown = JSON.parse(raw);
    if (!isObj(parsed)) return {};
    const out: RingPresets = {};
    for (const [ringId, entry] of Object.entries(parsed)) {
      if (!ringId || ringId === 'all' || !isObj(entry)) continue;
      const wallpaper = parseWallpaper(entry.wallpaper);
      const bubbles = parseBubbles(entry.bubbles);
      const preset: RingPreset = {};
      if (wallpaper) preset.wallpaper = wallpaper;
      if (bubbles) preset.bubbles = bubbles;
      if (preset.wallpaper !== undefined || preset.bubbles !== undefined) {
        out[ringId] = preset;
      }
    }
    return out;
  } catch {
    return {};
  }
};

const writeRingPresets = (presets: RingPresets): void => {
  try {
    if (typeof localStorage === 'undefined') return;
    localStorage.setItem(RING_PRESETS_KEY, JSON.stringify(presets));
  } catch {
    /* без сховища вибір живе в памʼяті */
  }
};

export const sameWallpaper = (a?: WallpaperChoice, b?: WallpaperChoice): boolean => {
  if (!a || !b || a.kind !== b.kind) return false;
  if (a.kind === 'none') return true;
  if (a.kind === 'builtin' && b.kind === 'builtin') return a.id === b.id;
  if (a.kind === 'photo' && b.kind === 'photo') return a.url === b.url;
  return false;
};

export const resolveLook = (
  chat: (Pick<Chat, 'id'> & Partial<Pick<Chat, 'circle' | 'circles'>>) | undefined,
  look: Look,
  presets: RingPresets = {},
): { wallpaper: WallpaperChoice; bubbles: Density; ring: string | null } => {
  const rawRing = chat ? primaryOf(chat.circles ?? (chat.circle ? [chat.circle] : [])) : 'all';
  const ring = rawRing && rawRing !== 'all' ? rawRing : null;
  const ringPreset = ring ? presets[ring] : undefined;
  const chatOverride = chat ? look.chats[chat.id] : undefined;
  return {
    wallpaper: chatOverride ?? ringPreset?.wallpaper ?? look.wallpaper,
    bubbles: ringPreset?.bubbles ?? look.bubbles,
    ring,
  };
};

interface RingPresetsState {
  presets: RingPresets;
  setRingWallpaper: (ringId: string, choice: WallpaperChoice | null) => void;
  setRingBubbles: (ringId: string, density: Density | null) => void;
}

export const useRingPresets = create<RingPresetsState>((set, get) => ({
  presets: readRingPresets(),
  setRingWallpaper: (ringId, choice) => {
    // Фото кола не тримаємо: data-URL не влазить у localStorage.
    if (!ringId || ringId === 'all' || choice?.kind === 'photo') return;
    const next = { ...get().presets };
    const cur: RingPreset = { ...next[ringId] };
    if (choice === null) delete cur.wallpaper;
    else cur.wallpaper = choice;
    if (cur.wallpaper === undefined && cur.bubbles === undefined) delete next[ringId];
    else next[ringId] = cur;
    writeRingPresets(next);
    set({ presets: next });
  },
  setRingBubbles: (ringId, density) => {
    if (!ringId || ringId === 'all') return;
    const next = { ...get().presets };
    const cur: RingPreset = { ...next[ringId] };
    if (density === null) delete cur.bubbles;
    else cur.bubbles = density;
    if (cur.wallpaper === undefined && cur.bubbles === undefined) delete next[ringId];
    else next[ringId] = cur;
    writeRingPresets(next);
    set({ presets: next });
  },
}));
