/**
 * «Переглянуто» — пам'ять цього телефона: {id моменту: до коли тримати}.
 * Вузол може не прийняти POST /seen або віддати старий список; мітка лишається.
 */
export const SEEN_KEY = 'np.momentseen.v1';
const KEEP_MS = 30 * 86_400_000;
const CAP = 1000;

type Ledger = Record<string, number>;

function read(): Ledger {
  try {
    const o: unknown = JSON.parse(localStorage.getItem(SEEN_KEY) ?? '{}');
    if (!o || typeof o !== 'object' || Array.isArray(o)) return {};
    return Object.fromEntries(Object.entries(o as Record<string, unknown>).filter(([, v]) => typeof v === 'number')) as Ledger;
  } catch {
    return {};
  }
}

function write(l: Ledger): void {
  try {
    localStorage.setItem(SEEN_KEY, JSON.stringify(l));
  } catch {
    /* приватне вікно чи повна сховка: мітка живе до кінця сесії в списку */
  }
}

const fresh = (l: Ledger, now: number): Ledger =>
  Object.fromEntries(
    Object.entries(l)
      .filter(([, until]) => until > now)
      .sort((a, b) => b[1] - a[1])
      .slice(0, CAP),
  );

export function rememberSeen(id: string, untilMs: number, now = Date.now()): void {
  const l = fresh(read(), now);
  l[id] = untilMs > now ? untilMs : now + KEEP_MS;
  write(l);
}

export function seenLocally(now = Date.now()): (id: string) => boolean {
  const l = read();
  return (id) => (l[id] ?? 0) > now;
}
