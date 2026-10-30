import { create } from 'zustand';
import type { Chat } from '../../types/messenger';
import { isNodeChat, isSavedNotes } from '../../components/messenger/chatListFormat';
import type { Profile } from '../../stores/profilesStore';

export const INBOX_KEY = 'np.bizinbox.v1';
export const INBOX_STATUSES = ['new', 'progress', 'done'] as const;
export type InboxStatus = (typeof INBOX_STATUSES)[number];

type Entry = { status: InboxStatus; at: number };

const load = (): Record<string, Entry> => {
  try {
    const raw = JSON.parse(localStorage.getItem(INBOX_KEY) ?? '{}') as Record<string, Partial<Entry>>;
    const out: Record<string, Entry> = {};
    for (const [id, e] of Object.entries(raw)) {
      if (e && (INBOX_STATUSES as readonly unknown[]).includes(e.status) && typeof e.at === 'number') {
        out[id] = { status: e.status as InboxStatus, at: e.at };
      }
    }
    return out;
  } catch {
    return {};
  }
};

const save = (entries: Record<string, Entry>): void => {
  try {
    localStorage.setItem(INBOX_KEY, JSON.stringify(entries));
  } catch {
    /* сховище недоступне — стан живе до перезапуску */
  }
};

interface InboxState {
  entries: Record<string, Entry>;
  set: (chatId: string, status: InboxStatus) => void;
}

export const useBizInbox = create<InboxState>((set, get) => ({
  entries: load(),
  set: (chatId, status) => {
    const entries = { ...get().entries, [chatId]: { status, at: Date.now() } };
    save(entries);
    set({ entries });
  },
}));

/** Лише кола профілю: закріплена чи «вузлова» розмова з другом клієнтом не стає. */
function inBusinessCircles(c: Chat, profile: Pick<Profile, 'passGroups'> | null): boolean {
  if (!profile) return false;
  const circles = c.circles && c.circles.length > 0 ? c.circles : [c.circle];
  return circles.some((x) => profile.passGroups.includes(x));
}

/** Клієнти — розмови вдвох у колах профілю; вузол, «Збережене» і групи сюди не входять. */
export function clientChats(chats: readonly Chat[], profile: Pick<Profile, 'kind' | 'passGroups'> | null): Chat[] {
  return chats.filter(
    (c) =>
      (c.type === 'direct' || c.type === 'dm' || c.type === 'p2p') &&
      !isSavedNotes(c) &&
      !isNodeChat(c) &&
      !c.archived &&
      inBusinessCircles(c, profile),
  );
}

/** Без позначки: непрочитане — нове, решта — готово. Нове від клієнта після позначки знову «нове». */
export function statusOfChat(chat: Pick<Chat, 'unreadCount' | 'lastAt'>, entry: Entry | undefined): InboxStatus {
  const lastAt = chat.lastAt ? Date.parse(chat.lastAt) : 0;
  if (!entry) return 'new';
  if (entry.status !== 'new' && chat.unreadCount > 0 && lastAt > entry.at) return 'new';
  return entry.status;
}

export const QUICK_REPLIES = ['ready5', 'late', 'soldOut', 'tableFree'] as const;
export type QuickReply = (typeof QUICK_REPLIES)[number];
