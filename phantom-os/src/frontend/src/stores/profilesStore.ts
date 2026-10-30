import { create } from 'zustand';
import type { ProfileKit } from '@shared/types/profileKit';
import { cleanKit, kitForKind } from '../services/profileKit';
import { barSlots, nativeBarTabs } from '../components/messenger/directions';
import { resolveLocale } from '../i18n/useTranslation';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from './settingsStore';
import { tNow } from '../components/chat/tNow';
import type { MessageKey } from '../i18n/messages/uk';
import { uk as ukProfiles, en as enProfiles } from '../i18n/messages/parts/profilesW1';
import { cs as csProfiles } from '../i18n/messages/parts/cs/profilesW1';
import type { Accent, BuiltinWallpaper } from '../services/appearance';
import { forgetProfileSpaces } from '../services/spaces/spaceVault';
import { forgetProfileDrafts } from '../services/draftVault';
import { stashForBackup } from '../services/backupStash';
import { chatProfileMaps } from './chatProfileStore';

export const PROFILES_KEY = 'np.profiles.v1';
export const PASS_NONE = 'none';

export const PROFILE_GLYPHS = ['home', 'work', 'shop', 'book', 'heart', 'plane'] as const;
export type ProfileGlyph = (typeof PROFILE_GLYPHS)[number];

export interface ProfileLook {
  accent?: Accent;
  wallpaper?: BuiltinWallpaper | 'none';
  glyph?: ProfileGlyph;
}

export type ProfileKind =
  | 'personal'
  | 'family'
  | 'work'
  | 'business'
  | 'dev'
  | 'study'
  | 'trip'
  | 'hobby'
  | 'author'
  | 'place'
  | 'org'
  | 'temp'
  | 'unnamed'
  | 'own';

export const PROFILE_KINDS: readonly ProfileKind[] = [
  'personal',
  'family',
  'work',
  'business',
  'dev',
  'study',
  'trip',
  'hobby',
  'author',
  'place',
  'org',
  'temp',
  'unnamed',
  'own',
] as const;

/** profiles-v4 §2: hidden from the picker until their tabs have screens; existing ones stay. */
export const HIDDEN_PROFILE_KINDS: readonly ProfileKind[] = ['dev', 'author'] as const;

export const NEW_PROFILE_KINDS: readonly ProfileKind[] = [
  'personal',
  'work',
  'business',
  'study',
  'unnamed',
  'family',
  'trip',
  'temp',
] as const;

export const STAFF_ROLES = ['admin', 'staff'] as const;
export type StaffRole = (typeof STAFF_ROLES)[number];

export interface StaffMember {
  handle: string;
  role: StaffRole;
  addedAt: number;
  /** Розмови бізнесу, доручені цій людині; одна розмова — один працівник. */
  threads: string[];
}

export const STAFF_THREADS_MAX = 20;

export interface BizHours {
  from: string;
  to: string;
}

export interface BizService {
  id: string;
  name: string;
  price: string;
}

/** Картка бізнес-профілю. Лише на цьому пристрої: сервер її не тримає. */
export interface BizCard {
  category: string;
  address: string;
  hours: BizHours | null;
  phone: string;
  email: string;
  services: BizService[];
}

export const BIZ_MAX = { category: 40, address: 120, phone: 32, email: 80, svcName: 60, svcPrice: 20 } as const;

const HHMM_RE = /^([01]\d|2[0-3]):[0-5]\d$/;
export const isHhmm = (v: unknown): v is string => typeof v === 'string' && HHMM_RE.test(v);

export const EMPTY_BIZ: BizCard = {
  category: '',
  address: '',
  hours: null,
  phone: '',
  email: '',
  services: [],
};

export const HANDLE_RE = /^[a-z0-9_]{3,32}$/;

/** Куди перейти, коли активний профіль зник: «Особисте», інакше перший. */
export function fallbackProfile(remaining: Profile[]): Profile {
  return remaining.find((p) => p.kind === 'personal') ?? remaining[0];
}

export function cleanHandle(raw: string): string {
  return raw.trim().replace(/^@+/, '').toLowerCase();
}

export const PROFILE_ALERTS = ['sound', 'vibrate', 'silent'] as const;
export type ProfileAlert = (typeof PROFILE_ALERTS)[number];

