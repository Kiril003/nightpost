import type { Chat, Message } from '../types/messenger';
import { RING_HAPTIC, haptic, type HapticKind, type RingName } from '../utils/haptic';
import { soundFx } from '../utils/messengerSound';
import { hushAllows } from './chatHush';

export type RingSound = 'receive' | 'chime' | 'tap';

export interface RingSignal {
  sound: RingSound;
  haptic: HapticKind | null;
}

const ORDER: RingName[] = ['personal', 'close', 'friends', 'social', 'work'];

const SOUND: Record<RingName, RingSound> = {
  personal: 'chime',
  close: 'chime',
  friends: 'receive',
  social: 'receive',
  work: 'tap',
};

const DEFAULT_SIGNAL: RingSignal = { sound: 'receive', haptic: null };

export const ringOf = (chat: Chat): RingName | null => {
  const names = [chat.circle, ...(Array.isArray(chat.circles) ? chat.circles : [])];
  let best = -1;
  for (const n of names) {
    const i = ORDER.indexOf(n as RingName);
    if (i >= 0 && (best < 0 || i < best)) best = i;
  }
  return best < 0 ? null : ORDER[best];
};

export const ringSignal = (chat: Chat): RingSignal => {
  const ring = ringOf(chat);
  if (!ring) return DEFAULT_SIGNAL;
  return { sound: SOUND[ring], haptic: RING_HAPTIC[ring] };
};

/** Вхідне: тиша з chatHush має першість; без кола — як було (playReceive). */
export const signalIncoming = (
  chat: Chat,
  msg: Message,
  nowMs: number,
  me: { id: string; name: string; handle?: string },
): boolean => {
  if (!hushAllows(chat, msg, nowMs, me)) return false;
  const sig = ringSignal(chat);
  if (!ringOf(chat)) {
    soundFx.playReceive();
    return true;
  }
  const opts = { haptic: false };
  if (sig.sound === 'chime') soundFx.playChime(opts);
  else if (sig.sound === 'tap') soundFx.playTap(opts);
  else soundFx.playReceive(opts);
  if (sig.haptic) haptic(sig.haptic);
  return true;
};
