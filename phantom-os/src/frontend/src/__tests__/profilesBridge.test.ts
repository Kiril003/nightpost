import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { applyBootstrapLanguage } from '../services/settingsBootstrap';
import { create } from 'zustand';
import type { Chat } from '../types/messenger';
import { loadChatProfiles, startChatProfiles, useChatProfiles } from '../stores/chatProfileStore';
import {
  buildBridgePayload,
  loadProfiles,
  PROFILES_KEY,
  registerProfilesOpener,
  startProfiles,
  templateDefaults,
  useProfiles,
  glyphForProfile,
} from '../stores/profilesStore';

type BridgeGlobal = typeof globalThis & {
  PhantomBridge?: { setProfiles?: (json: string) => void };
};

type ProfilesWindow = Window & {
  __phantomProfiles?: { setActive: (id: string) => boolean; open: () => boolean };
};

describe('profilesBridge', () => {
  let stop: (() => void) | undefined;

  beforeEach(() => {
    localStorage.clear();
    useChatProfiles.setState(loadChatProfiles());
    document.documentElement.lang = 'uk';
    applyBootstrapLanguage();
    delete (globalThis as BridgeGlobal).PhantomBridge;
    useProfiles.setState(loadProfiles());
  });

  afterEach(() => {
    stop?.();
    stop = undefined;
    delete (globalThis as BridgeGlobal).PhantomBridge;
    localStorage.clear();
  });

  it('pushes the exact v2 contract (14-key profiles + peerProfile + chatProfile) on startProfiles()', () => {
    const setProfiles = vi.fn();
    (globalThis as BridgeGlobal).PhantomBridge = { setProfiles };

    stop = startProfiles();

    expect(setProfiles).toHaveBeenCalledTimes(1);
    const payload = JSON.parse(setProfiles.mock.calls[0][0] as string);
    const state = useProfiles.getState();
    const first = state.profiles[0];

    expect(Object.keys(payload)).toEqual(['v', 'activeId', 'profiles', 'peerProfile', 'chatProfile']);
    expect(payload).toEqual({
      v: 2,
      activeId: state.activeId,
      peerProfile: {},
      chatProfile: {},
      profiles: [
        {
          id: first.id,
          name: first.name,
          kind: first.kind,
          passGroups: first.passGroups,
          quiet: first.quiet,
          alert: 'sound',
          expiresAt: first.expiresAt,
          faces: first.faces,
          ownName: first.ownName,
          kit: first.kit,
          bar: ['map', 'wallet'],
          slots: [],
          accent: first.look?.accent ?? null,
          glyph: glyphForProfile(first),
        },
      ],
    });
    expect(Object.keys(payload.profiles[0])).toEqual([
      'id',
      'name',
      'kind',
      'passGroups',
      'quiet',
      'alert',
      'expiresAt',
      'faces',
      'ownName',
      'kit',
      'bar',
      'slots',
      'accent',
      'glyph',
    ]);
    expect(payload.profiles[0]).not.toHaveProperty('walletLabel');
    expect(payload.profiles[0]).not.toHaveProperty('assistantReads');
  });

  it('pushes again when a profile is added after startProfiles()', () => {
    const setProfiles = vi.fn();
    (globalThis as BridgeGlobal).PhantomBridge = { setProfiles };

    stop = startProfiles();
    expect(setProfiles).toHaveBeenCalledTimes(1);

    useProfiles.getState().addFromTemplate('work');
    expect(setProfiles).toHaveBeenCalledTimes(2);

    const secondPayload = JSON.parse(setProfiles.mock.calls[1][0] as string);
    expect(secondPayload.profiles).toHaveLength(2);
    expect(secondPayload.profiles[1].kind).toBe('work');
  });

  it('does not throw when PhantomBridge is empty or undefined', () => {
    (globalThis as BridgeGlobal).PhantomBridge = {};
    expect(() => {
      stop = startProfiles();
      useProfiles.getState().addFromTemplate('hobby');
    }).not.toThrow();
    stop?.();
    stop = undefined;

    delete (globalThis as BridgeGlobal).PhantomBridge;
    expect(() => {
      stop = startProfiles();
      useProfiles.getState().addFromTemplate('study');
    }).not.toThrow();
  });

  it('sweeps expired temp profiles before the initial push', () => {
    const now = Date.now();
    localStorage.setItem(
      PROFILES_KEY,
      JSON.stringify({
        v: 1,
        activeId: 'expired-temp',
        profiles: [
          {
            ...templateDefaults('temp', now - 20_000),
            id: 'expired-temp',
            name: 'Старий тимчасовий',
            expiresAt: now - 1_000,
          },
          {
            ...templateDefaults('personal', now - 20_000),
            id: 'keep-personal',
            name: 'Особисте',
            passGroups: [],
          },
        ],
      }),
    );

    const setProfiles = vi.fn();
    (globalThis as BridgeGlobal).PhantomBridge = { setProfiles };

    stop = startProfiles();

    expect(setProfiles).toHaveBeenCalledTimes(1);
    const payload = JSON.parse(setProfiles.mock.calls[0][0] as string);
    expect(payload.profiles.map((p: { id: string }) => p.id)).toEqual([
      'keep-personal',
    ]);
    expect(payload.activeId).toBe('keep-personal');
  });

  it('exposes window.__phantomProfiles.setActive and cleans it up on stop()', () => {
    const setProfiles = vi.fn();
    (globalThis as BridgeGlobal).PhantomBridge = { setProfiles };

    stop = startProfiles();
    const work = useProfiles.getState().addFromTemplate('work');
    setProfiles.mockClear();

    const api = (window as ProfilesWindow).__phantomProfiles;
    expect(api).toBeDefined();

    expect(api!.setActive(work.id)).toBe(true);
    expect(useProfiles.getState().activeId).toBe(work.id);
    expect(setProfiles).toHaveBeenCalledTimes(1);

    expect(api!.setActive('unknown')).toBe(false);
    expect(useProfiles.getState().activeId).toBe(work.id);
    expect(setProfiles).toHaveBeenCalledTimes(1);

    stop();
    stop = undefined;
    expect((window as ProfilesWindow).__phantomProfiles).toBeUndefined();
  });

  it('keeps the same first-run profile id across cold starts', () => {
    localStorage.clear();
    const setProfiles = vi.fn();
    (globalThis as BridgeGlobal).PhantomBridge = { setProfiles };

    stop = startProfiles();
    const first = JSON.parse(setProfiles.mock.calls[0][0]).activeId;
    stop();
    stop = undefined;
    setProfiles.mockClear();

    stop = startProfiles();
    expect(JSON.parse(setProfiles.mock.calls[0][0]).activeId).toBe(first);
  });

  it('sends only string face fields from stored profiles', () => {
    localStorage.setItem(
      PROFILES_KEY,
      JSON.stringify({
        v: 1,
        activeId: 'p1',
        profiles: [
          {
            id: 'p1',
            name: 'Особисте',
            kind: 'personal',
            faces: {
              work: { name: 'Олег', status: 5, bio: { x: 1 }, photoId: 'ph1', extra: 'no' },
              bad: 7,
              list: ['a'],
            },
          },
        ],
      }),
    );
    const setProfiles = vi.fn();
    (globalThis as BridgeGlobal).PhantomBridge = { setProfiles };

    stop = startProfiles();
    const payload = JSON.parse(setProfiles.mock.calls[0][0]);
    expect(payload.profiles[0].faces).toEqual({ work: { name: 'Олег', photoId: 'ph1' } });
  });

  it('opens the profiles list through window.__phantomProfiles.open() only when mounted', () => {
    stop = startProfiles();
    const api = (window as ProfilesWindow).__phantomProfiles!;
    expect(api.open()).toBe(false);

    const opened = vi.fn();
    const unregister = registerProfilesOpener(opened);
    expect(api.open()).toBe(true);
    expect(opened).toHaveBeenCalledTimes(1);

    unregister();
    expect(api.open()).toBe(false);
  });

  it("зміна мови заново шле нативній смузі підписи вкладок", async () => {
    const { useSettingsStore, LANGUAGE_SETTING_KEY } = await import("../stores/settingsStore");
    const setProfiles = vi.fn();
    (globalThis as BridgeGlobal).PhantomBridge = { setProfiles };
    stop = startProfiles();
    const st = useProfiles.getState();
    useProfiles.setState({ profiles: st.profiles.map((p, i) => (i === 0 ? { ...p, kind: "work" } : p)) });
    useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: "en" } }));
    const last = JSON.parse(setProfiles.mock.calls.at(-1)![0] as string);
    expect(last.profiles[0].slots.map((x: { label: string }) => x.label)).toEqual(["Tasks", "Shifts"]);
  });

  it('v2 maps: 1:1 by peerNodeId, groups by group id, group-only peers by the oldest shared group', () => {
    const now = Date.now();
    useProfiles.setState({
      activeId: 'p_personal',
      previousId: null,
      profiles: [
        { ...templateDefaults('personal', now), id: 'p_personal', name: 'Особисте' },
        { ...templateDefaults('work', now), id: 'p_work', name: 'Робота' },
      ],
    });
    const member = (id: string) => ({ id, name: id, handle: '', avatar: '', role: 'member' });
    const chats = [
      { id: 'chat_saved_notes', type: 'saved', circle: 'saved' },
      { id: 'node_taras', peerNodeId: 'node_taras', type: 'dm', circle: 'work' },
      { id: 'node_mama', peerNodeId: 'node_mama', type: 'dm', circle: 'close' },
      { id: 'grp_new', type: 'group', circle: 'work', lastAt: '2026-10-25T10:00:00Z', members: [member('me'), member('node_olia')] },
      { id: 'grp_old', type: 'group', circle: 'friends', lastAt: '2026-10-01T10:00:00Z', members: [member('me'), member('node_olia'), member('node_taras')] },
    ].map((c) => ({ title: c.id, avatar: '', unreadCount: 0, messages: [], lastSelfAt: '2026-10-20T10:00:00Z', ...c })) as unknown as Chat[];
    const src = create(() => ({ chats, hydrated: true, currentUser: { id: 'me' } }));
    useChatProfiles.getState().assign('grp_new', 'p_work');
    const setProfiles = vi.fn();
    (globalThis as BridgeGlobal).PhantomBridge = { setProfiles };
    const stopChats = startChatProfiles(src);
    try {
      const payload = buildBridgePayload(useProfiles.getState());
      expect(payload.v).toBe(2);
      expect(payload.chatProfile).toEqual({
        node_taras: 'p_work',
        node_mama: 'p_personal',
        grp_new: 'p_work',
        grp_old: 'p_personal',
      });
      expect(payload.peerProfile).toEqual({
        node_taras: 'p_work',
        node_mama: 'p_personal',
        node_olia: 'p_personal',
      });
      const pushed = JSON.parse(setProfiles.mock.calls.at(-1)![0] as string);
      expect(pushed.chatProfile).toEqual(payload.chatProfile);

      setProfiles.mockClear();
      useChatProfiles.getState().assign('grp_old', 'p_work');
      expect(setProfiles).toHaveBeenCalled();
      expect(JSON.parse(setProfiles.mock.calls.at(-1)![0] as string).peerProfile.node_olia).toBe('p_work');
    } finally {
      stopChats();
    }
  });
});