export interface Profile {
  id: string;
  name: string;
  kind: ProfileKind;
  look: ProfileLook | null;
  passGroups: string[];
  quiet: { from: string; to: string } | null;
  alert: ProfileAlert;
  expiresAt: number | null;
  faces: Record<string, { name?: string; status?: string; bio?: string; photoId?: string }>;
  homeOrder: string[];
  walletLabel: string | null;
  /** Імʼя в цьому профілі; порожнє — основне. */
  ownName: string;
  assistantReads: boolean;
  autoByDay: boolean;
  /** Сторінка закладу з «Білдера», яку власник обрав для бізнес-профілю. Лише на цьому пристрої. */
  venueId: string | null;
  /** Бізнес-картка: категорія, адреса, години, контакти, послуги. */
  biz: BizCard;
  /** Команда бізнес-профілю; власник — це ти, окремим рядком його немає. */
  staff: StaffMember[];
  /** Вкладки, швидкі дії, сповіщення, помічник, гаманець — 11-spaces §7. */
  kit: ProfileKit;
  uses: number;
  createdAt: number;
}

export interface BridgeProfile {
  id: string;
  name: string;
  kind: ProfileKind;
  passGroups: string[];
  quiet: { from: string; to: string } | null;
  alert: ProfileAlert;
  expiresAt: number | null;
  faces: Record<string, { name?: string; status?: string; bio?: string; photoId?: string }>;
  ownName: string;
  kit: ProfileKit;
  /** Рідні вкладки смуги (мапа, гаманець), які телефон показує в цьому профілі. */
  bar: Array<'map' | 'wallet'>;
  /** Власні вкладки напряму після «Розмов»: id і підпис; телефон малює їх у смузі й відкриває веб-хост. */
  slots: Array<{ id: string; label: string }>;
  /** Вигляд для «Я» на телефоні: акцент (null — загальний) і знак профілю. */
  accent: Accent | null;
  glyph: ProfileGlyph;
}

export interface ProfilesBridgePayload {
  v: 2;
  activeId: string;
  profiles: BridgeProfile[];
  /** Вузол людини → профіль, що володіє розмовою з нею (або найстаршою спільною групою). */
  peerProfile: Record<string, string>;
  /** Ключ розмови, як його бачить PeerNotifications (вузол людини / id групи) → профіль. */
  chatProfile: Record<string, string>;
}

export interface ProfilesState {
  profiles: Profile[];
  activeId: string;
  previousId: string | null;
  addFromTemplate: (kind: ProfileKind, name?: string) => Profile;
  rename: (id: string, name: string) => void;
  update: (id: string, patch: Partial<Omit<Profile, 'id' | 'createdAt'>>) => void;
  setProfileLook: (
    id: string,
    patch: { [K in keyof ProfileLook]?: ProfileLook[K] | undefined },
  ) => void;
  remove: (id: string) => boolean;
  addStaff: (id: string, handle: string, role: StaffRole) => 'ok' | 'bad' | 'dup';
  setStaffRole: (id: string, handle: string, role: StaffRole) => void;
  revokeStaff: (id: string, handle: string) => void;
  assignThread: (id: string, chatId: string, handle: string | null) => void;
  setActive: (id: string) => boolean;
  switchBack: () => Profile | null;
  mostUsed: (n?: number) => Profile[];
  fanChoices: (n?: number) => Profile[];
  sweepExpired: (now?: number) => void;
  extendTemp: (id: string) => void;
}

const ONE_DAY_MS = 864e5;
const HOUR_MS = 36e5;

/** «На час», що скінчиться протягом години: найближчий; інакше null. */
export function tempEndingSoon(profiles: readonly Profile[], now: number): Profile | null {
  let soonest: Profile | null = null;
  for (const p of profiles) {
    if (p.kind !== 'temp' || p.expiresAt === null || p.expiresAt <= now || p.expiresAt - now > HOUR_MS) continue;
    if (!soonest || p.expiresAt < (soonest.expiresAt as number)) soonest = p;
  }
  return soonest;
}

