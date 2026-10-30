import type { Message } from '../types/messenger';
import { parseSticker } from './stickerWire';
import { stickerForEmoji } from './pack';

export const STICKER = 160;
export const BIG_EMOJI = 112;

/** Розмір живої картинки листа (0 — не стікер): по ньому час сідає на її нижній край. */
export function stickerBoxOf(msg: Pick<Message, 'type' | 'text' | 'media'>): number {
  if (msg.type !== 'text' || msg.media || !msg.text) return 0;
  if (parseSticker(msg.text)) return STICKER;
  const t = msg.text.trim();
  return t.length <= 16 && stickerForEmoji(t) ? BIG_EMOJI : 0;
}
