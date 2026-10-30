/**
 * msg-x зріз 4: моменти над «Розмовами» — лише люди активного профілю (S10),
 * свої — лише викладені з нього; нікого — чесне «Ще немає моментів» з «+».
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import React from 'react';
import { cleanup, render } from '@testing-library/react';
import { ListMomentsStrip, useListMoments } from '../components/messenger/list/ListMoments';
import { useMoments, type Moment } from '../services/moments';
import { useMomentProfiles } from '../services/momentProfile';
import { useChatProfiles } from '../stores/chatProfileStore';
import { templateDefaults, useProfiles, type Profile, type ProfileKind } from '../stores/profilesStore';
import { useMessengerStore } from '../stores/messengerStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Chat } from '../types/messenger';

const P = (id: string, kind: ProfileKind): Profile => ({ ...templateDefaults(kind, 1), id, name: id });
const chat = (id: string, circle: string): Chat =>
  ({ id, title: id, type: 'dm', circle, avatar: '', unreadCount: 0, pinned: false, messages: [], lastSnippet: 'hi', lastAt: new Date().toISOString(), peerNodeId: id }) as Chat;
const chats = [chat('olena', 'close'), chat('taras', 'work')];
const me = { id: 'me', name: 'Я', avatar: '' };

const moment = (id: string, fromId: string, mine = false): Moment => ({
  id, fromId, fromName: fromId, mine, atMs: Date.now() - 60_000, editedMs: 0, untilMs: Date.now() + 3_600_000, keep: false,
  reach: 'contacts', circles: [], text: id, seen: false, toCount: 0, media: [], viewers: [], reactions: {}, myReaction: '',
});

const Harness: React.FC = () => <ListMomentsStrip data={useListMoments(chats, me)} me={me} />;
const shown = () => [...document.querySelectorAll('[data-list-moment]')].map((n) => n.getAttribute('data-list-moment'));

const before = { profiles: useProfiles.getState(), chats: useMessengerStore.getState().chats };

beforeEach(() => {
  localStorage.clear();
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
  useProfiles.setState({ activeId: 'work', profiles: [P('personal', 'personal'), P('work', 'work')], previousId: null });
  useChatProfiles.setState({ map: { olena: 'personal', taras: 'work' }, migratedAt: 1 });
  useMomentProfiles.setState({ map: { mine1: 'personal' } });
  useMessengerStore.setState({ chats });
});

afterEach(() => {
  cleanup();
  useMoments.setState({ list: null });
  useProfiles.setState({ activeId: before.profiles.activeId, profiles: before.profiles.profiles, previousId: null });
  useMessengerStore.setState({ chats: before.chats });
});

describe('смуга моментів у «Розмовах» за активним профілем', () => {
  it('робочий профіль: лише Тарас; Олена й мій особистий момент — ні', () => {
    useMoments.setState({ list: [moment('o1', 'olena'), moment('t1', 'taras'), moment('mine1', 'me', true)] });
    render(<Harness />);
    expect(shown()).toEqual(['me', 'taras']);
    expect(document.querySelector('[data-moment-own-add]')).not.toBeNull();
  });

  it('особистий профіль: Олена і мій момент, без Тараса', () => {
    useProfiles.setState({ activeId: 'personal' });
    useMoments.setState({ list: [moment('o1', 'olena'), moment('t1', 'taras'), moment('mine1', 'me', true)] });
    render(<Harness />);
    expect(shown()).toEqual(['me', 'olena']);
    expect(document.querySelector('[data-moment-own-add]')).toBeNull();
  });

  it('нікого в профілі — «+ Додати» і «Ще немає моментів», без вигаданих кружків', () => {
    useMoments.setState({ list: [moment('o1', 'olena')] });
    render(<Harness />);
    expect(shown()).toEqual(['me']);
    expect(document.querySelector('[data-list-moments-empty]')?.textContent).toBe('Ще немає моментів');
    expect(document.querySelector('[data-moment-own-add]')?.textContent).toContain('Додати');
  });
});
