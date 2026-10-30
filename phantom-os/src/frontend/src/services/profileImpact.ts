import type { Chat } from '../types/messenger';
import type { Profile } from '../stores/profilesStore';
import { ownable, ownerOf, useChatProfiles } from '../stores/chatProfileStore';

/** Що станеться з розмовами, якщо прибрати профіль: вони не видаляються, а йдуть в «Особисте». */
export function removalImpact(
  chats: Array<Pick<Chat, 'id' | 'type' | 'circle' | 'archived'>>,
  profiles: Profile[],
  id: string,
): { inside: number; orphaned: number } {
  if (!profiles.some((p) => p.id === id)) return { inside: 0, orphaned: 0 };
  const map = useChatProfiles.getState().map;
  let inside = 0;
  for (const c of chats) {
    if (!c.archived && ownable(c) && ownerOf(c.id, map, profiles) === id) inside += 1;
  }
  return { inside, orphaned: profiles.length > 1 ? 0 : inside };
}
