import { GLYPHS, type GlyphDef } from './glyphData';

export type { GlyphDef };
export { GLYPHS };

export const QUICK_GLYPH_IDS = ['yes', 'love', 'laugh', 'wow', 'sad', 'thanks'] as const;

const norm = (s: string) => s.replace(/️/g, '');
const byId = new Map(GLYPHS.map((g) => [g.id, g]));
const byEmoji = new Map(GLYPHS.map((g) => [norm(g.emoji), g]));

export const glyphById = (id: string): GlyphDef | undefined => byId.get(id);

/** У дроті їде запасний смайл: старий клієнт малює його, новий впізнає знак. */
export function glyphForReaction(value: string): GlyphDef | undefined {
  if (value.startsWith('np:')) return byId.get(value.slice(3));
  return byEmoji.get(norm(value));
}

export const wireEmojiFor = (id: string): string => byId.get(id)?.emoji ?? id;

export function glyphLabel(g: GlyphDef, lang: string): string {
  return lang.startsWith('uk') ? g.uk : g.en;
}
