export const REMINDERS_CHANGED = 'phantom:reminders-changed';
export const REMINDERS_OPEN = 'phantom:reminders-open';

const OPEN_TTL_MS = 5000;
let pendingOpenAt = 0;

export function notifyRemindersChanged(): void {
  if (typeof window !== 'undefined') window.dispatchEvent(new Event(REMINDERS_CHANGED));
}

export const openRemindersSheet = (): void => {
  pendingOpenAt = Date.now();
  if (typeof window !== 'undefined') window.dispatchEvent(new Event(REMINDERS_OPEN));
};

export const takeRemindersOpen = (): boolean => {
  const was = pendingOpenAt > 0 && Date.now() - pendingOpenAt <= OPEN_TTL_MS;
  pendingOpenAt = 0;
  return was;
};

if (typeof window !== 'undefined') {
  (window as unknown as { __phantomRemindersChanged?: () => void }).__phantomRemindersChanged = notifyRemindersChanged;
}
