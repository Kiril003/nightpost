/** Bookings kit on a public page: masters and the deposit due, with the server's own math (venue_requests.deposit_of). */
type B = { kind: string; hidden?: boolean; data?: any };

export const DECIMALS: Record<string, number> = { USDC: 6, EURC: 6, SOL: 9 };

const visible = (page: { blocks: B[] }, kind: string) => page.blocks.find((b) => b.kind === kind && !b.hidden);

export function pageStaff(page: { blocks: B[] }): { id: string; name: string; role?: string }[] {
  const items = visible(page, 'book.staff')?.data?.items;
  return Array.isArray(items) ? items.filter((i) => i && typeof i.id === 'string' && i.id && typeof i.name === 'string') : [];
}

export function depositOf(price: unknown, percent: unknown, decimals: number): string | null {
  if (typeof price !== 'string' || typeof percent !== 'number' || !Number.isInteger(percent) || percent <= 0 || percent > 100) return null;
  const m = new RegExp(`^(0|[1-9]\\d{0,8})(?:\\.(\\d{1,${decimals}}))?$`).exec(price);
  if (!m) return null;
  const scale = 10n ** BigInt(decimals);
  const units = BigInt(m[1]) * scale + BigInt((m[2] ?? '').padEnd(decimals, '0') || '0');
  const due = (units * BigInt(percent) + 50n) / 100n;
  if (due === 0n) return null;
  const frac = (due % scale).toString().padStart(decimals, '0').replace(/0+$/, '');
  return `${due / scale}${frac ? `.${frac}` : ''}`;
}

export function depositDue(page: { blocks: B[] }, service: { price?: unknown } | undefined, token: unknown): { amount: string; token: string } | null {
  if (!service || typeof token !== 'string' || !(token in DECIMALS)) return null;
  const amount = depositOf(service.price, visible(page, 'book.policy')?.data?.deposit, DECIMALS[token]);
  return amount ? { amount, token } : null;
}
