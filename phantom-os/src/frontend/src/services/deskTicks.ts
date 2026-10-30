const KEY = 'phantom_desk_ticks';
const LIMIT = 500;

export function loadDeskTicks(): Set<string> {
  try {
    const raw = localStorage.getItem(KEY);
    const parsed: unknown = raw ? JSON.parse(raw) : [];
    return new Set(Array.isArray(parsed) ? parsed.filter((x): x is string => typeof x === 'string') : []);
  } catch {
    return new Set();
  }
}

export function addDeskTick(prev: ReadonlySet<string>, messageId: string): Set<string> {
  const next = new Set(prev);
  next.add(messageId);
  try {
    localStorage.setItem(KEY, JSON.stringify([...next].slice(-LIMIT)));
  } catch {
    /* без сховища позначка живе до закриття */
  }
  return next;
}
