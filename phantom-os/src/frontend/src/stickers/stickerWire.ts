/**
 * Стікер їде звичайним текстовим листом: перший рядок — емодзі (старий
 * застосунок і сповіщення показують саме його), другий — мітка набору й
 * стікера. Нового роду кадру дріт не знає; стікер малюється з набору,
 * що лежить у застосунку, без мережі.
 */
import { tNow } from '../components/chat/tNow';

export interface StickerRef {
  packId: string;
  id: string;
  emoji: string;
}

const TAG = /^⟦st1:([a-z0-9]{1,16})\/([a-z0-9-]{1,32})⟧$/;
const MAX_EMOJI = 16;

export const stickerTag = (packId: string, id: string): string => `⟦st1:${packId}/${id}⟧`;

export const composeSticker = (s: StickerRef): string => `${s.emoji}\n${stickerTag(s.packId, s.id)}`;

export function parseSticker(text: string | null | undefined): StickerRef | null {
  if (!text || !text.includes('⟦st1:')) return null;
  const lines = text.trim().split('\n');
  if (lines.length !== 2) return null;
  const emoji = lines[0].trim();
  const m = TAG.exec(lines[1].trim());
  if (!m || !emoji || emoji.length > MAX_EMOJI || /[\p{L}\p{N}]/u.test(emoji)) return null;
  return { packId: m[1], id: m[2], emoji };
}

/** Превʼю, пошук, цитата: «☕ Стікер» замість мітки. */
export function stickerStrip(text: string): string {
  const s = parseSticker(text);
  if (s) return `${s.emoji} ${tNow('chatList.kind.sticker')}`;
  return text.includes('⟦st1:') ? text.replace(/\n?⟦st1:[^⟧\n]*⟧/g, '').trim() : text;
}
