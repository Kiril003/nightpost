import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { belongsInConversations, hasLetter } from '../components/messenger/chatListFormat';
import { __resetChatKeep, chatKeep } from '../services/chatKeep';
import type { Chat } from '../types/messenger';

const makeChat = (id: string, overrides: Partial<Chat> = {}): Chat => ({
  id,
  title: 'Олена',
  avatar: '',
  type: 'dm',
  circle: 'all',
  unreadCount: 0,
  messages: [],
  ...overrides,
});

beforeEach(() => {
  __resetChatKeep();
});

afterEach(() => {
  __resetChatKeep();
});

describe('chatKeep', () => {
  it('marks a letterless person chat as belonging in conversations', () => {
    const chat = makeChat('dm_1');
    expect(belongsInConversations(chat)).toBe(false);
    chatKeep.add('dm_1');
    expect(chatKeep.has('dm_1')).toBe(true);
    expect(belongsInConversations(chat)).toBe(true);
  });

  it('persists kept ids across module reloads', async () => {
    chatKeep.add('dm_persisted');
    vi.resetModules();
    const { chatKeep: reloaded } = await import('../services/chatKeep');
    expect(reloaded.has('dm_persisted')).toBe(true);
  });

  it('a cleared chat stays protected after reload while a later plain id is evicted first', async () => {
    const cleared = makeChat('c_cleared', { lastKind: 'text', lastSnippet: 'b' });
    chatKeep.track([cleared], [], hasLetter);
    chatKeep.track([makeChat('c_cleared')], [cleared], hasLetter);
    chatKeep.track([makeChat('c_plain', { lastKind: 'text', lastSnippet: 'a' })], [], hasLetter);
    vi.resetModules();
    const { chatKeep: reloaded } = await import('../services/chatKeep');
    for (let i = 0; i < 499; i += 1) reloaded.add(`n_${i}`);
    expect(reloaded.has('c_plain')).toBe(false);
    expect(reloaded.has('c_cleared')).toBe(true);
  });

  it('falls back to an empty set when np.chatKeep.v1 contains garbage', async () => {
    localStorage.setItem('np.chatKeep.v1', '{not-json');
    vi.resetModules();
    const { chatKeep: reloaded } = await import('../services/chatKeep');
    expect(reloaded.has('dm_1')).toBe(false);
  });

  it('tracks lettered chats, preserves them across a transient short list, and drops only on explicit drop', () => {
    const lettered = makeChat('c_lettered', {
      lastKind: 'text',
      lastSnippet: 'Привіт',
    });
    const empty = makeChat('c_empty');
    chatKeep.track([lettered, empty], [], hasLetter);
    expect(chatKeep.has('c_lettered')).toBe(true);
    expect(chatKeep.has('c_empty')).toBe(false);

    const cleared = makeChat('c_lettered');
    chatKeep.track([cleared], [lettered, empty], hasLetter);
    expect(chatKeep.has('c_lettered')).toBe(true);

    chatKeep.track([], [cleared], hasLetter);
    expect(chatKeep.has('c_lettered')).toBe(true);

    chatKeep.drop('c_lettered');
    expect(chatKeep.has('c_lettered')).toBe(false);
  });

  it('evicts least-recently-touched at the 500 cap and never evicts cleared chats before plain tracked ones', () => {
    const cLettered = makeChat('c_cleared', {
      lastKind: 'text',
      lastSnippet: 'Старе повідомлення',
    });
    const cCleared = makeChat('c_cleared');
    chatKeep.track([cLettered], [], hasLetter);
    chatKeep.track([cCleared], [cLettered], hasLetter);
    chatKeep.add('c_added');

    const firstBatch = Array.from({ length: 498 }, (_, i) =>
      makeChat(`m_${i}`, { lastKind: 'text', lastSnippet: `Лист ${i}` }),
    );
    chatKeep.track(firstBatch, [], hasLetter);

    chatKeep.track([firstBatch[0]], [], hasLetter);

    const overflow = [
      makeChat('m_498', { lastKind: 'text', lastSnippet: 'Лист 498' }),
      makeChat('m_499', { lastKind: 'text', lastSnippet: 'Лист 499' }),
    ];
    chatKeep.track(overflow, [], hasLetter);

    expect(chatKeep.has('c_cleared')).toBe(true);
    expect(chatKeep.has('c_added')).toBe(true);
    expect(chatKeep.has('m_0')).toBe(true);
    expect(chatKeep.has('m_1')).toBe(false);
    expect(chatKeep.has('m_2')).toBe(false);
    expect(chatKeep.has('m_3')).toBe(true);
    expect(chatKeep.has('m_499')).toBe(true);
  });
});
