/** Те, що лист несе поверх тексту: автор пересланого (`fw`) і обраний шматок цитати (`qt`). Старі збірки ці поля пропускають. */
import type { Message } from '../types/messenger';

export const QUOTE_MAX = 200;

/** Пересланий лист з дроту: автор оригіналу. Розмови-джерела в листі немає — лише людина. */
export function forwardOfRow(row: { fw?: { name?: unknown; id?: unknown } | null }): Message['forwardFrom'] | undefined {
  const name = typeof row.fw?.name === 'string' ? row.fw.name.trim().slice(0, 64) : '';
  if (!name) return undefined;
  const id = typeof row.fw?.id === 'string' ? row.fw.id.slice(0, 128) : '';
  return { chatTitle: '', senderName: name, ...(id ? { senderId: id } : {}) };
}

/** Обраний шматок цитати з дроту. */
export function quoteOfRow(row: { qt?: unknown } | undefined): string | undefined {
  const q = typeof row?.qt === 'string' ? row.qt.trim().slice(0, QUOTE_MAX) : '';
  return q || undefined;
}

/** Обраний шматок цитати — на дріт. */
export function quoteForWire(text: string | undefined | null): string | undefined {
  const q = (text ?? '').trim().slice(0, QUOTE_MAX);
  return q || undefined;
}
