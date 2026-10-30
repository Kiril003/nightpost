/**
 * Ім'я людини так, як його дав власник телефона. Перейменований контакт
 * мусить бути одним іменем усюди: у підписі в групі, на картці зустрічі.
 */
import type { Chat } from '../types/messenger';
import { displayTitle, isUnnamed } from '../components/messenger/chatListFormat';
import { useMessengerStore } from '../stores/messengerStore';

const PERSON = new Set<Chat['type']>(['dm', 'direct', 'p2p']);

export function localNameOf(chats: Chat[], peerId: string | null | undefined, fallback: string): string {
  if (!peerId) return fallback;
  const dm = chats.find((c) => c.peerNodeId === peerId && PERSON.has(c.type));
  const title = dm ? displayTitle(dm.title).trim() : '';
  if (title && !isUnnamed(title)) return title;
  for (const c of chats) {
    const m = c.members?.find((x) => x.id === peerId && x.name);
    if (m) return m.name;
  }
  return fallback;
}

export const useLocalName = (peerId: string | null | undefined, fallback: string): string =>
  useMessengerStore((s) => localNameOf(s.chats, peerId, fallback));
