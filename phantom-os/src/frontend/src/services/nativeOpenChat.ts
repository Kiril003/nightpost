/**
 * Телефон просить відкрити конкретну розмову (тап по сповіщенню PHANTOM).
 * Прохання приходить двома дорогами: поштовхом `window.__phantomOpenChat`,
 * коли сторінка вже жива, і запитом `/messenger/self/open` при старті, коли
 * тап розбудив застосунок. Розмова відкривається, щойно вона є в списку.
 */
import { request } from './api';
import { useMessengerStore } from '../stores/messengerStore';
import { ownable, profileOf } from '../stores/chatProfileStore';
import { useProfiles } from '../stores/profilesStore';
import { useUIStore } from '../stores/uiStore';
import { tNow } from '../components/chat/tNow';

type Win = { __phantomOpenChat?: (id: string) => boolean };

/** Скільки чекати після завантаження списку, поки розмова зʼявиться: пізніше стрибок був би несподіваним. */
export const PENDING_OPEN_MS = 10_000;

function enterOwnerOf(chat: Parameters<typeof ownable>[0] & { id: string }): void {
  if (!ownable(chat)) return;
  const owner = profileOf(chat);
  const ps = useProfiles.getState();
  if (!owner || owner === ps.activeId || !ps.setActive(owner)) return;
  const name = ps.profiles.find((p) => p.id === owner)?.name ?? '';
  useUIStore.getState().toast({ kind: 'info', message: tNow('profileSwitch.youreIn', { name }) });
}

export function installNativeOpenChat(): () => void {
  let pending: { id: string; at: number | null; from: string } | null = null;

  const tryOpen = (id: string): boolean => {
    const st = useMessengerStore.getState();
    if (!st.chats.some((c) => c.id === id)) {
      pending = { id, at: st.hydrated ? Date.now() : null, from: st.activeChatId };
      return false;
    }
    pending = null;
    enterOwnerOf(st.chats.find((c) => c.id === id)!);
    st.setActiveChat(id);
    return true;
  };

  (window as unknown as Win).__phantomOpenChat = (id: string) => tryOpen(id);
  const unsubscribe = useMessengerStore.subscribe((s) => {
    if (!pending) return;
    if (pending.at === null && s.hydrated) pending.at = Date.now();
    // Прохання застаріло або людина вже пішла в іншу розмову — не перекидаємо її.
    if ((pending.at !== null && Date.now() - pending.at > PENDING_OPEN_MS) || s.activeChatId !== pending.from) {
      pending = null;
      return;
    }
    if (s.chats.some((c) => c.id === pending!.id)) tryOpen(pending.id);
  });

  if ((window as unknown as { PhantomBridge?: unknown }).PhantomBridge) {
    request<{ chat_id: string | null }>('GET', '/messenger/self/open')
      .then((r) => {
        if (r?.chat_id) tryOpen(r.chat_id);
      })
      .catch(() => undefined);
  }

  return () => {
    unsubscribe();
    delete (window as unknown as Win).__phantomOpenChat;
  };
}
