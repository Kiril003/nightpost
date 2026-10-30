/**
 * Які подарунки людина вже відкрила на цьому пристрої: рух відкриття — один раз.
 * Памʼять лише зручність: немає сховища — подарунок просто знову запакований.
 */
const KEY = 'np.gifts.opened';
const CAP = 500;

let mem: string[] | null = null;

const read = (): string[] => {
  if (mem) return mem;
  try {
    const raw = JSON.parse(localStorage.getItem(KEY) ?? '[]') as unknown;
    mem = Array.isArray(raw) ? raw.filter((x): x is string => typeof x === 'string').slice(-CAP) : [];
  } catch {
    mem = [];
  }
  return mem;
};

export const giftOpened = {
  has: (id: string): boolean => read().includes(id),
  add(id: string): void {
    const list = read().filter((x) => x !== id);
    list.push(id);
    mem = list.slice(-CAP);
    try {
      localStorage.setItem(KEY, JSON.stringify(mem));
    } catch {
      /* без сховища — лише на цей запуск */
    }
  },
  reset(): void {
    mem = null;
  },
};
