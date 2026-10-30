import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { inProfile, splitByProfile } from '../profileScope';
import { loadChatProfiles, useChatProfiles } from '../../stores/chatProfileStore';
import { templateDefaults, useProfiles, type Profile, type ProfileKind } from '../../stores/profilesStore';

const chat = (id: string, circle: string, extra: Record<string, unknown> = {}) =>
  ({ id, type: 'direct', circle, ...extra }) as never;

const P = (id: string, kind: ProfileKind): Profile => ({ ...templateDefaults(kind, 1), id, name: id });
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

describe('profileScope', () => {
  it('a chat sits only in the profile that owns it', () => {
    useChatProfiles.getState().assign('t', 'work');
    expect(inProfile(chat('t', 'work'), work)).toBe(true);
    expect(inProfile(chat('t', 'work'), personal)).toBe(false);
    expect(inProfile(chat('a', 'social'), null)).toBe(true);
  });

  it('an unowned chat belongs to Особисте, whatever its circle', () => {
    expect(inProfile(chat('m', 'work'), personal)).toBe(true);
    expect(inProfile(chat('m', 'work'), work)).toBe(false);
  });

  it('Робота keeps its own chats, the rest folds into «Інші»', () => {
    useChatProfiles.getState().assign('t', 'work');
    useChatProfiles.getState().assign('s', 'work');
    const { inside, outside } = splitByProfile([chat('t', 'work'), chat('m', 'close'), chat('s', 'social')], work);
    expect(inside.map((c: { id: string }) => c.id)).toEqual(['t', 's']);
    expect(outside.map((c: { id: string }) => c.id)).toEqual(['m']);
  });

  it('Збережене and the assistant stay in every profile; pinned no longer bypasses', () => {
    expect(inProfile(chat('chat_saved_notes', 'saved', { type: 'saved' }), work)).toBe(true);
    expect(inProfile(chat('n', 'all', { type: 'phantom' }), work)).toBe(true);
    expect(inProfile(chat('p', 'close', { pinned: true }), work)).toBe(false);
  });
});
