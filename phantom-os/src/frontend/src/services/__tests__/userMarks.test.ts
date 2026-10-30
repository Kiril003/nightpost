import { beforeEach, describe, expect, it } from 'vitest';
import type { Message } from '../../types/messenger';
import { isBackedUpKey } from '../backupStash';
import { PINS_KEY, VOTES_KEY, userMarks } from '../userMarks';

const me = 'me1';
const poll = (id: string, voters: string[][] = [[], []]): Message =>
  ({
    id,
    type: 'poll',
    text: '',
    pollData: {
      question: 'Куди?',
      options: voters.map((v, i) => ({ id: `o${i + 1}`, text: `${i}`, votes: v.length, voters: v })),
      totalVotes: voters.flat().length,
    },
  }) as unknown as Message;
const text = (id: string, extra: Partial<Message> = {}): Message => ({ id, type: 'text', text: 'привіт', ...extra }) as Message;

describe('pins and my poll votes outside the chat cache', () => {
  beforeEach(() => {
    localStorage.clear();
    userMarks.reload();
  });

  it('both keys travel with the encrypted copy', () => {
    expect(isBackedUpKey(PINS_KEY)).toBe(true);
    expect(isBackedUpKey(VOTES_KEY)).toBe(true);
  });

  it('first run lifts pins and my votes out of the old cache, once', () => {
    const cache = [{ messages: [text('m1', { isPinned: true }), text('m2'), poll('p1', [[], [me, 'x']])] }];
    userMarks.adoptFromCache(cache, me);
    expect(JSON.parse(localStorage.getItem(PINS_KEY)!)).toEqual(['m1']);
    expect(JSON.parse(localStorage.getItem(VOTES_KEY)!)).toEqual({ p1: ['o2'] });
    userMarks.adoptFromCache([{ messages: [text('m9', { isPinned: true })] }], me);
    expect(JSON.parse(localStorage.getItem(PINS_KEY)!)).toEqual(['m1']);
  });

  it('a phone restored without the cache gets the pin and the vote back on the node rows', () => {
    userMarks.pin('m1', true);
    userMarks.vote('p1', ['o1']);
    localStorage.removeItem('phantom_chats_backup');
    userMarks.reload();
    const [m1, p1] = userMarks.apply([text('m1'), poll('p1', [['x'], []])], me);
    expect(m1.isPinned).toBe(true);
    expect(p1.pollData!.options[0].voters).toEqual(['x', me]);
    expect(p1.pollData!.totalVotes).toBe(2);
  });

  it('a vote the node already counts is not counted twice', () => {
    userMarks.vote('p1', ['o2']);
    const rows = [poll('p1', [[], [me]])];
    expect(userMarks.apply(rows, me)).toBe(rows);
  });

  it('unpin and a withdrawn vote leave the copy', () => {
    userMarks.pin('m1', true);
    userMarks.pin('m1', false);
    userMarks.vote('p1', ['o1']);
    userMarks.vote('p1', []);
    expect(JSON.parse(localStorage.getItem(PINS_KEY)!)).toEqual([]);
    expect(JSON.parse(localStorage.getItem(VOTES_KEY)!)).toEqual({});
  });
});
