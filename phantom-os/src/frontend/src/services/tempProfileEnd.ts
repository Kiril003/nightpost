/**
 * Кінець профілю «На час»: його розмови йдуть у місцевий архів, а не
 * осідають у «Особистому». Запис власника лишається, тож розмова з архіву
 * не губиться; повернути її може сама людина.
 */
import { onTempProfilesEnded, useProfiles, type EndedProfile } from '../stores/profilesStore';
import { useChatProfiles } from '../stores/chatProfileStore';
import { useMessengerStore } from '../stores/messengerStore';
import { chatArchive } from './chatArchive';

const ENDED_KEY = 'np.tempended.v1';
const ENDED_CAP = 50;

function readEnded(): EndedProfile[] {
  try {
    const raw = JSON.parse(localStorage.getItem(ENDED_KEY) ?? '[]');
    return Array.isArray(raw) ? raw.filter((e) => e && typeof e.id === 'string' && typeof e.name === 'string') : [];
  } catch {
    return [];
  }
}

function rememberEnded(ended: readonly EndedProfile[]): void {
  const ids = new Set(ended.map((e) => e.id));
  const next = [...readEnded().filter((e) => !ids.has(e.id)), ...ended].slice(-ENDED_CAP);
  try {
    localStorage.setItem(ENDED_KEY, JSON.stringify(next));
  } catch {
    /* storage off: the archive keeps the chats, only the label is lost */
  }
}

/** Назва скінченого «На час» за id, щоб архів підписав його розмови. */
export function endedProfileNames(): Record<string, string> {
  return Object.fromEntries(readEnded().map((e) => [e.id, e.name]));
}

export function archiveChatsOf(profiles: readonly EndedProfile[]): string[] {
  rememberEnded(profiles);
  const ended = new Set(profiles.map((p) => p.id));
  const map = useChatProfiles.getState().map;
  const chatIds = Object.keys(map).filter((chatId) => ended.has(map[chatId]));
  if (!chatIds.length) return [];
  for (const chatId of chatIds) chatArchive.set(chatId, true);
  const hide = new Set(chatIds);
  useMessengerStore.setState((s) => ({
    chats: s.chats.map((c) => (hide.has(c.id) ? { ...c, archived: true } : c)),
  }));
  return chatIds;
}

/** Підписка на кінець і прохід раз на хвилину: строк спливає й при відкритому застосунку. */
export function installTempProfileEnd(): () => void {
  const off = onTempProfilesEnded(archiveChatsOf);
  const timer = setInterval(() => useProfiles.getState().sweepExpired(Date.now()), 60_000);
  return () => {
    off();
    clearInterval(timer);
  };
}
