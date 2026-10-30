/**
 * ПОШУК СМАЙЛА, який не міг знайти нічого.
 *
 * Виміряно 18.09, ReactionPickerModal.tsx:88 до правки:
 *
 *     allEmojis.filter((e) => e.includes(search.trim()))
 *
 * Збіг шукався в самому символі емодзі. Жодне слово, яке можна набрати з
 * клавіатури, у «❤️» не міститься, тож поле з написом «Пошук реакції або
 * емодзі...» відповідало ПОРОЖНЬОЮ СІТКОЮ на будь-який ввід — і порожня сітка
 * ще й нічого не казала, тобто читалась як «застрягло». Знайти щось можна було
 * рівно одним способом: вставити емодзі, яке ти й так уже маєш.
 *
 * Мутації (заміряні): прибрати `EMOJI_WORDS[e]` з фільтра — червоніють обидва
 * пошукові тести; викинути один рядок зі словника — червоніє покриття.
 */
import { describe, it, expect } from 'vitest';
import { EMOJI_WORDS, emojiCategories } from '../components/messenger/ReactionPickerModal';

const allEmojis = [...new Set(emojiCategories.flatMap((c) => c.emojis))];

/** Точна копія фільтра з модалки — дзеркало, а не друге правило. */
const search = (query: string) => {
  const q = query.trim().toLowerCase();
  if (!q) return null;
  return emojiCategories
    .flatMap((c) => c.emojis)
    .filter((e) => e.includes(query.trim()) || (EMOJI_WORDS[e] || '').includes(q));
};

describe('пошук реакції', () => {
  it('КОЖНЕ емодзі набору має слова — інакше воно ненаходиме', () => {
    const mute = allEmojis.filter((e) => !EMOJI_WORDS[e]);
    expect(
      mute,
      `емодзі без слів: ${mute.join(' ')} — додайте рядок у EMOJI_WORDS тим самим комітом, ` +
        'інакше набір росте, а пошук про це не знає.',
    ).toEqual([]);
  });

  it('знаходить українською', () => {
    expect(search('серце')).toContain('❤️');
    expect(search('вогонь')).toContain('🔥');
    expect(search('ідея')).toContain('💡');
    expect(search('кава')).toContain('☕');
  });

  it('знаходить англійською — розкладка буває не та', () => {
    expect(search('heart')).toContain('❤️');
    expect(search('rocket')).toContain('🚀');
    expect(search('LAUGH')).toContain('😂');
  });

  it('вставлене емодзі теж знаходиться — стара дорога не зламана', () => {
    expect(search('🔥')).toContain('🔥');
  });

  it('нічого схожого — це порожній результат, а не збіг зі всім', () => {
    expect(search('ъъъ')).toEqual([]);
  });
});
