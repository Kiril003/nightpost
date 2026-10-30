import { create } from 'zustand';
import type { Chat } from '../types/messenger';
import { fallbackProfile, PASS_NONE, pushProfilesToNative, useProfiles, type Profile, type ProfileKind } from './profilesStore';
import { chatKind, isNodeChat, isSavedNotes } from '../components/messenger/chatListFormat';
import { isRequest } from '../services/chatFilters';
import { isClassChat } from '../services/edu/eduModel';

/** Чия розмова: кожна належить рівно одному профілю (SPEC-2810 §2). */
export const CHAT_PROFILE_KEY = 'np.chatprofile.v1';

/** Міграція виносить із «Особистого» лише в ці роди; сімʼя, автор, поїздка, на час — ніколи (v4 §1.1). */
const AUTO_KINDS: readonly ProfileKind[] = ['work', 'business', 'study', 'dev', 'org'];

type Owned = Pick<Chat, 'id' | 'type' | 'circle'>;

export interface ChatProfileState {
  map: Record<string, string>;
  migratedAt: number | null;
  assign: (chatId: string, profileId: string) => void;
  onChatCreated: (chat: Pick<Chat, 'id'>) => void;
  remove: (profileId: string) => void;
  migrate: (chats: Chat[], meId: string, now?: number) => void;
}

const isPlainObject = (v: unknown): v is Record<string, unknown> =>
  v !== null && typeof v === 'object' && !Array.isArray(v);

/** Збережене й помічник — не розмова з людьми: вони в кожному профілі й нікому не належать. */
export const ownable = (chat: Owned): boolean => !isSavedNotes(chat) && !isNodeChat(chat);

export function loadChatProfiles(): { map: Record<string, string>; migratedAt: number | null } {
  const empty = { map: {}, migratedAt: null };
  try {
    const raw = typeof localStorage === 'undefined' ? null : localStorage.getItem(CHAT_PROFILE_KEY);
    if (!raw) return empty;
    const parsed: unknown = JSON.parse(raw);
    if (!isPlainObject(parsed) || parsed.v !== 1 || !isPlainObject(parsed.map)) return empty;
    const map: Record<string, string> = {};
    for (const [k, v] of Object.entries(parsed.map)) if (k && typeof v === 'string' && v) map[k] = v;
    const at = parsed.migratedAt;
    return { map, migratedAt: typeof at === 'number' && Number.isFinite(at) ? at : null };
  } catch {
    return empty;
  }
}

function write(map: Record<string, string>, migratedAt: number | null): void {
  try {
    localStorage.setItem(CHAT_PROFILE_KEY, JSON.stringify({ v: 1, map, migratedAt }));
  } catch {
    // Квота чи недоступне сховище: у памʼяті лишається.
  }
}

export function ownerOf(chatId: string, map: Record<string, string>, profiles: Profile[]): string {
  const id = map[chatId];
  if (id && profiles.some((p) => p.id === id)) return id;
  return profiles.length ? fallbackProfile(profiles).id : '';
}

export const profileOf = (chat: Pick<Chat, 'id'>): string =>
  ownerOf(chat.id, useChatProfiles.getState().map, useProfiles.getState().profiles);

const circlesOf = (chat: Pick<Chat, 'circle' | 'circles'>): string[] =>
  chat.circles && chat.circles.length ? chat.circles : chat.circle && chat.circle !== 'all' ? [chat.circle] : [];

const claims = (p: Profile): string[] => p.passGroups.filter((g) => g !== PASS_NONE);

/** SPEC §5: лише виключний збіг кіл з одним робочим профілем; інше — «Особисте». Наявні записи не чіпає. */
export function migrateChats(
  chats: Chat[],
  profiles: Profile[],
  map: Record<string, string>,
  meId: string,
): Record<string, string> {
  if (profiles.length === 0) return map;
  const home = fallbackProfile(profiles);
  const study = profiles.find((p) => p.kind === 'study');
  const homeClaims = claims(home);
  const out = { ...map };
  for (const chat of chats) {
    if (!ownable(chat) || out[chat.id]) continue;
    if (study && chat.type === 'group' && isClassChat(chat, meId)) {
      out[chat.id] = study.id;
      continue;
    }
    const circles = circlesOf(chat);
    const hit = profiles.filter((p) => p.id !== home.id && claims(p).some((g) => circles.includes(g)));
    const only = hit.length === 1 && AUTO_KINDS.includes(hit[0].kind) && !homeClaims.some((g) => circles.includes(g));
    out[chat.id] = only ? hit[0].id : home.id;
  }
  return out;
}

export const useChatProfiles = create<ChatProfileState>((set, get) => {
  const commit = (map: Record<string, string>, migratedAt: number | null) => {
    set({ map, migratedAt });
    write(map, migratedAt);
  };
  return {
    ...loadChatProfiles(),
    assign: (chatId, profileId) => {
      const { map, migratedAt } = get();
      if (!chatId || !profileId || map[chatId] === profileId) return;
      commit({ ...map, [chatId]: profileId }, migratedAt);
    },
    onChatCreated: (chat) => {
      if (get().map[chat.id]) return;
      get().assign(chat.id, useProfiles.getState().activeId);
    },
    remove: (profileId) => {
      const { map, migratedAt } = get();
      if (!Object.values(map).includes(profileId)) return;
      commit(Object.fromEntries(Object.entries(map).filter(([, v]) => v !== profileId)), migratedAt);
    },
    migrate: (chats, meId, now = Date.now()) => {
      const { map, migratedAt } = get();
      if (migratedAt !== null) return;
      commit(migrateChats(chats, useProfiles.getState().profiles, map, meId), now);
    },
  };
});

