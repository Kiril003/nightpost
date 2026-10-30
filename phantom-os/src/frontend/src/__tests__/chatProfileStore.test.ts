/**
 * W-S1: кожна розмова належить рівно одному профілю (SPEC-2810 §2, §5).
 * Міграція тиха, одноразова, ідемпотентна; без збігу — «Особисте».
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { create } from 'zustand';
import {
  CHAT_PROFILE_KEY,
  loadChatProfiles,
  migrateChats,
  ownerOf,
  profileOf,
  startChatProfiles,
  useChatProfiles,
} from '../stores/chatProfileStore';
import { templateDefaults, useProfiles, type Profile, type ProfileKind } from '../stores/profilesStore';
import type { Chat } from '../types/messenger';

const P = (id: string, kind: ProfileKind, passGroups?: string[]): Profile => {
  const d = templateDefaults(kind, 1);
  return { ...d, id, name: id, passGroups: passGroups ?? d.passGroups };
};

const chat = (id: string, circle: string, extra: Partial<Chat> = {}): Chat =>
  ({ id, title: id, type: 'dm', circle, avatar: '', unreadCount: 0, messages: [], lastSelfAt: '2026-10-20T10:00:00Z', ...extra }) as unknown as Chat;

const personal = P('personal', 'personal');
const work = P('work', 'work');

const initial = useProfiles.getState();

beforeEach(() => {
  localStorage.clear();
  useChatProfiles.setState(loadChatProfiles());
  useProfiles.setState({ activeId: 'personal', profiles: [personal, work], previousId: null });
});

afterEach(() => {
  localStorage.clear();
  useProfiles.setState({ activeId: initial.activeId, profiles: initial.profiles, previousId: null });
});

describe('migrateChats (SPEC §5)', () => {
  it('a work chat moves to the only matching work profile, the rest stays in Особисте', () => {
    const map = migrateChats([chat('taras', 'work'), chat('mama', 'close')], [personal, work], {}, 'me');
    expect(map).toEqual({ taras: 'work', mama: 'personal' });
  });

  it('is idempotent: running twice gives the same map', () => {
    const chats = [chat('taras', 'work'), chat('mama', 'close'), chat('g1', 'work', { type: 'group' })];
    const once = migrateChats(chats, [personal, work], {}, 'me');
    expect(migrateChats(chats, [personal, work], once, 'me')).toEqual(once);
  });

  it('an overlap (family vs personal) stays personal', () => {
    const family = P('family', 'family');
    const map = migrateChats([chat('mama', 'close'), chat('bro', 'friends')], [personal, family], {}, 'me');
    expect(map).toEqual({ mama: 'personal', bro: 'personal' });
  });

  it('two profiles claiming one circle is not exclusive: stays personal', () => {
    const dev = P('dev', 'dev');
    expect(migrateChats([chat('taras', 'work')], [personal, work, dev], {}, 'me')).toEqual({ taras: 'personal' });
  });

  it('a chat that personal also claims stays personal', () => {
    const map = migrateChats([chat('olia', 'work', { circles: ['work', 'friends'] })], [personal, work], {}, 'me');
    expect(map).toEqual({ olia: 'personal' });
  });

  it('a first-run profile with empty passGroups keeps everything', () => {
    const first = P('first', 'personal', []);
    const map = migrateChats([chat('taras', 'work'), chat('mama', 'close')], [first], {}, 'me');
    expect(map).toEqual({ taras: 'first', mama: 'first' });
  });

  it('never moves Збережене or the assistant; keeps entries that already exist', () => {
    const map = migrateChats(
      [chat('chat_saved_notes', 'saved', { type: 'saved' }), chat('n', 'all', { type: 'phantom' }), chat('taras', 'work')],
      [personal, work],
      { taras: 'personal' },
      'me',
    );
    expect(map).toEqual({ taras: 'personal' });
  });
});

describe('chatProfileStore', () => {
  it('profileOf: missing or deleted owner → Особисте', () => {
    useChatProfiles.getState().assign('taras', 'work');
    useChatProfiles.getState().assign('ghost', 'deleted-profile');
    expect(profileOf({ id: 'taras' })).toBe('work');
    expect(profileOf({ id: 'ghost' })).toBe('personal');
    expect(profileOf({ id: 'nobody' })).toBe('personal');
    expect(ownerOf('x', {}, [])).toBe('');
  });

  it('assign persists np.chatprofile.v1 {v:1,map,migratedAt}', () => {
    useChatProfiles.getState().assign('taras', 'work');
    expect(JSON.parse(localStorage.getItem(CHAT_PROFILE_KEY)!)).toEqual({ v: 1, map: { taras: 'work' }, migratedAt: null });
  });

  it('onChatCreated puts a new chat into the active profile, once', () => {
    useProfiles.setState({ activeId: 'work' });
    useChatProfiles.getState().onChatCreated({ id: 'new1' });
    useProfiles.setState({ activeId: 'personal' });
    useChatProfiles.getState().onChatCreated({ id: 'new1' });
    expect(profileOf({ id: 'new1' })).toBe('work');
  });

  it('remove(profileId) sends its chats to Особисте', () => {
    useChatProfiles.getState().assign('taras', 'work');
    useChatProfiles.getState().assign('mama', 'personal');
    useChatProfiles.getState().remove('work');
    expect(useChatProfiles.getState().map).toEqual({ mama: 'personal' });
    expect(profileOf({ id: 'taras' })).toBe('personal');
  });

  it('a corrupt store re-runs migration and never empties a list', () => {
    localStorage.setItem(CHAT_PROFILE_KEY, '{"v":1,"map":[1,2],"migratedAt":"x"');
    useChatProfiles.setState(loadChatProfiles());
    expect(useChatProfiles.getState().migratedAt).toBeNull();
    localStorage.setItem(CHAT_PROFILE_KEY, JSON.stringify({ v: 1, map: { a: 5, b: 'work' }, migratedAt: 'soon' }));
    const loaded = loadChatProfiles();
    expect(loaded).toEqual({ map: { b: 'work' }, migratedAt: null });
    useChatProfiles.setState(loaded);
    useChatProfiles.getState().migrate([chat('a', 'close'), chat('b', 'close'), chat('c', 'work')], 'me', 100);
    const s = useChatProfiles.getState();
    expect(s.map).toEqual({ a: 'personal', b: 'work', c: 'work' });
    expect(s.migratedAt).toBe(100);
  });
});

describe('startChatProfiles: silent migration on the first hydrated list', () => {
  type Src = { chats: Chat[]; hydrated: boolean; currentUser: { id: string } };
  const source = () => create<Src>(() => ({ chats: [], hydrated: false, currentUser: { id: 'me' } }));

  it('waits for hydration, migrates once, then new chats go to the active profile and strangers to Особисте', () => {
    const src = source();
    const stop = startChatProfiles(src);
    try {
      expect(useChatProfiles.getState().migratedAt).toBeNull();
      src.setState({ hydrated: true, chats: [chat('taras', 'work'), chat('mama', 'close')] });
      const first = useChatProfiles.getState();
      expect(first.migratedAt).not.toBeNull();
      expect(first.map).toEqual({ taras: 'work', mama: 'personal' });

      useProfiles.setState({ activeId: 'work' });
      src.setState((s) => ({
        chats: [...s.chats, chat('client', 'work'), chat('stranger', '', { circles: [], lastSelfAt: null })],
      }));
      expect(profileOf({ id: 'client' })).toBe('work');
      expect(profileOf({ id: 'stranger' })).toBe('personal');
      expect(useChatProfiles.getState().migratedAt).toBe(first.migratedAt);
    } finally {
      stop();
    }
  });

  it('an empty first list does not use up the migration (Особисте never empties)', () => {
    const src = source();
    const stop = startChatProfiles(src);
    try {
      useProfiles.setState({ activeId: 'work' });
      src.setState({ hydrated: true, chats: [chat('chat_saved_notes', 'saved', { type: 'saved' })] });
      expect(useChatProfiles.getState().migratedAt).toBeNull();
      src.setState({ chats: [chat('mama', 'close'), chat('taras', 'work')] });
      expect(useChatProfiles.getState().map).toEqual({ mama: 'personal', taras: 'work' });
      expect(useChatProfiles.getState().migratedAt).not.toBeNull();
    } finally {
      stop();
    }
  });

  it('a removed profile hands its chats back to Особисте', () => {
    const src = source();
    const stop = startChatProfiles(src);
    try {
      src.setState({ hydrated: true, chats: [chat('taras', 'work')] });
      expect(profileOf({ id: 'taras' })).toBe('work');
      useProfiles.setState({ profiles: [personal] });
      expect(useChatProfiles.getState().map).toEqual({});
      expect(profileOf({ id: 'taras' })).toBe('personal');
    } finally {
      stop();
    }
  });

  it('group members come from the group store, not only from opened groups: each unopened group is asked once, in turn', async () => {
    const asked: string[] = [];
    const src = create<Src & { loadGroupMembers: (id: string) => Promise<boolean> }>((set, get) => ({
      chats: [],
      hydrated: false,
      currentUser: { id: 'me' },
      loadGroupMembers: async (id) => {
        asked.push(id);
        await Promise.resolve();
        set({
          chats: get().chats.map((c) =>
            c.id === id ? ({ ...c, members: [{ id: 'me', isMe: true }, { id: 'node-me-alias', isMe: true }, { id: `node-${id}` }] } as Chat) : c,
          ),
        });
        return true;
      },
    }));
    const setProfiles = vi.fn();
    (globalThis as { PhantomBridge?: unknown }).PhantomBridge = { setProfiles };
    useChatProfiles.setState({ map: { g1: 'work', g2: 'personal', ch: 'work' }, migratedAt: 1 });
    const stop = startChatProfiles(src);
    try {
      src.setState({
        hydrated: true,
        chats: [
          chat('g1', 'all', { type: 'group' }),
          chat('g2', 'all', { type: 'group' }),
          chat('ch', 'all', { type: 'channel' }),
          chat('g3', 'all', { type: 'group', members: [{ id: 'node-x' }] } as Partial<Chat>),
        ],
      });
      await vi.waitFor(() => expect(asked).toEqual(['g1', 'g2']));
      await vi.waitFor(() => {
        const last = JSON.parse(setProfiles.mock.calls.at(-1)![0] as string);
        expect(last.peerProfile['node-g1']).toBe('work');
        expect(last.peerProfile['node-g2']).toBe('personal');
        expect(last.peerProfile['node-me-alias']).toBeUndefined();
      });
      src.setState({ chats: [...src.getState().chats] });
      await Promise.resolve();
      expect(asked).toEqual(['g1', 'g2']);
    } finally {
      stop();
      delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
    }
  });
});

