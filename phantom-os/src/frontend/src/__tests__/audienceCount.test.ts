import { describe, it, expect } from 'vitest';
import { audienceCount } from '../services/audienceCount';
import type { Chat } from '../types/messenger';

const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({
    id,
    title,
    type: 'dm',
    circle: 'all',
    avatar: '',
    unreadCount: 0,
    pinned: false,
    messages: [],
    lastSnippet: 'hi',
    lastAt: new Date().toISOString(),
    peerNodeId: id,
    ...extra,
  }) as unknown as Chat;

describe('audienceCount', () => {
  it('contacts audience with 27 DMs and 3 in work counts 27 total and 3 in byRing.work', () => {
    const inner = Array.from({ length: 24 }, (_, i) => chat(`p${i + 1}`, `Person ${i + 1}`, { circle: 'friends' }));
    const work = [
      chat('w1', 'Worker 1', { circle: 'work' }),
      chat('w2', 'Worker 2', { circle: 'work' }),
      chat('w3', 'Worker 3', { circle: 'work' }),
    ];
    const res = audienceCount({ kind: 'contacts' }, [...inner, ...work]);
    expect(res).not.toBeNull();
    expect(res!.total).toBe(27);
    expect(res!.ids).toHaveLength(27);
    expect(res!.byRing.work).toEqual(['w1', 'w2', 'w3']);
  });

  it('person in both close and work is counted once under close, not under work', () => {
    const chats = [
      chat('olena', 'Olena', { circle: 'work', circles: ['close', 'work'] }),
      chat('taras', 'Taras', { circle: 'work' }),
      chat('plain', 'Plain'),
    ];
    const res = audienceCount({ kind: 'contacts' }, chats);
    expect(res).not.toBeNull();
    expect(res!.total).toBe(2);
    expect(res!.byRing.close).toEqual(['olena']);
    expect(res!.byRing.work).toEqual(['taras']);
  });

  it('people audience keeps only reachable ids; me, everyone, and empty reach return null', () => {
    const chats = [
      chat('olena', 'Olena', { circle: 'personal' }),
      chat('mum', 'Mum', { circle: 'close' }),
    ];
    const picked = audienceCount({ kind: 'people', ids: ['olena', 'unknown'] }, chats);
    expect(picked).toEqual({
      total: 1,
      byRing: { personal: ['olena'] },
      ids: ['olena'],
    });
    expect(audienceCount({ kind: 'me' }, chats)).toBeNull();
    expect(audienceCount({ kind: 'everyone' }, chats)).toBeNull();
    expect(audienceCount({ kind: 'contacts' }, [])).toBeNull();
    expect(audienceCount({ kind: 'people', ids: ['unknown'] }, chats)).toBeNull();
  });

  it('excludes a person whose primary ring is Friends even if Work is in chat.circles when audience is Work', () => {
    const chats = [
      chat('petro', 'Petro', { circle: 'friends', circles: ['friends', 'work'] }),
      chat('taras', 'Taras', { circle: 'work' }),
      chat('ivan', 'Ivan', { circle: 'personal' }),
      chat('arch', 'Archived', { circle: 'work', archived: true }),
    ];

    expect(audienceCount({ kind: 'circles', names: ['work'], ids: [] }, chats)).toEqual({
      total: 1,
      byRing: { work: ['taras'] },
      ids: ['taras'],
    });

    expect(audienceCount({ kind: 'circles', names: ['work'], ids: ['petro', 'taras'] }, chats)).toEqual({
      total: 1,
      byRing: { work: ['taras'] },
      ids: ['taras'],
    });

    expect(audienceCount({ kind: 'circle', name: 'work', ids: ['petro', 'taras'] }, chats)).toEqual({
      total: 1,
      byRing: { work: ['taras'] },
      ids: ['taras'],
    });

    expect(audienceCount({ kind: 'circles', names: ['work'], ids: [] }, [chats[0]])).toBeNull();
  });
});
