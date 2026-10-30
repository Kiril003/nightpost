/**
 * Профіль › Вигляд у вебі: акцент, тло розмов (загальне й своє для розмови),
 * приглушення й розмиття окремо для Дня й Ночі, щільність бульбашок і списку.
 *
 * На телефоні вибір живе на рідному боці, окремо для кожної своєї особи
 * (`PhantomBridge.getAppearance`, `/messenger/appearance`); зміну приносить
 * `window.__phantomAppearance`. Без мосту (ПК) — localStorage під id особи.
 * Акцент і щільність — атрибути `<html>`, далі працюють лише токени
 * (`[data-accent]` у tokens.css, `[data-bubbles]`/`[data-list-density]` у identity.css).
 */
import { create } from 'zustand';
import { request } from './api';
import { useMessengerStore } from '../stores/messengerStore';
import { useProfiles, type Profile } from '../stores/profilesStore';
import { browserDeps, canPrepareImages, fitWithin, type ImagePrepDeps } from './imagePrep';

/** Порядок — як у docs/design/system/tokens.json `accents.order` (сторож — appearance.test.ts). */
export const ACCENTS = ['amber', 'sage', 'sea', 'iris', 'rose', 'graphite'] as const;
export const WALLPAPERS = ['contour', 'sand', 'sage', 'slate', 'clay', 'dusk', 'stone'] as const;
export type Accent = (typeof ACCENTS)[number];
export type BuiltinWallpaper = (typeof WALLPAPERS)[number];
export type Density = 'comfortable' | 'compact';

export type WallpaperChoice =
  | { kind: 'none' }
  | { kind: 'builtin'; id: BuiltinWallpaper }
  | { kind: 'photo'; url: string };

export interface Look {
  accent: Accent;
  wallpaper: WallpaperChoice;
  wallpaperPicked: boolean;
  chats: Record<string, WallpaperChoice>;
  dim: { day: number; night: number };
  blur: { day: number; night: number };
  bubbles: Density;
  list: Density;
}

export const DIM_MAX = 80;
export const BLUR_MAX = 20;
/** Вбудований візерунок — лише фон: завіса з'їдає ще половину того, що лишило приглушення. */
export const PATTERN_QUIET = 0.5;
export const scrimFor = (dim: number, kind: WallpaperChoice['kind']): number => {
  const d = dim / 100;
  return kind === 'builtin' ? Math.round((d + (1 - d) * PATTERN_QUIET) * 1000) / 1000 : d;
};
const NONE: WallpaperChoice = { kind: 'none' };
/** Типове тло — рівна поверхня теми. Рельєф і решта — лише власним вибором (`wallpaperPicked`). */
const DEFAULT_WALLPAPER: WallpaperChoice = NONE;

export const DEFAULT_LOOK: Look = {
  accent: 'amber',
  wallpaper: DEFAULT_WALLPAPER,
  wallpaperPicked: false,
  chats: {},
  dim: { day: 55, night: 80 },
  blur: { day: 0, night: 0 },
  bubbles: 'comfortable',
  list: 'comfortable',
};

const obj = (v: unknown): Record<string, unknown> =>
  v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {};

const clamp = (v: unknown, max: number, fallback: number): number =>
  typeof v === 'number' && Number.isFinite(v) ? Math.min(max, Math.max(0, Math.round(v))) : fallback;

const density = (v: unknown): Density => (v === 'compact' ? 'compact' : 'comfortable');

const choice = (v: unknown): WallpaperChoice | null => {
  const o = obj(v);
  if (o.kind === 'none') return NONE;
  if (o.kind === 'builtin' && (WALLPAPERS as readonly unknown[]).includes(o.id)) return { kind: 'builtin', id: o.id as BuiltinWallpaper };
  if (o.kind === 'photo' && typeof o.url === 'string' && /^(\/webmsgr\/_look\/[a-z]+-[a-z0-9]+\.jpg|data:image\/)/.test(o.url)) {
    return { kind: 'photo', url: o.url };
  }
  return null;
};

