import type { Message } from '../types/messenger';
import { useMessengerStore } from '../stores/messengerStore';
import { cardFieldOf } from './composerCards';
import { cardKey, pollOf, pollPatch, type Poll } from '../components/messenger/cards/cardModel';

export interface DayItem {
  start: number;
  end: number;
}

export const SLOT_MIN = 60;

/** Попередні позначки вільних слотів без жодного запису в стор чи лист. */
export function preTick(
  options: readonly string[],
  dayItems: readonly DayItem[] | null,
  slotMin = SLOT_MIN,
): boolean[] | null {
  if (dayItems === null) return null;
  const durationMs = slotMin * 60_000;
  return options.map((iso) => {
    const start = Date.parse(iso);
    if (!Number.isFinite(start)) return false;
    const end = start + durationMs;
    return !dayItems.some((item) => start < item.end && item.start < end);
  });
}

export function votesFor(poll: Poll, picks: readonly boolean[], meId: string): Poll {
  return {
    ...poll,
    options: poll.options.map((o, i) => {
      const without = o.voters.filter((v) => v !== meId);
      return {
        ...o,
        voters: picks[i] ? [...without, meId] : without,
      };
    }),
  };
}

export function confirm(msg: Message, picks: readonly boolean[], meId: string): void {
  const fields = pollPatch(msg, votesFor(pollOf(msg)!, picks, meId), meId);
  const store = useMessengerStore.getState();
  const next = { ...msg, ...fields } as Message;
  const field = cardFieldOf(msg.type);
  if (field && cardKey(next)) {
    void store.sendCard(msg.type, next[field]);
    return;
  }
  store.updateMessage(msg.id, fields);
}
