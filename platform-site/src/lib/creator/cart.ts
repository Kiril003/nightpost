/** Shop kit on a public page: the cart, what is left and the sum, with the server's own math (venue_requests._check_order). */
import { DECIMALS } from './booking.ts';

type B = { id?: string; kind: string; hidden?: boolean; data?: any };
export type Product = { id: string; name?: string; price?: unknown; stock?: unknown };
export type Zone = { id: string; area: string; fee: string; min?: string; eta?: number };
export type Cart = Record<string, number>;
export type Fulfil = { fulfil: 'pickup' | 'delivery'; zone?: string; address?: string };

const MAX_QTY = 99;

export function leftOf(item: Product, live: Record<string, number> | undefined): number {
  if (live && typeof live[item.id] === 'number') return live[item.id];
  return typeof item.stock === 'number' && Number.isInteger(item.stock) && item.stock > 0 ? item.stock : 0;
}

export function addTo(cart: Cart, item: Product, left: number): Cart {
  const n = (cart[item.id] ?? 0) + 1;
  return n > left || n > MAX_QTY ? cart : { ...cart, [item.id]: n };
}

export function takeFrom(cart: Cart, id: string): Cart {
  const { [id]: n, ...rest } = cart;
  return n && n > 1 ? { ...rest, [id]: n - 1 } : rest;
}

export const cartCount = (cart: Cart) => Object.values(cart).reduce((a, n) => a + n, 0);

function units(amount: unknown, decimals: number): bigint {
  const m = typeof amount === 'string' ? /^(\d{1,12})(?:\.(\d+))?$/.exec(amount) : null;
  if (!m || (m[2] ?? '').length > decimals) return 0n;
  return BigInt(m[1]) * 10n ** BigInt(decimals) + BigInt((m[2] ?? '').padEnd(decimals, '0') || '0');
}

function plain(u: bigint, decimals: number): string {
  const scale = 10n ** BigInt(decimals);
  const frac = (u % scale).toString().padStart(decimals, '0').replace(/0+$/, '');
  return `${u / scale}${frac ? `.${frac}` : ''}`;
}

export function orderSum(block: B, cart: Cart, zone: Zone | null): { subtotal: string; fee: string; total: string; belowMin: boolean } {
  const d = DECIMALS[block.data?.token] ?? 6;
  const items: Product[] = Array.isArray(block.data?.items) ? block.data.items : [];
  const sub = items.reduce((a, i) => a + units(i.price, d) * BigInt(cart[i.id] ?? 0), 0n);
  const fee = zone ? units(zone.fee, d) : 0n;
  return {
    subtotal: plain(sub, d),
    fee: plain(fee, d),
    total: plain(sub + fee, d),
    belowMin: !!zone?.min && sub < units(zone.min, d),
  };
}

/** No delivery block: the visitor collects. A delivery block: its zones, and pickup only when the owner switched it on. */
export function fulfilOptions(page: { blocks: B[] }): { pickup: boolean; zones: Zone[] } {
  const area = page.blocks.find((b) => b.kind === 'loc.service_area' && !b.hidden);
  if (!area) return { pickup: true, zones: [] };
  const zones = Array.isArray(area.data?.items) ? area.data.items.filter((z: Zone) => z && typeof z.id === 'string') : [];
  return { pickup: area.data?.pickup === true, zones };
}

export function orderBody(block: B, cart: Cart, how: Fulfil) {
  const items: Product[] = Array.isArray(block.data?.items) ? block.data.items : [];
  const lines = items.filter((i) => cart[i.id] > 0).map((i) => ({ id: i.id, qty: cart[i.id] }));
  const body: { block: string; items: { id: string; qty: number }[]; fulfil: Fulfil['fulfil']; zone?: string; address?: string } = {
    block: String(block.id),
    items: lines,
    fulfil: how.fulfil,
  };
  if (how.fulfil === 'delivery') Object.assign(body, { zone: how.zone, address: (how.address ?? '').trim() });
  return body;
}
