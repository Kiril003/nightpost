import { describe, expect, it } from 'vitest';
import { applyReaction, reactionActionFor, reactionAuthors } from '../reactionState';
describe('reactionState', () => {
  it('is idempotent for repeated operations', () => {
    const added = applyReaction(undefined, '❤️', 'u1', 'add');
    expect(applyReaction(added, '❤️', 'u1', 'add')).toEqual(added);
    expect(applyReaction(added, '❤️', 'u1', 'remove')).toEqual([]);
  });
  it('does not mutate source state', () => {
    const source = [{ emoji: '🔥', count: 1, users: ['u1'] }];
    expect(applyReaction(source, '🔥', 'u2', 'add')[0].users).toEqual(['u1', 'u2']);
    expect(source[0].users).toEqual(['u1']);
  });
  it('derives add/remove from membership', () => {
    expect(reactionActionFor(undefined, '👍', 'u1')).toBe('add');
    expect(reactionActionFor([{ emoji: '👍', count: 1, users: ['u1'] }], '👍', 'u1')).toBe('remove');
  });

  /**
   * Дорога домальовка. Без неї смайл виглядав однаково «до» і «після»
   * відповіді вузла — це і є «начебто поставилось».
   */
  it('несе ознаку дороги, коли її назвали, і не вигадує, коли ні', () => {
    expect(applyReaction(undefined, '❤️', 'u1', 'add', 'sending')[0].mine).toBe('sending');
    expect(applyReaction(undefined, '❤️', 'u1', 'add')[0].mine).toBeUndefined();
  });

  it('зняте не лишає по собі дороги', () => {
    const mine = applyReaction(undefined, '❤️', 'u1', 'add', 'queued');
    const shared = applyReaction(mine, '❤️', 'u2', 'add');
    // Годинник під смайлом, якого вже не тримаємо, — брехня про свою дію.
    expect(applyReaction(shared, '❤️', 'u1', 'remove')[0].mine).toBeUndefined();
  });

  /** Імена й автори зсуваються разом або не зсуваються зовсім. */
  it('імена лишаються навпроти своїх авторів', () => {
    const two = applyReaction(
      [{ emoji: '🔥', count: 1, users: ['u1'], names: ['Оксана'] }],
      '🔥',
      { id: 'u2', name: 'Богдан' },
      'add',
    );
    expect(two[0].names).toEqual(['Оксана', 'Богдан']);

    const gone = applyReaction(two, '🔥', 'u1', 'remove');
    expect(gone[0].users).toEqual(['u2']);
    expect(gone[0].names).toEqual(['Богдан']);
  });

  it('автор без імені прибирає імена зовсім, а не зсуває їх', () => {
    const mixed = applyReaction(
      [{ emoji: '🔥', count: 1, users: ['u1'], names: ['Оксана'] }],
      '🔥',
      'u2',
      'add',
    );
    // Список на один коротший підписав би Богдана Оксаною.
    expect(mixed[0].names).toBeUndefined();
  });

  it('підпис під смайлом — люди, а не ключі вузлів', () => {
    const r = { emoji: '👍', count: 2, users: ['me', 'node_8f3ac1d0e4b7'], names: ['Я', 'Оксана'] };
    expect(reactionAuthors(r, 'me')).toEqual(['Я', 'Оксана']);
    // Вузол не назвав імен — коротка форма ключа, а не вигадане імʼя.
    expect(reactionAuthors({ emoji: '👍', count: 1, users: ['node_8f3ac1d0e4b7'] }, 'me'))
      .toEqual(['node_8f3']);
  });
});
