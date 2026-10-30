/**
 * Текст листа без службових рядків — те, що людина бачить у бульбашці:
 * без позначки роботи `[np:…]`, рядків картки `PH-ORD:`/`PH-ORS:` і мітки
 * навчання `⟦edu1:…⟧` і стікера `⟦st1:…⟧`. Одне джерело для превʼю в списку, пошуку, копіювання
 * й цитати.
 */
import { stripWorkTag } from './workEvents';
import { plainOrderText } from './business/orders';
import { eduStrip } from './edu/eduWire';
import { stickerStrip } from '../stickers/stickerWire';

/** Позначка роботи, яку обрізав `lastSnippet`: лише префікс її граматики, без закривної дужки. */
const CUT_WORK_TAG = /\n\s*\[np:(?:[a-z-]+(?: [a-z]+=[^\s\]]*)*(?: [a-z]*)?)?$/;

export function plainText(text: string | null | undefined): string {
  if (!text) return '';
  let s = stripWorkTag(text);
  if (s.includes('[np:')) s = s.replace(CUT_WORK_TAG, '').trimEnd();
  return stickerStrip(eduStrip(plainOrderText(s)));
}
