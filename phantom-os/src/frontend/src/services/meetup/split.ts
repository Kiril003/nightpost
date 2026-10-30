/**
 * Ділення рахунку — цілими одиницями токена, без float.
 *
 * USDC рахується в мікро (6 знаків), SOL — у лампортах (9). Ціну страви ділять
 * порівну ті, хто її позначив (`dish:<id>`); залишок одиниць іде по одній
 * першим авторам у порядку зростання id — та сама купа позначок дає ту саму
 * частку на кожному апараті. Страва без автора — «нічия»: вона лежить окремо
 * і НЕ розкидається на всіх (`docs/scene/MEETUP_CONTRACT.md` §3).
 */

import { TOKEN_DECIMALS, isDecimal, type BillData, type BillItem, type BillToken, type CardMarks } from '../../types/meetup';
import { readCardMarks } from './marks';
import { currentLocale } from '../../components/chat/tNow';

/** «7.5» → 7 500 000n для USDC. Більше знаків, ніж має токен, — відмова, не округлення. */
export function toUnits(decimal: string, token: BillToken): bigint {
  if (!isDecimal(decimal)) throw new Error(`Not an amount: «${decimal}»`);
  const places = TOKEN_DECIMALS[token];
  const [whole, frac = ''] = decimal.split('.');
  if (frac.length > places) throw new Error(`${token} has ${places} decimal places, got ${frac.length}`);
  return BigInt(whole) * 10n ** BigInt(places) + BigInt(frac.padEnd(places, '0') || '0');
}

/** Точний десятковий рядок без хвостових нулів — для переказу. */
export function fromUnits(units: bigint, token: BillToken): string {
  const places = TOKEN_DECIMALS[token];
  const neg = units < 0n;
  const abs = neg ? -units : units;
  const base = 10n ** BigInt(places);
  const whole = abs / base;
  const frac = (abs % base).toString().padStart(places, '0').replace(/0+$/, '');
  return `${neg ? '-' : ''}${whole}${frac ? `.${frac}` : ''}`;
}

/** На екран: USDC щонайменше з копійками («18.50 USDC»), SOL — як є. */
export function formatUnits(units: bigint, token: BillToken): string {
  const s = fromUnits(units, token);
  if (token !== 'USDC') return s;
  const [w, f = ''] = s.split('.');
  return `${w}.${f.padEnd(2, '0')}`;
}

/** Для очей: десяткова кома для uk/cs, крапка для en. */
export function displayUnits(units: bigint, token: BillToken): string {
  const s = formatUnits(units, token);
  return currentLocale() === 'en' ? s : s.replace('.', ',');
}

/** Сума з монетою одним нерозривним шматком: «18,00 USDC». */
export function formatAmount(units: bigint, token: BillToken): string {
  return `${displayUnits(units, token)}\u00A0${token}`;
}

export interface ShareLine {
  itemId: string;
  units: bigint;
  /** Скільки людей ділять цю страву. */
  with: number;
}

export interface Share {
  id: string;
  /** null — автора немає серед людей рахунку (позначив, але не в списку). */
  name: string | null;
  units: bigint;
  items: ShareLine[];
}

export interface BillSplit {
  token: BillToken;
  /** Сума всіх страв рахунку. */
  total: bigint;
  /** Сума страв, які хтось позначив, = сума часток. */
  claimedTotal: bigint;
  /** Люди рахунку в їхньому порядку, далі — сторонні автори за id. */
  shares: Share[];
  unclaimed: BillItem[];
  unclaimedTotal: bigint;
}

const byId = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);

/** Поділити [units] на [holders]: порівну, залишок — першим за id. */
function divide(units: bigint, holders: readonly string[]): Map<string, bigint> {
  const sorted = [...new Set(holders)].sort(byId);
  const out = new Map<string, bigint>();
  if (sorted.length === 0) return out;
  const k = BigInt(sorted.length);
  const base = units / k;
  const rem = Number(units % k);
  sorted.forEach((id, i) => out.set(id, base + (i < rem ? 1n : 0n)));
  return out;
}

function emptyShares(bill: BillData): Map<string, Share> {
  const m = new Map<string, Share>();
  for (const p of bill.people) m.set(p.id, { id: p.id, name: p.name, units: 0n, items: [] });
  return m;
}

