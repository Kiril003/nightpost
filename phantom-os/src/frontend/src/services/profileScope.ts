import type { Chat } from '../types/messenger';
import type { Profile } from '../stores/profilesStore';
import { ownable, ownerOf, profileOf } from '../stores/chatProfileStore';

type Scoped = Pick<Chat, 'id' | 'type' | 'circle'>;

/** Чи розмова в списку профілю: лише свої розмови (np.chatprofile.v1); Збережене й помічник — у кожному. */
export function inProfile(chat: Scoped, profile: Pick<Profile, 'id'> | null | undefined): boolean {
  if (!profile || !ownable(chat)) return true;
  return profileOf(chat) === profile.id;
}

export function splitByProfile<T extends Scoped>(chats: T[], profile: Pick<Profile, 'id'> | null | undefined): { inside: T[]; outside: T[] } {
  const inside: T[] = [];
  const outside: T[] = [];
  for (const c of chats) (inProfile(c, profile) ? inside : outside).push(c);
  return { inside, outside };
}

type Counted = Scoped & Pick<Chat, 'unreadCount' | 'archived' | 'muted'>;

/** Непрочитане за профілем-власником: свої розмови без тиші й архіву; Збережене й помічник — у кожному, тож ні в чий лік. */
export function unreadByProfile(chats: readonly Counted[], map: Record<string, string>, profiles: Profile[]): Record<string, number> {
  const out: Record<string, number> = {};
  for (const c of chats) {
    if (c.archived || c.muted || !(c.unreadCount > 0) || !ownable(c)) continue;
    const id = ownerOf(c.id, map, profiles);
    if (id) out[id] = (out[id] ?? 0) + c.unreadCount;
  }
  return out;
}