/** Будь-що з мосту чи сховища → чесний [Look]; невідоме падає на типове. */
export const normalizeLook = (raw: unknown): Look => {
  const o = obj(raw);
  const chats: Record<string, WallpaperChoice> = {};
  for (const [id, v] of Object.entries(obj(o.chats))) {
    const c = choice(v);
    if (c && id) chats[id] = c;
  }
  const dim = obj(o.dim);
  const blur = obj(o.blur);
  const wallpaperPicked = o.wallpaperPicked === true;
  const w = choice(o.wallpaper);
  return {
    accent: (ACCENTS as readonly unknown[]).includes(o.accent) ? (o.accent as Accent) : 'amber',
    wallpaper: w && wallpaperPicked ? w : DEFAULT_WALLPAPER,
    wallpaperPicked,
    chats,
    dim: { day: clamp(dim.day, DIM_MAX, DEFAULT_LOOK.dim.day), night: clamp(dim.night, DIM_MAX, DEFAULT_LOOK.dim.night) },
    blur: { day: clamp(blur.day, BLUR_MAX, 0), night: clamp(blur.night, BLUR_MAX, 0) },
    bubbles: density(o.bubbles),
    list: density(o.list),
  };
};

type LookBridge = { getAppearance?: () => unknown };
const lookBridge = (): LookBridge | null => {
  if (typeof window === 'undefined') return null;
  const b = (window as unknown as { PhantomBridge?: LookBridge }).PhantomBridge;
  return b && typeof b.getAppearance === 'function' ? b : null;
};

const storageKey = (): string => `nightpost.look.v1.${useMessengerStore.getState().currentUser.id || 'me'}`;

/** Те, що діє зараз: рідний бік, а без нього — сховище цієї особи. */
export const readLook = (): Look => {
  const bridge = lookBridge();
  if (bridge) {
    try {
      const raw = bridge.getAppearance?.();
      return normalizeLook(typeof raw === 'string' ? JSON.parse(raw) : raw);
    } catch {
      return DEFAULT_LOOK;
    }
  }
  try {
    const raw = localStorage.getItem(storageKey());
    return raw ? normalizeLook(JSON.parse(raw)) : DEFAULT_LOOK;
  } catch {
    return DEFAULT_LOOK;
  }
};

export const effectiveLook = (global: Look, profile: Profile | null | undefined): Look => {
  const pl = profile?.look;
  if (!pl) return { ...global };
  const accent: Accent =
    pl.accent && (ACCENTS as readonly unknown[]).includes(pl.accent) ? pl.accent : global.accent;
  let wallpaper: WallpaperChoice = global.wallpaper;
  let wallpaperPicked: boolean = global.wallpaperPicked;
  if (pl.wallpaper === 'none') {
    wallpaper = NONE;
    wallpaperPicked = true;
  } else if (pl.wallpaper && (WALLPAPERS as readonly unknown[]).includes(pl.wallpaper)) {
    wallpaper = { kind: 'builtin', id: pl.wallpaper };
    wallpaperPicked = true;
  }
  return {
    ...global,
    accent,
    wallpaper,
    wallpaperPicked,
  };
};

const activeProfile = (): Profile | null => {
  const { profiles, activeId } = useProfiles.getState();
  return profiles.find((p) => p.id === activeId) ?? profiles[0] ?? null;
};

export const applyLookToRoot = (look: Look = readLook()): void => {
  if (typeof document === 'undefined') return;
  const eff = effectiveLook(look, activeProfile());
  const root = document.documentElement;
  // Головна дія — завжди загальний акцент (бурштин за замовчуванням) у кожному профілі (лідер 28.10:
  // блакитні «Надіслати»/«Далі» під «Роботою»). Тон профілю — лише на його пігулці, знаку й кільці.
  // `look` тут уже може бути злитий з профілем, тож загальний акцент беремо із самого вибору людини.
  root.setAttribute('data-accent', readLook().accent);
  root.setAttribute('data-profile-accent', eff.accent);
  root.setAttribute('data-bubbles', eff.bubbles);
  root.setAttribute('data-list-density', eff.list);
};

export const wallpaperFor = (look: Look, chatId: string | undefined): WallpaperChoice =>
  (chatId ? look.chats[chatId] : undefined) ?? look.wallpaper;

