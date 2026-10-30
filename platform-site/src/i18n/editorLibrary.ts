import type { Lang } from './types';
import type { Chip } from '../lib/creator/library';

export type LibraryCopy = {
  search: (n: number) => string;
  chips: Record<Chip, string>;
  families: string;
  nothingFor: (q: string) => string;
  pageFull: string;
  clear: string;
  onPage: (n: number) => string;
};

const ukBlocks = (n: number) => {
  const d = n % 10;
  const h = n % 100;
  if (d === 1 && h !== 11) return 'блок';
  if (d >= 2 && d <= 4 && (h < 12 || h > 14)) return 'блоки';
  return 'блоків';
};

const en: LibraryCopy = {
  search: (n) => `Search ${n} blocks`,
  chips: { you: 'For you', sell: 'Sell', book: 'Book', show: 'Show', talk: 'Talk', pay: 'Pay', place: 'Place' },
  families: 'Block families',
  nothingFor: (q) => `No blocks for “${q}”`,
  pageFull: 'Nothing more fits this page',
  clear: 'Clear',
  onPage: (n) => `${n} on this page`,
};

const uk: LibraryCopy = {
  search: (n) => `Пошук · ${n} ${ukBlocks(n)}`,
  chips: { you: 'Для вас', sell: 'Продаж', book: 'Запис', show: 'Показ', talk: 'Розмова', pay: 'Оплата', place: 'Місце' },
  families: 'Групи блоків',
  nothingFor: (q) => `Немає блоків для «${q}»`,
  pageFull: 'На сторінку більше нічого не додати',
  clear: 'Очистити',
  onPage: (n) => `На сторінці: ${n}`,
};

export const libraryCopy: Record<Lang, LibraryCopy> = { en, uk };