const groupAge = (chat: Chat): number => {
  const times = [chat.messages?.[0]?.sentAt, chat.lastAt].map((t) => (t ? Date.parse(t) : NaN)).filter(Number.isFinite);
  return times.length ? Math.min(...times) : Infinity;
};

/**
 * Мапи мосту v2. Ключі — як їх бачить PeerNotifications: 1:1 — вузол людини
 * (`notice.fromId`), група — id групи (`notice.groupId`, той самий, що `Chat.id`).
 */
export function bridgeMaps(
  chats: Chat[],
  map: Record<string, string>,
  profiles: Profile[],
  meId: string,
): { peerProfile: Record<string, string>; chatProfile: Record<string, string> } {
  const peerProfile: Record<string, string> = {};
  const chatProfile: Record<string, string> = {};
  if (profiles.length === 0) return { peerProfile, chatProfile };
  const groups: Chat[] = [];
  for (const chat of chats) {
    if (!ownable(chat)) continue;
    const owner = ownerOf(chat.id, map, profiles);
    if (chatKind(chat) === 'person') {
      const peer = chat.peerNodeId || chat.id;
      chatProfile[peer] = owner;
      peerProfile[peer] = owner;
    } else {
      chatProfile[chat.id] = owner;
      groups.push(chat);
    }
  }
  groups.sort((a, b) => groupAge(a) - groupAge(b) || a.id.localeCompare(b.id));
  for (const g of groups) {
    const owner = chatProfile[g.id];
    for (const m of g.members ?? []) {
      if (m.id && m.id !== meId && !m.isMe && !(m.id in peerProfile)) peerProfile[m.id] = owner;
    }
  }
  return { peerProfile, chatProfile };
}

let seen: { chats: Chat[]; meId: string } = { chats: [], meId: '' };

export const chatProfileMaps = (profiles: Profile[]) =>
  bridgeMaps(seen.chats, useChatProfiles.getState().map, profiles, seen.meId);

type ChatSource = {
  getState: () => {
    chats: Chat[];
    hydrated: boolean;
    currentUser?: { id?: string } | null;
    /** Склад групи з мосту: без нього людей із невідкритих груп міст не знає. */
    loadGroupMembers?: (chatId: string) => Promise<boolean>;
  };
  subscribe: (fn: () => void) => () => void;
};

/** Групи, чий склад уже питали в цьому запуску: відмова не повторюється по колу. */
const askedMembers = new Set<string>();

let users = 0;
let halt: (() => void) | null = null;

/**
 * Тихо, без листів і кнопок: перша повна стрічка — одноразова міграція,
 * далі нова розмова йде в активний профіль, а перше слово чужого — в «Особисте».
 */
export function startChatProfiles(source: ChatSource): () => void {
  users += 1;
  if (!halt) {
    let sentKey = '';
    let alive = true;
    let filling = false;
    // По одній групі за раз: міст не захлинається, а кожна відповідь одразу оновлює мапу людей.
    const fillMembers = async () => {
      if (filling) return;
      filling = true;
      try {
        for (;;) {
          const s = source.getState();
          const load = s.loadGroupMembers;
          if (!alive || !load) return;
          const next = s.chats.find(
            (c) => c.type === 'group' && ownable(c) && !c.members?.length && !askedMembers.has(c.id),
          );
          if (!next) return;
          askedMembers.add(next.id);
          await load(next.id).catch(() => false);
        }
      } finally {
        filling = false;
      }
    };
    const push = () => {
      const key = JSON.stringify(chatProfileMaps(useProfiles.getState().profiles));
      if (key === sentKey) return;
      sentKey = key;
      pushProfilesToNative();
    };
    const sync = () => {
      const s = source.getState();
      seen = { chats: s.chats, meId: s.currentUser?.id ?? '' };
      const store = useChatProfiles.getState();
      if (s.hydrated) {
        if (store.migratedAt === null) {
          // Порожня перша стрічка (міст ще не готовий) міграцію не витрачає: інакше
          // все старе потім пішло б в активний профіль як «нове».
          if (s.chats.some(ownable)) store.migrate(s.chats, seen.meId);
        } else {
          const { profiles, activeId: active } = useProfiles.getState();
          const fresh = profiles.length ? s.chats.filter((c) => ownable(c) && !store.map[c.id]) : [];
          if (fresh.length) {
            const home = fallbackProfile(profiles).id;
            const next = { ...store.map };
            for (const c of fresh) next[c.id] = isRequest(c) ? home : active;
            useChatProfiles.setState({ map: next });
            write(next, store.migratedAt);
          }
        }
      }
      push();
      if (s.hydrated) void fillMembers();
    };
    let prevChats: Chat[] | null = null;
    let prevHydrated = false;
    const offChats = source.subscribe(() => {
      const s = source.getState();
      if (s.chats === prevChats && s.hydrated === prevHydrated) return;
      prevChats = s.chats;
      prevHydrated = s.hydrated;
      sync();
    });
    const offMap = useChatProfiles.subscribe(push);
    const offProfiles = useProfiles.subscribe((s, prev) => {
      const left = prev.profiles.filter((p) => !s.profiles.some((q) => q.id === p.id));
      for (const p of left) useChatProfiles.getState().remove(p.id);
    });
    sync();
    halt = () => {
      alive = false;
      askedMembers.clear();
      offChats();
      offMap();
      offProfiles();
      seen = { chats: [], meId: '' };
    };
  }
  let stopped = false;
  return () => {
    if (stopped) return;
    stopped = true;
    users -= 1;
    if (users === 0 && halt) {
      halt();
      halt = null;
    }
  };
}
