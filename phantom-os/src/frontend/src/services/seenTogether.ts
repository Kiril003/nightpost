import { cancelledBy, readCardMarks } from './meetup/marks';
import { MEETUP_KIND, parseMeetupData } from '../types/meetup';
import type { Chat } from '../types/messenger';

export interface SeenTogether {
  count: number;
  lastAt: number;
}

/** Спільні зустрічі наживо: позначки here:1 обох, зведені по id плану з усіх копій на пристрої. */
export function seenTogether(chats: readonly Chat[], meId: string, peerId: string): SeenTogether | null {
  if (!meId || !peerId) return null;
  const plans = new Map<string, { at: number; here: Set<string>; cancelled: boolean }>();
  for (const chat of chats) {
    for (const m of chat.messages ?? []) {
      if (!m || m.isDeleted || m.type !== MEETUP_KIND) continue;
      const data = parseMeetupData(m.meetupData);
      if (!data) continue;
      const plan = plans.get(data.id) ?? { at: data.at, here: new Set<string>(), cancelled: false };
      if (cancelledBy(m.cardMarks, data.organizer)) plan.cancelled = true;
      for (const id of readCardMarks(m.cardMarks).here) plan.here.add(id);
      plans.set(data.id, plan);
    }
  }
  let count = 0;
  let lastAt = -Infinity;
  for (const plan of plans.values()) {
    if (plan.cancelled || !plan.here.has(meId) || !plan.here.has(peerId)) continue;
    count += 1;
    if (plan.at > lastAt) lastAt = plan.at;
  }
  return count > 0 ? { count, lastAt } : null;
}