function ordered(bill: BillData, shares: Map<string, Share>): Share[] {
  const known = bill.people.map((p) => shares.get(p.id)!);
  const strangers = [...shares.values()].filter((s) => !bill.people.some((p) => p.id === s.id)).sort((a, b) => byId(a.id, b.id));
  return [...known, ...strangers];
}

/** Частки за позначеними стравами (`dish:<id>` у згорнутих позначках картки). */
export function splitBill(bill: BillData, marks: CardMarks | null | undefined): BillSplit {
  if (bill.asks) {
    const shares = emptyShares(bill);
    let total = 0n;
    for (const ask of bill.asks) {
      const units = toUnits(ask.amount, bill.token);
      total += units;
      const s = shares.get(ask.id);
      if (s) s.units += units;
    }
    return { token: bill.token, total, claimedTotal: total, shares: ordered(bill, shares), unclaimed: [], unclaimedTotal: 0n };
  }
  const { dishes } = readCardMarks(marks);
  const shares = emptyShares(bill);
  let total = 0n;
  let claimedTotal = 0n;
  const unclaimed: BillItem[] = [];
  let unclaimedTotal = 0n;
  for (const item of bill.items) {
    const price = toUnits(item.price, bill.token);
    total += price;
    const holders = dishes.get(item.id) ?? [];
    if (holders.length === 0) {
      unclaimed.push(item);
      unclaimedTotal += price;
      continue;
    }
    claimedTotal += price;
    const parts = divide(price, holders);
    for (const [id, units] of parts) {
      let s = shares.get(id);
      if (!s) {
        s = { id, name: null, units: 0n, items: [] };
        shares.set(id, s);
      }
      s.units += units;
      s.items.push({ itemId: item.id, units, with: parts.size });
    }
  }
  return { token: bill.token, total, claimedTotal, shares: ordered(bill, shares), unclaimed, unclaimedTotal };
}

/** Уся сума порівну на людей рахунку — для «розділи порівну». */
export function splitEqual(bill: BillData): BillSplit {
  const total = bill.items.reduce((acc, i) => acc + toUnits(i.price, bill.token), 0n);
  const shares = emptyShares(bill);
  for (const [id, units] of divide(total, bill.people.map((p) => p.id))) shares.get(id)!.units = units;
  return { token: bill.token, total, claimedTotal: total, shares: ordered(bill, shares), unclaimed: [], unclaimedTotal: 0n };
}

/** Хто за столом: позначив страву цього рахунку або `here:1`, за id. */
export function tableOf(bill: BillData, marks: CardMarks | null | undefined): string[] {
  if (bill.asks) return [];
  const { dishes } = readCardMarks(marks);
  const ids = new Set<string>();
  for (const item of bill.items) {
    for (const id of dishes.get(item.id) ?? []) ids.add(id);
  }
  for (const id of marks?.['here:1'] ?? []) {
    if (id) ids.add(id);
  }
  return [...ids].sort(byId);
}

/** Розкласти нічиї страви порівну між `among` (залишок за id по кожній страві). */
export function spreadUnclaimed(
  bill: BillData,
  marks: CardMarks | null | undefined,
  among: readonly string[],
): BillSplit {
  const base = splitBill(bill, marks);
  const holders = [...new Set(among)];
  if (holders.length === 0 || base.unclaimed.length === 0) return base;
  const shares = emptyShares(bill);
  for (const s of base.shares) {
    shares.set(s.id, { id: s.id, name: s.name, units: s.units, items: [...s.items] });
  }
  for (const item of base.unclaimed) {
    const price = toUnits(item.price, bill.token);
    const parts = divide(price, holders);
    for (const [id, units] of parts) {
      let s = shares.get(id);
      if (!s) {
        s = { id, name: null, units: 0n, items: [] };
        shares.set(id, s);
      }
      s.units += units;
      s.items.push({ itemId: item.id, units, with: parts.size });
    }
  }
  return {
    token: bill.token,
    total: base.total,
    claimedTotal: base.total,
    shares: ordered(bill, shares),
    unclaimed: [],
    unclaimedTotal: 0n,
  };
}

/** Частки, однакові на кожному апараті: нічиє розкладено, лише якщо отримувач поставив `spread:1`. */
export function billSplitOf(bill: BillData, marks: CardMarks | null | undefined): BillSplit {
  const spread = bill.payee.kind === 'peer' && readCardMarks(marks).spread.has(bill.payee.id);
  return spread ? spreadUnclaimed(bill, marks, tableOf(bill, marks)) : splitBill(bill, marks);
}
