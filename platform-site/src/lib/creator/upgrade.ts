import { up } from '../blocks/migrate.ts';
import { WHO_LOOK, WHO_SAMPLE } from './start.ts';
import { SAMPLES } from './samples.ts';
import type { Block, Json } from './arrange.ts';
import type { EditPage } from './editor.ts';

type OldVenue = {
  name: string;
  page: { v?: number; blocks?: unknown[] } | null;
  rating?: { mean: number; count: number } | null;
  location?: { address: string; lat: number; lon: number } | null;
  wallet?: string | null;
};

const DAYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'];
const filled = (v: unknown) => typeof v === 'string' && v.trim() !== '';

function hasContent(b: Block): boolean {
  const d = b.data;
  if (b.kind === 'id.name') return filled(d.name);
  if (b.kind === 'id.about') return filled(d.text);
  if (b.kind === 'loc.address') return filled(d.text) && Number.isFinite(d.point?.lat) && Number.isFinite(d.point?.lon);
  if (b.kind === 'hrs.week') return Array.isArray(d.rows) && d.rows.length > 0;
  if (b.kind === 'cat.menu') return Array.isArray(d.items) && d.items.length > 0;
  if (b.kind === 'evt.single') return filled(d.title) && filled(d.starts);
  if (b.kind === 'pay.wallet') return filled(d.address);
  return true;
}

function groupHours(rows: Json[]): Json[] {
  const out: Json[] = [];
  const sorted = [...rows].sort((a, b) => DAYS.indexOf(a.days[0]) - DAYS.indexOf(b.days[0]));
  for (const r of sorted) {
    const prev = out[out.length - 1];
    const next = DAYS.indexOf(prev?.days[prev.days.length - 1]) + 1 === DAYS.indexOf(r.days[0]);
    if (prev && next && prev.hours.open === r.hours.open && prev.hours.close === r.hours.close) prev.days.push(...r.days);
    else out.push({ days: [...r.days], hours: { ...r.hours } });
  }
  return out;
}

export function upgradePage(venue: OldVenue): EditPage {
  if (venue.page?.v === 2) return venue.page as unknown as EditPage;
  const look = WHO_LOOK.place;
  const template = SAMPLES.find((s) => s.handle === WHO_SAMPLE.place)?.page;
  const blocks = ((up({ blocks: venue.page?.blocks ?? [] }).blocks ?? []) as Block[]).filter(hasContent);
  const at = (kind: string) => blocks.findIndex((b) => b.kind === kind);

  const name = venue.name.trim();
  const n = at('id.name');
  if (n > 0) blocks.unshift(...blocks.splice(n, 1));
  else if (n < 0 && name) blocks.unshift({ id: 'name-0', kind: 'id.name', variant: 'plain', hidden: false, data: { name } });

  const loc = venue.location;
  if (at('loc.address') < 0 && loc && filled(loc.address) && Number.isFinite(loc.lat) && Number.isFinite(loc.lon)) {
    blocks.push({ id: 'address-loc', kind: 'loc.address', variant: 'map_line', hidden: false, data: { text: loc.address, point: { lat: loc.lat, lon: loc.lon } } });
  }

  const week = blocks[at('hrs.week')];
  if (week) week.data = { rows: groupHours(week.data.rows) };

  const wallet = at('pay.wallet');
  if (venue.rating && venue.rating.count > 0) {
    const reviews: Block = { id: 'reviews-0', kind: 'com.reviews', variant: 'summary', hidden: false, data: { text: true, show: 3 } };
    if (wallet < 0) blocks.push(reviews);
    else blocks.splice(wallet, 0, reviews);
  }
  if (at('pay.wallet') < 0 && filled(venue.wallet)) {
    blocks.push({ id: 'wallet-0', kind: 'pay.wallet', variant: 'address_row', hidden: false, data: { address: venue.wallet } });
  }

  for (const b of blocks) {
    const v = template?.blocks.find((t) => t.kind === b.kind)?.variant;
    if (v) b.variant = v;
  }
  return { v: 2, look, tone: template?.tone ?? 'amber', blocks };
}
