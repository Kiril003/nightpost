/**
 * Дотик по тегу в бульбашці → назад до списку з пошуком цього тегу.
 *
 * Бульбашка не знає, хто й як шукає: вона лише каже «шукай #тег». Слухає
 * список (подія TAG_SEARCH_EVENT) — так стрічка не тягне в себе ні список,
 * ні його стан, а другий слухач (ПК) може підʼєднатися без змін тут.
 */
import { useMessengerStore } from '../stores/messengerStore';
import { normaliseTag } from './listSignals';

export const TAG_SEARCH_EVENT = 'phantom:tag-search';

export interface TagSearchDetail {
  /** Завжди з `#` і малими літерами: `#світ`. */
  tag: string;
}

export function openTagSearch(tag: string): void {
  const bare = normaliseTag(tag);
  if (!bare) return;
  // Закриваємо розмову СТАНОМ, як системне «Назад» у MessengerRoot, а не
  // setActiveChat(''): той ще й тягне стрічку з вузла по порожньому id і
  // клацає звуком дотику вдруге.
  useMessengerStore.setState({ activeChatId: '' });
  // Подія — на наступному такті: список монтується після закриття розмови,
  // і подія, кинута раніше, впала б у порожнечу.
  setTimeout(() => {
    window.dispatchEvent(
      new CustomEvent<TagSearchDetail>(TAG_SEARCH_EVENT, { detail: { tag: `#${bare}` } }),
    );
  }, 0);
}
