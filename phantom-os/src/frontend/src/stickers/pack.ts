/**
 * Власний набір D1 (стиль D): анімації лежать у застосунку окремими
 * дрібними шматками й підтягуються лише тоді, коли стікер на екрані.
 */
export interface StickerDef {
  packId: string;
  id: string;
  emoji: string;
}

export const D1_PACK = 'd1';

const D1: ReadonlyArray<[string, string]> = [
  ['tea', '☕'],
  ['wave', '👋'],
  ['heart', '❤️'],
  ['thumbs', '👍'],
  ['laugh', '😂'],
  ['sun', '☀️'],
  ['moon', '🌙'],
  ['clock', '⏰'],
  ['done', '✅'],
  ['party', '🎉'],
  ['pin', '📍'],
  ['gift', '🎁'],
  ['coin', '🪙'],
  ['sleep', '😴'],
  ['wow', '😮'],
  ['sad', '😢'],
  ['clap', '👏'],
  ['fire', '🔥'],
];

export const PACK: readonly StickerDef[] = D1.map(([id, emoji]) => ({ packId: D1_PACK, id, emoji }));

const files = import.meta.glob('./d1/*.json', { import: 'default' }) as Record<string, () => Promise<object>>;

export function stickerDef(packId: string, id: string): StickerDef | null {
  return PACK.find((s) => s.packId === packId && s.id === id) ?? null;
}

const bare = (e: string): string => e.replace(/️/g, '').trim();

/** Самотнє емодзі, для якого в наборі є живий двійник. */
export function stickerForEmoji(emoji: string): StickerDef | null {
  const e = bare(emoji);
  return e ? PACK.find((s) => bare(s.emoji) === e) ?? null : null;
}

export function loadSticker(packId: string, id: string): Promise<object | null> {
  const load = stickerDef(packId, id) ? files[`./${packId}/${id}.json`] : undefined;
  return load ? load().then((d) => d ?? null, () => null) : Promise.resolve(null);
}