export const wallpaperUrl = (c: WallpaperChoice): string | null => {
  if (c.kind === 'builtin') return `${import.meta.env.BASE_URL}wallpapers/${c.id}.webp`;
  if (c.kind === 'photo') return c.url;
  return null;
};

export const PHOTO_EDGE = 1440;

/** Фото з галереї → data-URL JPEG не більше [PHOTO_EDGE]; рідний бік стискає ще раз. */
export async function wallpaperPhoto(file: Blob, deps: ImagePrepDeps | null = canPrepareImages() ? browserDeps : null): Promise<string | null> {
  if (!deps || !file.type.startsWith('image/')) return null;
  try {
    const d = await deps.decode(file);
    try {
      const m = fitWithin(d.width, d.height, PHOTO_EDGE);
      const blob = await deps.encode(d.source, m.width, m.height, 'image/jpeg', 0.82);
      if (!blob || blob.size === 0) return null;
      const url = await new Promise<string>((ok, fail) => {
        const r = new FileReader();
        r.onload = () => ok(String(r.result));
        r.onerror = () => fail(r.error);
        r.readAsDataURL(blob);
      });
      return url.startsWith('data:image/') ? url : null;
    } finally {
      d.close?.();
    }
  } catch {
    return null;
  }
}

/** Що людина обрала для розмови: `default` — знову як усюди; фото — data-URL уже стиснутого JPEG. */
export type ChatWallpaperPick = { kind: 'default' } | { kind: 'none' } | { kind: 'builtin'; id: BuiltinWallpaper } | { kind: 'photo'; data: string };

interface LookState {
  look: Look;
  refresh: () => void;
  setChatWallpaper: (chatId: string, pick: ChatWallpaperPick) => Promise<void>;
}

export const useLook = create<LookState>((set, get) => ({
  look: DEFAULT_LOOK,
  refresh: () => {
    const look = effectiveLook(readLook(), activeProfile());
    applyLookToRoot(look);
    set({ look });
  },
  setChatWallpaper: async (chatId, pick) => {
    if (lookBridge()) {
      const raw = await request<unknown>('PUT', `/messenger/appearance/chats/${encodeURIComponent(chatId)}`, pick);
      const look = effectiveLook(normalizeLook(raw), activeProfile());
      applyLookToRoot(look);
      set({ look });
      return;
    }
    const cur = get().look;
    const chats = { ...cur.chats };
    if (pick.kind === 'default') delete chats[chatId];
    else chats[chatId] = pick.kind === 'photo' ? { kind: 'photo', url: pick.data } : pick;
    const base = normalizeLook({ ...readLook(), chats });
    try {
      localStorage.setItem(storageKey(), JSON.stringify(base));
    } catch {
      /* без сховища вибір живе до перезапуску */
    }
    const look = effectiveLook(base, activeProfile());
    applyLookToRoot(look);
    set({ look });
  },
}));

/** Ставить вигляд зараз і тримає його свіжим: поштовх рідного боку, зміна своєї особи. */
export function startLookWatch(): () => void {
  const { refresh } = useLook.getState();
  refresh();
  if (typeof window === 'undefined') return () => undefined;
  const w = window as unknown as Record<string, unknown>;
  const previous = w.__phantomAppearance;
  w.__phantomAppearance = () => useLook.getState().refresh();
  const offIdentity = useMessengerStore.subscribe((s, prev) => {
    if (s.currentUser.id !== prev.currentUser.id) useLook.getState().refresh();
  });
  const offProfiles = useProfiles.subscribe((s, prev) => {
    const cur = s.profiles.find((p) => p.id === s.activeId) ?? s.profiles[0] ?? null;
    const old = prev.profiles.find((p) => p.id === prev.activeId) ?? prev.profiles[0] ?? null;
    if (cur?.id !== old?.id || cur?.look !== old?.look) {
      useLook.getState().refresh();
    }
  });
  return () => {
    offIdentity();
    offProfiles();
    if (previous === undefined) delete w.__phantomAppearance;
    else w.__phantomAppearance = previous;
  };
}
