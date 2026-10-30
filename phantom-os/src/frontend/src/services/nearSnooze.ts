import { listenLiving, useLiving } from './livingPulse';
import {
  NEAR_TTL_MS,
  canSnoozeNear,
  dayKey,
  isMeeting,
  nearPeerOf,
  nearTick,
  type LetterReminder,
  type NearSnoozeIO,
} from './nearSnoozeCore';
import { notifyRemindersChanged } from './remindersBus';

export {
  NEAR_TTL_MS,
  canSnoozeNear,
  dayKey,
  isMeeting,
  nearPeerOf,
  nearTick,
  type LetterReminder,
  type NearSnoozeIO,
};

let seen: Record<string, boolean> = {};
let unsub: (() => void) | null = null;
let release: (() => void) | null = null;
let activeIo: NearSnoozeIO | null = null;
let defaultIo: NearSnoozeIO | null = null;

function sameLanSeen(
  a: Record<string, { lanSeenAtMs?: number | null }> | undefined,
  b: Record<string, { lanSeenAtMs?: number | null }> | undefined,
): boolean {
  if (a === b) return true;
  if (!a || !b) return false;
  const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
  for (const k of keys) {
    if ((a[k]?.lanSeenAtMs ?? null) !== (b[k]?.lanSeenAtMs ?? null)) {
      return false;
    }
  }
  return true;
}

export function setNearSnoozeIO(io: NearSnoozeIO): void {
  defaultIo = io;
}

export function disarmNearSnooze(): void {
  const u = unsub;
  const r = release;
  unsub = null;
  release = null;
  seen = {};
  activeIo = null;
  u?.();
  r?.();
}

function tick(): void {
  const io = activeIo;
  if (!io) return;
  const before = io.read();
  if (!before.some((r) => r.trigger === 'near')) {
    disarmNearSnooze();
    return;
  }
  const { pulse, faces } = useLiving.getState();
  const res = nearTick(before, seen, pulse, faces, Date.now());
  seen = res.seen;
  const changed = res.list.length !== before.length || res.ring.length > 0;
  if (changed) {
    io.write(res.list);
  }
  for (const item of res.ring) {
    io.ring(item);
  }
  if (changed) {
    notifyRemindersChanged();
  }
  if (!res.list.some((r) => r.trigger === 'near')) {
    disarmNearSnooze();
  }
}

export function armNearSnooze(io?: NearSnoozeIO): void {
  if (io) {
    activeIo = io;
  } else if (!activeIo) {
    activeIo = defaultIo;
  }
  const currentIo = activeIo;
  if (!currentIo) return;
  if (!currentIo.read().some((r) => r.trigger === 'near')) {
    disarmNearSnooze();
    return;
  }
  if (!unsub) {
    release = listenLiving();
    unsub = useLiving.subscribe((state, prevState) => {
      if (state.pulse === prevState?.pulse && sameLanSeen(state.faces, prevState?.faces)) return;
      tick();
    });
  }
  tick();
}
