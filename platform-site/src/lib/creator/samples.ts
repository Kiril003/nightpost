import alma from '../../../server/blocks/fixtures/pages/person-alma.json' with { type: 'json' };
import marigold from '../../../server/blocks/fixtures/pages/group-marigold.json' with { type: 'json' };
import olha from '../../../server/blocks/fixtures/pages/person-olha.json' with { type: 'json' };
import ember from '../../../server/blocks/fixtures/pages/place-ember.json' with { type: 'json' };
import type { Live, Page } from './arrange.ts';

export type Sample = { handle: string; sample: true; lang?: 'en' | 'uk'; page: Page; live: Live };

export const SAMPLES: Sample[] = [alma as unknown as Sample, olha as unknown as Sample, marigold as unknown as Sample, ember as unknown as Sample];

const DEMO = new Set(SAMPLES.flatMap((s) => s.page.blocks.map((b) => b.data?.point).filter(Boolean).map((p) => `${p.lat},${p.lon}`)));

/** A template's sample point: never a real creator's place (the server refuses `demo_place` too). */
export const isDemoPoint = (p: unknown): boolean => !!p && typeof p === 'object' && DEMO.has(`${(p as { lat?: number }).lat},${(p as { lon?: number }).lon}`);

const TEXT_KEYS = new Set(['name', 'tagline', 'headline', 'lede', 'title', 'text', 'note', 'detail', 'label', 'primary_label', 'secondary_label', 'eyebrow', 'alt', 'group']);
const TWINS = new Map<string, unknown[]>();
for (const s of SAMPLES) for (const b of s.page.blocks) TWINS.set(`${b.kind}|${b.id}`, [...(TWINS.get(`${b.kind}|${b.id}`) ?? []), b.data]);

type Node = Record<string, unknown>;
function holdsSample(node: unknown, twin: unknown): boolean {
  if (Array.isArray(node)) {
    const list = Array.isArray(twin) ? twin : [];
    return node.some((v, i) => {
      const id = v && typeof v === 'object' ? (v as Node).id : undefined;
      return holdsSample(v, id !== undefined ? list.find((x) => x && typeof x === 'object' && (x as Node).id === id) : list[i]);
    });
  }
  if (!node || typeof node !== 'object') return false;
  const n = node as Node;
  if (typeof n.blob === 'string' && n.blob.startsWith('sample-')) return true;
  const t = twin && typeof twin === 'object' && !Array.isArray(twin) ? (twin as Node) : undefined;
  return Object.entries(n).some(([k, v]) => (TEXT_KEYS.has(k) && typeof v === 'string' && v.trim() !== '' && t?.[k] === v) || holdsSample(v, t?.[k]));
}

/** Shown blocks still carrying a template's words or photos (mirrors the server's `sample_content`). */
export function sampleBlocks(page: { blocks: { id: string; kind: string; hidden?: boolean; data: unknown }[] }): string[] {
  return page.blocks.filter((b) => !b.hidden && b.data && (TWINS.get(`${b.kind}|${b.id}`) ?? [undefined]).some((t) => holdsSample(b.data, t))).map((b) => b.id);
}

export function sampleByHandle(handle: string): Sample | undefined {
  return SAMPLES.find((s) => s.handle === handle);
}

import { BUILDER_SERVER as MEDIA } from '../builderServer.ts';

let mediaVenue: { id: string; gen: number } | null = null;

/** While the editor holds a media session cookie, its images load from the venue's own same-origin path. */
export function setMediaVenue(id: string | null, gen = 0): void {
  mediaVenue = id ? { id, gen } : null;
}

export function imageUrl(blob: string): string | null {
  if (/^sample-[a-z]+-\d{2}$/.test(blob)) return `/creator/${blob}.webp`;
  if (/^p[0-9a-f]{32}$/.test(blob)) {
    return mediaVenue ? `/venues/${encodeURIComponent(mediaVenue.id)}/media/${blob}?s=${mediaVenue.gen}` : `${MEDIA}/venue-media/${blob}`;
  }
  return null;
}