export interface EndedProfile {
  id: string;
  name: string;
}
type EndedListener = (ended: EndedProfile[]) => void;
const endedListeners = new Set<EndedListener>();
let endedQueue: EndedProfile[] = [];

/** Хто прибирає хвости скінченого «На час»; кінець до підписки чекає в черзі цього запуску. */
export function onTempProfilesEnded(fn: EndedListener): () => void {
  endedListeners.add(fn);
  if (endedQueue.length) {
    const queued = endedQueue;
    endedQueue = [];
    fn(queued);
  }
  return () => {
    endedListeners.delete(fn);
  };
}

function emitEnded(ended: EndedProfile[]): void {
  if (!ended.length) return;
  if (endedListeners.size === 0) {
    endedQueue = [...endedQueue, ...ended];
    return;
  }
  for (const fn of endedListeners) fn(ended);
}
const VALID_ACCENTS: readonly Accent[] = ['amber', 'sage', 'sea', 'iris', 'rose', 'graphite'];
const VALID_WALLPAPERS: readonly BuiltinWallpaper[] = [
  'contour',
  'sand',
  'sage',
  'slate',
  'clay',
  'dusk',
  'stone',
];

export function defaultGlyphForKind(kind: ProfileKind): ProfileGlyph {
  if (kind === 'work' || kind === 'dev') return 'work';
  if (kind === 'business' || kind === 'place' || kind === 'org' || kind === 'author') return 'shop';
  if (kind === 'study') return 'book';
  if (kind === 'family') return 'heart';
  if (kind === 'trip' || kind === 'hobby') return 'plane';
  return 'home';
}

export function glyphForProfile(
  profile: Pick<Profile, 'kind' | 'look'> | null | undefined,
): ProfileGlyph {
  if (!profile) return 'home';
  return profile.look?.glyph ?? defaultGlyphForKind(profile.kind);
}

export function templateDefaults(
  kind: ProfileKind,
  now: number = Date.now(),
): Omit<Profile, 'id' | 'name'> {
  const base: Omit<Profile, 'id' | 'name'> = {
    kind,
    look: null,
    passGroups: [],
    quiet: null,
    alert: 'sound',
    expiresAt: null,
    faces: {},
    homeOrder: [],
    walletLabel: null,
    ownName: '',
    assistantReads: true,
    autoByDay: false,
    venueId: null,
    biz: { ...EMPTY_BIZ, services: [] },
    staff: [],
    kit: kitForKind(kind),
    uses: 0,
    createdAt: now,
  };

  return { ...base, ...KIND_TEMPLATE[kind](now) };
}

const WORK_QUIET = { from: '20:00', to: '08:00' };
const STUDY_QUIET = { from: '16:00', to: '08:00' };

const KIND_TEMPLATE: Record<ProfileKind, (now: number) => Partial<Omit<Profile, 'id' | 'name'>>> = {
  personal: () => ({ passGroups: ['personal', 'close', 'friends'] }),
  family: () => ({
    passGroups: ['personal', 'close', 'friends'],
    quiet: null,
    look: { accent: 'rose', glyph: 'heart' },
  }),
  hobby: () => ({ passGroups: ['personal', 'close', 'friends'] }),
  work: () => ({
    passGroups: ['work'],
    quiet: { ...WORK_QUIET },
    look: { accent: 'sea', glyph: 'work' },
  }),
  business: () => ({
    passGroups: ['work'],
    look: { accent: 'sage', glyph: 'shop' },
  }),
  dev: () => ({
    passGroups: ['work'],
    look: { accent: 'sea', glyph: 'work' },
  }),
  study: () => ({
    passGroups: ['work'],
    quiet: { ...STUDY_QUIET },
    look: { accent: 'iris', glyph: 'book' },
  }),
  trip: () => ({
    look: { accent: 'graphite', glyph: 'plane' },
  }),
  org: () => ({ passGroups: ['work'] }),
  temp: (now) => ({
    expiresAt: now + ONE_DAY_MS,
    look: { accent: 'amber' },
  }),
  unnamed: () => ({
    passGroups: [PASS_NONE],
    assistantReads: false,
    quiet: { from: '22:00', to: '08:00' },
    look: { accent: 'graphite', glyph: 'home' },
  }),
  author: () => ({
    passGroups: ['friends', 'social'],
    look: { accent: 'iris', glyph: 'book' },
  }),
  place: () => ({}),
  own: () => ({}),
};

