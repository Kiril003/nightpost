/**
 * S10 (SPEC-2810 §4, D5): момент із профілю бачать лише люди цього профілю,
 * кола звужують усередині нього; свої дописи — у профілі, з якого їх виклали.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';

const bridge = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('../services/api', async (orig) => {
  const real = await orig<typeof import('../services/api')>();
  return { ...real, request: bridge.request };
});

import { MomentComposer } from '../components/messenger/MomentsStrip';
import { MomentsFeed } from '../components/messenger/MomentsFeed';
import { momentPeople } from '../components/messenger/MomentViewer';
import { readMoments, useMoments } from '../services/moments';
import { MOMENT_PROFILE_KEY, useMomentProfiles } from '../services/momentProfile';
import { useChatProfiles } from '../stores/chatProfileStore';
import { templateDefaults, useProfiles, type Profile, type ProfileKind } from '../stores/profilesStore';
import { useMessengerStore } from '../stores/messengerStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import type { Chat } from '../types/messenger';

const P = (id: string, kind: ProfileKind): Profile => ({ ...templateDefaults(kind, 1), id, name: id });
const personal = P('personal', 'personal');
const work = P('work', 'work');

const chat = (id: string, circle: string): Chat =>
  ({
    id,
    title: id,
    type: 'dm',
    circle,
    avatar: '',
    unreadCount: 0,
    pinned: false,
    messages: [],
    lastSnippet: 'hi',
    lastAt: new Date().toISOString(),
    peerNodeId: id,
  }) as Chat;

const chats = [chat('olena', 'close'), chat('mum', 'personal'), chat('taras', 'work'), chat('ivan', 'friends')];

const profilesBefore = useProfiles.getState();
const chatsBefore = useMessengerStore.getState().chats;
const origLoadPrefs = useMoments.getState().loadPrefs;

const posted = () =>
  bridge.request.mock.calls.filter(([m, p]) => m === 'POST' && p === '/messenger/moments').map(([, , b]) => b as { audience: { kind: string; circles?: string[] }; to?: string[] });

beforeEach(() => {
  localStorage.clear();
  localStorage.setItem('nightpost.moments.audienceChosen', '1');
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
  useProfiles.setState({ activeId: 'work', profiles: [personal, work], previousId: null });
  useChatProfiles.setState({ map: { olena: 'personal', mum: 'personal', taras: 'work', ivan: 'work' }, migratedAt: 1 });
  useMomentProfiles.setState({ map: {} });
  useMessengerStore.setState({ chats });
  useMoments.setState({ list: [], prefs: null, loadPrefs: origLoadPrefs });
  bridge.request.mockReset().mockImplementation((method: string, path: string, body?: unknown) => {
    if (path === '/messenger/moments/prefs') {
      return Promise.resolve({ audience: { kind: 'contacts' }, to: [], keep: false, everyone: false, muted: [] });
    }
    if (method === 'POST' && path === '/messenger/moments') {
      const b = body as { text: string; to?: string[]; audience: unknown };
      return Promise.resolve({
        id: 'w'.repeat(32), from_id: 'me', from_name: '', mine: true, at_ms: Date.now(), until_ms: Date.now() + 86_400_000,
        keep: false, audience: b.audience, text: b.text, seen: true, to_count: b.to?.length ?? 0, media: [],
      });
    }
    return Promise.resolve({});
  });
});

afterEach(() => {
  cleanup();
  localStorage.clear();
  useProfiles.setState({ activeId: profilesBefore.activeId, profiles: profilesBefore.profiles, previousId: null });
  useMessengerStore.setState({ chats: chatsBefore });
  useMoments.setState({ list: [], loadPrefs: origLoadPrefs });
});

const compose = async () => {
  render(<MomentComposer file={null} people={momentPeople(chats)} chats={chats} onClose={vi.fn()} />);
  const sheet = await screen.findByRole('dialog');
  fireEvent.click(sheet.querySelector('[data-moment-audience-chip]')!);
  return sheet;
};

const share = (sheet: HTMLElement, text: string) => {
  fireEvent.change(sheet.querySelector('[data-moment-caption]')!, { target: { value: text } });
  fireEvent.click(sheet.querySelector('[data-moment-share]')!);
};

describe('S10: audience = peers of the active profile', () => {
  it('a moment posted in «Робота» to contacts targets only Робота-owned peers, and the sheet says «Бачать 2»', async () => {
    const sheet = await compose();
    fireEvent.click(document.querySelector('[data-testid="aud-contacts"]')!);
    await waitFor(() => expect(document.querySelector('[data-testid="aud-count"]')?.textContent).toMatch(/^Бачать 2/));
    share(sheet, 'робоче');
    await waitFor(() => expect(posted()).toHaveLength(1));
    expect(posted()[0].audience.kind).toBe('people');
    expect([...(posted()[0].to ?? [])].sort()).toEqual(['ivan', 'taras']);
  });

  it('the circles filter narrows inside the profile: only its circles, «Робоче» gives Taras alone', async () => {
    const sheet = await compose();
    fireEvent.click(document.querySelector('[data-testid="aud-circles"]')!);
    expect(document.querySelector('[data-testid="aud-circle-close"]')).toBeNull();
    expect(document.querySelector('[data-testid="aud-circle-personal"]')).toBeNull();
    fireEvent.click(document.querySelector('[data-testid="aud-circle-work"]')!);
    await waitFor(() => expect(document.querySelector('[data-testid="aud-count"]')?.textContent).toMatch(/^Бачать 1/));
    share(sheet, 'колегам');
    await waitFor(() => expect(posted()).toHaveLength(1));
    expect(posted()[0].to).toEqual(['taras']);
  });

  it('«Вибрані люди» lists only the profile\'s people', async () => {
    await compose();
    fireEvent.click(document.querySelector('[data-testid="aud-people"]')!);
    const shown = [...document.querySelectorAll('[data-moment-person]')].map((n) => n.getAttribute('data-moment-person')).sort();
    expect(shown).toEqual(['ivan', 'taras']);
  });

  it('with one profile nothing narrows: contacts stay contacts', async () => {
    useProfiles.setState({ activeId: 'personal', profiles: [personal], previousId: null });
    const sheet = await compose();
    fireEvent.click(document.querySelector('[data-testid="aud-contacts"]')!);
    await waitFor(() => expect(document.querySelector('[data-testid="aud-count"]')?.textContent).toMatch(/^4/));
    share(sheet, 'усім');
    await waitFor(() => expect(posted()).toHaveLength(1));
    expect(posted()[0].audience).toEqual({ kind: 'contacts' });
  });
});

describe('S10: own posts live in the profile they were posted from', () => {
  it('posting records the active profile', async () => {
    const sheet = await compose();
    fireEvent.click(document.querySelector('[data-testid="aud-contacts"]')!);
    share(sheet, 'робоче');
    await waitFor(() => expect(useMomentProfiles.getState().map['w'.repeat(32)]).toBe('work'));
    expect(JSON.parse(localStorage.getItem(MOMENT_PROFILE_KEY) ?? '{}').map).toEqual({ ['w'.repeat(32)]: 'work' });
  });

  it('the feed shows my Робота post only in «Робота»; an unrecorded old post stays in «Особисте»', () => {
    const row = (id: string, mine: boolean) => ({ id, from_id: mine ? 'me' : 'olena', from_name: '', mine, at_ms: Date.now(), until_ms: Date.now() + 86_400_000, keep: false, audience: { kind: 'contacts' }, text: id, seen: true, media: [] });
    const list = readMoments([row('a'.repeat(32), true), row('b'.repeat(32), true), row('c'.repeat(32), false)]);
    useMomentProfiles.setState({ map: { ['a'.repeat(32)]: 'work' } });
    useMoments.setState({ list, refresh: async () => {} });
    const shown = () => [...document.querySelectorAll('[data-feed-post]')].map((n) => n.getAttribute('data-feed-post')![0]).sort();

    useProfiles.setState({ activeId: 'personal' });
    const { unmount } = render(<MomentsFeed />);
    expect(shown()).toEqual(['b', 'c']);
    unmount();

    // Зріз 4: чужі моменти — лише від людей активного профілю; Олена в «Особистому».
    useProfiles.setState({ activeId: 'work' });
    render(<MomentsFeed />);
    expect(shown()).toEqual(['a']);
  });
});