type ProfileMessageKey = keyof typeof ukProfiles & MessageKey;

export function kindLabel(kind: ProfileKind): string {
  const key: ProfileMessageKey = `profiles.kind.${kind}`;
  return tNow(key);
}

function isDefaultKindName(kind: ProfileKind, name: string): boolean {
  const key: ProfileMessageKey = `profiles.kind.${kind}`;
  return name === ukProfiles[key] || name === enProfiles[key] || name === csProfiles[key];
}

function nextId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return Date.now().toString(36) + Math.random().toString(36).slice(2);
}

function isProfileKind(value: unknown): value is ProfileKind {
  return typeof value === 'string' && (PROFILE_KINDS as readonly string[]).includes(value);
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

const FACE_FIELDS = ['name', 'status', 'bio', 'photoId'] as const;

function cleanProfileLook(raw: unknown): ProfileLook | null {
  if (!isPlainObject(raw)) return null;
  const out: ProfileLook = {};
  if ((VALID_ACCENTS as readonly unknown[]).includes(raw.accent)) {
    out.accent = raw.accent as Accent;
  }
  if (raw.wallpaper === 'none' || (VALID_WALLPAPERS as readonly unknown[]).includes(raw.wallpaper)) {
    out.wallpaper = raw.wallpaper as BuiltinWallpaper | 'none';
  }
  if ((PROFILE_GLYPHS as readonly unknown[]).includes(raw.glyph)) {
    out.glyph = raw.glyph as ProfileGlyph;
  }
  return Object.keys(out).length > 0 ? out : null;
}

function cleanFaces(raw: unknown): Profile['faces'] {
  if (!isPlainObject(raw)) return {};
  const out: Profile['faces'] = {};
  for (const [ring, face] of Object.entries(raw)) {
    if (!ring || !isPlainObject(face)) continue;
    const kept: Profile['faces'][string] = {};
    for (const field of FACE_FIELDS) {
      const value = face[field];
      if (typeof value === 'string') kept[field] = value;
    }
    out[ring] = kept;
  }
  return out;
}

const str = (v: unknown, max: number): string => (typeof v === 'string' ? v.slice(0, max) : '');

function cleanBiz(raw: unknown): BizCard {
  if (!isPlainObject(raw)) return { ...EMPTY_BIZ, services: [] };
  const hours =
    isPlainObject(raw.hours) && isHhmm(raw.hours.from) && isHhmm(raw.hours.to)
      ? { from: raw.hours.from, to: raw.hours.to }
      : null;
  const services = Array.isArray(raw.services)
    ? raw.services
        .filter(isPlainObject)
        .map((x) => ({ id: str(x.id, 40), name: str(x.name, BIZ_MAX.svcName), price: str(x.price, BIZ_MAX.svcPrice) }))
        .filter((x) => x.id && (x.name || x.price))
        .slice(0, 50)
    : [];
  return {
    category: str(raw.category, BIZ_MAX.category),
    address: str(raw.address, BIZ_MAX.address),
    hours,
    phone: str(raw.phone, BIZ_MAX.phone),
    email: str(raw.email, BIZ_MAX.email),
    services,
  };
}

function cleanStaff(raw: unknown): StaffMember[] {
  if (!Array.isArray(raw)) return [];
  const seen = new Set<string>();
  const out: StaffMember[] = [];
  for (const x of raw) {
    if (!isPlainObject(x) || typeof x.handle !== 'string') continue;
    const handle = cleanHandle(x.handle);
    if (!HANDLE_RE.test(handle) || seen.has(handle)) continue;
    seen.add(handle);
    const role: StaffRole = x.role === 'admin' ? 'admin' : 'staff';
    const threads = Array.isArray(x.threads)
      ? [...new Set(x.threads.filter((c): c is string => typeof c === 'string' && c.length > 0 && c.length <= 80))].slice(0, STAFF_THREADS_MAX)
      : [];
    out.push({ handle, role, addedAt: typeof x.addedAt === 'number' ? x.addedAt : 0, threads });
  }
  return out;
}

function firstRunState(now: number = Date.now()): { activeId: string; profiles: Profile[] } {
  const id = nextId();
  const profile: Profile = {
    ...templateDefaults('personal', now),
    id,
    name: kindLabel('personal'),
    passGroups: [],
  };
  return { activeId: id, profiles: [profile] };
}

function normalizeProfile(raw: unknown, now: number): Profile | null {
  if (!isPlainObject(raw)) return null;
  if (typeof raw.id !== 'string' || !raw.id) return null;
  if (typeof raw.name !== 'string') return null;
  if (!isProfileKind(raw.kind)) return null;

  const defaults = templateDefaults(raw.kind, now);

  const look = cleanProfileLook(raw.look);

  const passGroups = Array.isArray(raw.passGroups)
    ? raw.passGroups.filter((item): item is string => typeof item === 'string')
    : defaults.passGroups;

  const quiet =
    raw.quiet === null
      ? null
      : isPlainObject(raw.quiet) &&
          typeof raw.quiet.from === 'string' &&
          typeof raw.quiet.to === 'string'
        ? { from: raw.quiet.from, to: raw.quiet.to }
        : defaults.quiet;

  const alert: ProfileAlert = (PROFILE_ALERTS as readonly unknown[]).includes(raw.alert)
    ? (raw.alert as ProfileAlert)
    : 'sound';

  const expiresAt =
    raw.expiresAt === null
      ? null
      : typeof raw.expiresAt === 'number' && Number.isFinite(raw.expiresAt)
        ? raw.expiresAt
        : defaults.expiresAt;

  const faces = cleanFaces(raw.faces);

  const homeOrder = Array.isArray(raw.homeOrder)
    ? raw.homeOrder.filter((item): item is string => typeof item === 'string')
    : defaults.homeOrder;

  const walletLabel =
    raw.walletLabel === null
      ? null
      : typeof raw.walletLabel === 'string'
        ? raw.walletLabel
        : defaults.walletLabel;

  const ownName = typeof raw.ownName === 'string' ? raw.ownName.trim().slice(0, 40) : '';

  const assistantReads =
    typeof raw.assistantReads === 'boolean' ? raw.assistantReads : defaults.assistantReads;

  const autoByDay =
    typeof raw.autoByDay === 'boolean' ? raw.autoByDay : defaults.autoByDay;

  const venueId = typeof raw.venueId === 'string' && raw.venueId ? raw.venueId : null;

  const kit = cleanKit(raw.kit, raw.kind);
  const biz = cleanBiz(raw.biz);
  const staff = cleanStaff(raw.staff);

  const uses =
    typeof raw.uses === 'number' && Number.isFinite(raw.uses) ? raw.uses : defaults.uses;

  const createdAt =
    typeof raw.createdAt === 'number' && Number.isFinite(raw.createdAt)
      ? raw.createdAt
      : defaults.createdAt;

  return {
    id: raw.id,
    name: raw.name,
    kind: raw.kind,
    look,
    passGroups,
    quiet,
    alert,
    expiresAt,
    faces,
    homeOrder,
    walletLabel,
    ownName,
    assistantReads,
    autoByDay,
    venueId,
    biz,
    staff,
    kit,
    uses,
    createdAt,
  };
}

type Stored = { activeId: string; profiles: Profile[]; previousId?: string | null };

function writeStorage(next: Stored): void {
  try {
    if (typeof localStorage !== 'undefined') {
      const raw = JSON.stringify({ v: 1, activeId: next.activeId, previousId: next.previousId ?? null, profiles: next.profiles });
      localStorage.setItem(PROFILES_KEY, raw);
      stashForBackup(PROFILES_KEY, raw);
    }
  } catch {
    // Ігноруємо помилки квоти або недоступного сховища.
  }
}

export const UNREAD_KEY = `${PROFILES_KEY}.unread`;

export function loadProfiles(persist = true): { activeId: string; profiles: Profile[]; previousId: string | null } {
  const now = Date.now();
  const fresh = () => {
    const state = { ...firstRunState(now), previousId: null };
    if (persist) writeStorage(state);
    return state;
  };
  const setAside = (raw: string) => {
    if (persist) {
      try {
        if (localStorage.getItem(UNREAD_KEY) === null) localStorage.setItem(UNREAD_KEY, raw);
      } catch {
        return { ...firstRunState(now), previousId: null };
      }
    }
    return fresh();
  };
  try {
    if (typeof localStorage === 'undefined') {
      return fresh();
    }
    const raw = localStorage.getItem(PROFILES_KEY);
    if (!raw) {
      return fresh();
    }
    const parsed: unknown = JSON.parse(raw);
    if (!isPlainObject(parsed) || !Array.isArray(parsed.profiles)) {
      return setAside(raw);
    }
    const seen = new Set<string>();
    const profiles = parsed.profiles
      .map((item) => normalizeProfile(item, now))
      .filter((item): item is Profile => item !== null && !seen.has(item.id) && Boolean(seen.add(item.id)));
    if (profiles.length === 0) {
      return setAside(raw);
    }
    const activeId =
      typeof parsed.activeId === 'string' && profiles.some((p) => p.id === parsed.activeId)
        ? parsed.activeId
        : profiles[0].id;
    const previousId =
      typeof parsed.previousId === 'string' &&
      parsed.previousId !== activeId &&
      profiles.some((p) => p.id === parsed.previousId)
        ? parsed.previousId
        : null;
    return { activeId, profiles, previousId };
  } catch {
    try {
      const raw = localStorage.getItem(PROFILES_KEY);
      return raw ? setAside(raw) : { ...firstRunState(now), previousId: null };
    } catch {
      return { ...firstRunState(now), previousId: null };
    }
  }
}

export const useProfiles = create<ProfilesState>((set, get) => {
  const commit = (next: Stored) => {
    const kept = next.previousId !== undefined ? next.previousId : get().previousId;
    const previousId =
      kept && kept !== next.activeId && next.profiles.some((p) => p.id === kept) ? kept : null;
    set({ ...next, previousId });
    writeStorage({ ...next, previousId });
  };

  const initial = loadProfiles(false);

  return {
    profiles: initial.profiles,
    activeId: initial.activeId,
    previousId: initial.previousId,

    addFromTemplate: (kind, name) => {
      const existing = kind === 'personal' ? get().profiles.find((p) => p.kind === 'personal') : undefined;
      if (existing) return existing;
      const now = Date.now();
      const resolvedName = name?.trim() || kindLabel(kind);
      const created: Profile = {
        ...templateDefaults(kind, now),
        id: nextId(),
        name: resolvedName,
      };
      const { activeId, profiles } = get();
      commit({ activeId, profiles: [...profiles, created] });
      return created;
    },

    rename: (id, name) => {
      const trimmed = name.trim();
      if (!trimmed) return;
      const { activeId, profiles } = get();
      if (!profiles.some((p) => p.id === id)) return;
      commit({
        activeId,
        profiles: profiles.map((p) => (p.id === id ? { ...p, name: trimmed } : p)),
      });
    },

    update: (id, patch) => {
      const { activeId, profiles } = get();
      if (!profiles.some((p) => p.id === id)) return;
      commit({
        activeId,
        profiles: profiles.map((p) =>
          p.id === id ? { ...p, ...patch, id: p.id, createdAt: p.createdAt } : p,
        ),
      });
    },

    setProfileLook: (id, patch) => {
      const { activeId, profiles } = get();
      if (!profiles.some((p) => p.id === id)) return;
      commit({
        activeId,
        profiles: profiles.map((p) => {
          if (p.id !== id) return p;
          const merged: Record<string, unknown> = { ...(p.look ?? {}) };
          for (const [k, v] of Object.entries(patch)) {
            if (v === undefined) delete merged[k];
            else merged[k] = v;
          }
          return { ...p, look: cleanProfileLook(merged) };
        }),
      });
    },

    remove: (id) => {
      const { activeId, profiles } = get();
      if (profiles.length <= 1) return false;
      if (!profiles.some((p) => p.id === id)) return false;
      const remaining = profiles.filter((p) => p.id !== id);
      const nextActiveId =
        activeId === id ? fallbackProfile(remaining).id : activeId;
      commit({ activeId: nextActiveId, profiles: remaining });
      forgetProfileSpaces(id);
      forgetProfileDrafts(id);
      return true;
    },

    addStaff: (id, handle, role) => {
      const h = cleanHandle(handle);
      if (!HANDLE_RE.test(h)) return 'bad';
      const target = get().profiles.find((p) => p.id === id);
      if (!target) return 'bad';
      if (target.staff.some((m) => m.handle === h)) return 'dup';
      get().update(id, { staff: [...target.staff, { handle: h, role, addedAt: Date.now(), threads: [] }] });
      return 'ok';
    },

    setStaffRole: (id, handle, role) => {
      const target = get().profiles.find((p) => p.id === id);
      if (!target) return;
      get().update(id, { staff: target.staff.map((m) => (m.handle === handle ? { ...m, role } : m)) });
    },

    revokeStaff: (id, handle) => {
      const target = get().profiles.find((p) => p.id === id);
      if (!target) return;
      get().update(id, { staff: target.staff.filter((m) => m.handle !== handle) });
    },

    assignThread: (id, chatId, handle) => {
      const target = get().profiles.find((p) => p.id === id);
      if (!target) return;
      const staff = target.staff.map((m) => {
        const rest = m.threads.filter((c) => c !== chatId);
        if (m.handle !== handle || rest.length >= STAFF_THREADS_MAX) return { ...m, threads: rest };
        return { ...m, threads: [...rest, chatId] };
      });
      get().update(id, { staff });
    },

    setActive: (id) => {
      const { profiles, activeId } = get();
      if (!profiles.some((p) => p.id === id)) return false;
      commit({
        activeId: id,
        previousId: id === activeId ? undefined : activeId,
        profiles: profiles.map((p) => (p.id === id ? { ...p, uses: p.uses + 1 } : p)),
      });
      return true;
    },

    switchBack: () => {
      const { previousId, profiles } = get();
      const target = previousId ? profiles.find((p) => p.id === previousId) : undefined;
      if (!target || !get().setActive(target.id)) return null;
      return get().profiles.find((p) => p.id === target.id) ?? null;
    },

    mostUsed: (n = 3) => {
      const { profiles } = get();
      return [...profiles]
        .sort((a, b) => b.uses - a.uses || a.createdAt - b.createdAt)
        .slice(0, n);
    },

    fanChoices: (n = 3) => {
      const { activeId } = get();
      return get()
        .mostUsed(Number.MAX_SAFE_INTEGER)
        .filter((p) => p.id !== activeId)
        .slice(0, n);
    },

    sweepExpired: (now = Date.now()) => {
      const { activeId, profiles } = get();
      const isExpiredTemp = (p: Profile) =>
        p.kind === 'temp' && p.expiresAt !== null && p.expiresAt <= now;
      if (!profiles.some(isExpiredTemp) && profiles.length > 0) {
        return;
      }
      const remaining = profiles.filter((p) => !isExpiredTemp(p));
      const ended = profiles.filter(isExpiredTemp).map((p) => ({ id: p.id, name: p.name }));
      for (const { id } of ended) {
        forgetProfileSpaces(id);
        forgetProfileDrafts(id);
      }
      if (remaining.length === 0) {
        commit(firstRunState(now));
        emitEnded(ended);
        return;
      }
      const nextActiveId = remaining.some((p) => p.id === activeId)
        ? activeId
        : fallbackProfile(remaining).id;
      commit({ activeId: nextActiveId, profiles: remaining });
      emitEnded(ended);
    },

    extendTemp: (id) => {
      const target = get().profiles.find((p) => p.id === id);
      if (!target || target.kind !== 'temp' || target.expiresAt === null) return;
      get().update(id, { expiresAt: target.expiresAt + ONE_DAY_MS });
    },
  };
});

export function buildBridgePayload(state: {
  activeId: string;
  profiles: Profile[];
}): ProfilesBridgePayload {
  return {
    v: 2,
    activeId: state.activeId,
    profiles: state.profiles.map(
      ({ id, name, kind, look, passGroups, quiet, alert, expiresAt, faces, kit, ownName }) => ({
        id,
        name,
        kind,
        passGroups,
        quiet,
        alert,
        expiresAt,
        faces,
        ownName,
        kit,
        bar: nativeBarTabs(kind),
        slots: barSlots(kind, resolveLocale(useSettingsStore.getState().values[LANGUAGE_SETTING_KEY])),
        accent: look?.accent ?? null,
        glyph: glyphForProfile({ kind, look }),
      }),
    ),
    ...chatProfileMaps(state.profiles),
  };
}

export function pushProfilesToNative(
  state: { activeId: string; profiles: Profile[] } = useProfiles.getState(),
): void {
  try {
    const bridge = (
      globalThis as { PhantomBridge?: { setProfiles?: (json: string) => void } }
    ).PhantomBridge;
    if (typeof bridge?.setProfiles === 'function') {
      bridge.setProfiles(JSON.stringify(buildBridgePayload(state)));
    }
  } catch {
    // Міст недоступний або відхилив виклик.
  }
}

type ProfilesWindow = Window & {
  __phantomProfiles?: { setActive: (id: string) => boolean; open: () => boolean; back: () => string };
};

const openers: Array<() => void> = [];

export function registerProfilesOpener(open: () => void): () => void {
  openers.push(open);
  return () => {
    const idx = openers.lastIndexOf(open);
    if (idx >= 0) openers.splice(idx, 1);
  };
}

export function openProfilesSheet(): boolean {
  const current = openers[openers.length - 1];
  if (!current) return false;
  current();
  return true;
}

export function profileToneIndex(profiles: Profile[], id: string): number {
  const order = [...profiles].sort((a, b) => a.createdAt - b.createdAt || a.id.localeCompare(b.id));
  const at = order.findIndex((p) => p.id === id);
  return (at < 0 ? 0 : at) % 6;
}

let activeStop: (() => void) | null = null;

function pullSpaces(): void {
  const id = useProfiles.getState().activeId;
  void import('../services/spaces/spaceWire').then((m) => m.syncSpaces(id)).catch(() => {});
}

export function startProfiles(): () => void {
  if (activeStop !== null) {
    return activeStop;
  }

  useProfiles.setState(loadProfiles());
  useProfiles.getState().sweepExpired(Date.now());
  pushProfilesToNative(useProfiles.getState());
  pullSpaces();

  const unsubscribe = useProfiles.subscribe((state) => {
    pushProfilesToNative(state);
  });

  const onVisibility = () => {
    if (typeof document !== 'undefined' && document.visibilityState === 'visible') {
      useProfiles.getState().sweepExpired(Date.now());
      pullSpaces();
    }
  };
  if (typeof document !== 'undefined') {
    document.addEventListener('visibilitychange', onVisibility);
  }

  if (typeof window !== 'undefined') {
    (window as ProfilesWindow).__phantomProfiles = {
      setActive: (id: string) => useProfiles.getState().setActive(id),
      open: openProfilesSheet,
      back: () => {
        const back = useProfiles.getState().switchBack();
        return back ? `opened:${back.name}` : 'refused';
      },
    };
  }

  const stop = () => {
    unsubscribe();
    if (typeof document !== 'undefined') {
      document.removeEventListener('visibilitychange', onVisibility);
    }
    if (typeof window !== 'undefined') {
      delete (window as ProfilesWindow).__phantomProfiles;
    }
    if (activeStop === stop) {
      activeStop = null;
    }
  };

  activeStop = stop;
  return stop;
}

let lastLang = useSettingsStore.getState().values[LANGUAGE_SETTING_KEY];
useSettingsStore.subscribe((state) => {
  const nextLang = state.values[LANGUAGE_SETTING_KEY];
  if (nextLang === lastLang) return;
  lastLang = nextLang;
  const { activeId, profiles } = useProfiles.getState();
  let changed = false;
  const updated = profiles.map((p) => {
    if (!isDefaultKindName(p.kind, p.name)) return p;
    const label = kindLabel(p.kind);
    if (label === p.name) return p;
    changed = true;
    return { ...p, name: label };
  });
  if (changed) {
    useProfiles.setState({ activeId, profiles: updated });
    writeStorage({ activeId, profiles: updated, previousId: useProfiles.getState().previousId });
  }
  // Підписи вкладок нативної смуги печуться в payload мовою на мить пуша.
  if (activeStop !== null) pushProfilesToNative();
});
