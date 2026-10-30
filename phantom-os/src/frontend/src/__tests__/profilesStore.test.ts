import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { applyBootstrapLanguage } from '../services/settingsBootstrap';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import {
  loadProfiles,
  PASS_NONE,
  PROFILE_KINDS,
  PROFILES_KEY,
  UNREAD_KEY,
  startProfiles,
  templateDefaults,
  useProfiles,
  type Profile,
} from '../stores/profilesStore';

const NOW = 1_700_000_000_000;

describe('profilesStore', () => {
  let stop: (() => void) | undefined;

  beforeEach(() => {
    localStorage.clear();
    document.documentElement.lang = 'uk';
    applyBootstrapLanguage();
    useProfiles.setState(loadProfiles());
  });

  afterEach(() => {
    stop?.();
    stop = undefined;
    localStorage.clear();
  });

  it('returns the Ukrainian personal profile with empty passGroups on first run', () => {
    const state = loadProfiles();
    expect(state.profiles).toHaveLength(1);
    const first = state.profiles[0];
    expect(first.name).toBe('Особисте');
    expect(first.kind).toBe('personal');
    expect(first.passGroups).toEqual([]);
    expect(state.activeId).toBe(first.id);
  });

  it('produces pure templateDefaults for every profile kind', () => {
    expect(PROFILE_KINDS).toEqual([
      'personal',
      'family',
      'work',
      'business',
      'dev',
      'study',
      'trip',
      'hobby',
      'author',
      'place',
      'org',
      'temp',
      'unnamed',
      'own',
    ]);

    for (const kind of ['personal', 'family', 'hobby'] as const) {
      const d = templateDefaults(kind, NOW);
      expect(d.passGroups).toEqual(['personal', 'close', 'friends']);
      expect(d.quiet).toBeNull();
      expect(d.expiresAt).toBeNull();
      expect(d.assistantReads).toBe(true);
    }

    for (const [kind, from] of [['work', '20:00'], ['study', '16:00']] as const) {
      const d = templateDefaults(kind, NOW);
      expect(d.passGroups).toEqual(['work']);
      expect(d.quiet).toEqual({ from, to: '08:00' });
      expect(d.expiresAt).toBeNull();
      expect(d.assistantReads).toBe(true);
    }

    expect(templateDefaults('author', NOW).passGroups).toEqual(['friends', 'social']);
    for (const kind of ['author', 'place'] as const) {
      const d = templateDefaults(kind, NOW);
      if (kind === 'place') expect(d.passGroups).toEqual([]);
      expect(d.quiet).toBeNull();
      expect(d.expiresAt).toBeNull();
    }

    const org = templateDefaults('org', NOW);
    expect(org.passGroups).toEqual(['work']);
    expect(org.quiet).toBeNull();

    const temp = templateDefaults('temp', NOW);
    expect(temp.passGroups).toEqual([]);
    expect(temp.expiresAt).toBe(NOW + 864e5);
    expect(temp.look).toEqual({ accent: 'amber' });

    const work = templateDefaults('work', NOW);
    expect(work.look).toEqual({ accent: 'sea', glyph: 'work' });
    expect('schedule' in work).toBe(false);

    const business = templateDefaults('business', NOW);
    expect(business.look).toEqual({ accent: 'sage', glyph: 'shop' });

    const study = templateDefaults('study', NOW);
    expect(study.look).toEqual({ accent: 'iris', glyph: 'book' });

    const family = templateDefaults('family', NOW);
    expect(family.look).toEqual({ accent: 'rose', glyph: 'heart' });
    expect(family.quiet).toBeNull();

    const trip = templateDefaults('trip', NOW);
    expect(trip.look).toEqual({ accent: 'graphite', glyph: 'plane' });

    const unnamed = templateDefaults('unnamed', NOW);
    expect(unnamed.passGroups).toEqual([PASS_NONE]);
    expect(unnamed.assistantReads).toBe(false);

    const own = templateDefaults('own', NOW);
    expect(own.passGroups).toEqual([]);
    expect(own.faces).toEqual({});
    expect(own.homeOrder).toEqual([]);
    expect(own.walletLabel).toBeNull();
    expect(own.autoByDay).toBe(false);
    expect(own.uses).toBe(0);
    expect(own.createdAt).toBe(NOW);

    for (const kind of PROFILE_KINDS) {
      expect(templateDefaults(kind, NOW)).toEqual(templateDefaults(kind, NOW));
    }
  });

  it('resolves template names and trims on rename while ignoring blank names', () => {
    const initialActive = useProfiles.getState().activeId;
    const workDefault = useProfiles.getState().addFromTemplate('work');
    expect(workDefault.name).toBe('Робота');
    expect(useProfiles.getState().activeId).toBe(initialActive);

    const workCustom = useProfiles.getState().addFromTemplate('work', 'Офіс');
    expect(workCustom.name).toBe('Офіс');

    useProfiles.getState().rename(workCustom.id, '  X  ');
    expect(
      useProfiles.getState().profiles.find((p) => p.id === workCustom.id)?.name,
    ).toBe('X');

    useProfiles.getState().rename(workCustom.id, '   ');
    expect(
      useProfiles.getState().profiles.find((p) => p.id === workCustom.id)?.name,
    ).toBe('X');
  });

  it('refuses to remove the last profile and moves activeId to first remaining when removing active', () => {
    const first = useProfiles.getState().profiles[0];
    expect(useProfiles.getState().remove(first.id)).toBe(false);
    expect(useProfiles.getState().profiles).toHaveLength(1);

    const second = useProfiles.getState().addFromTemplate('work');
    const third = useProfiles.getState().addFromTemplate('hobby');
    useProfiles.getState().setActive(second.id);
    expect(useProfiles.getState().activeId).toBe(second.id);

    expect(useProfiles.getState().remove(second.id)).toBe(true);
    expect(useProfiles.getState().profiles.map((p) => p.id)).toEqual([
      first.id,
      third.id,
    ]);
    expect(useProfiles.getState().activeId).toBe(first.id);
  });

  it('tracks usage counts and orders mostUsed by uses desc then createdAt asc', () => {
    const a = useProfiles.getState().profiles[0];
    const b = useProfiles.getState().addFromTemplate('work');
    const c = useProfiles.getState().addFromTemplate('study');

    useProfiles.getState().update(a.id, { uses: 1 });
    useProfiles.setState((s) => ({
      ...s,
      profiles: s.profiles.map((p) => {
        if (p.id === a.id) return { ...p, createdAt: 100, uses: 1 };
        if (p.id === b.id) return { ...p, createdAt: 200, uses: 0 };
        return { ...p, createdAt: 50, uses: 1 };
      }),
    }));

    expect(useProfiles.getState().setActive(b.id)).toBe(true);
    expect(useProfiles.getState().setActive(b.id)).toBe(true);
    expect(
      useProfiles.getState().profiles.find((p) => p.id === b.id)?.uses,
    ).toBe(2);

    expect(useProfiles.getState().setActive('nope')).toBe(false);

    const top2 = useProfiles.getState().mostUsed(2);
    expect(top2.map((p) => p.id)).toEqual([b.id, c.id]);
  });

  it('sweeps only expired temp profiles and advances activeId if needed', () => {
    const expiredTemp: Profile = {
      ...templateDefaults('temp', NOW - 10_000),
      id: 'temp-expired',
      name: 'Тимчасовий 1',
      expiresAt: NOW - 1,
    };
    const validTemp: Profile = {
      ...templateDefaults('temp', NOW),
      id: 'temp-valid',
      name: 'Тимчасовий 2',
      expiresAt: NOW + 86_400_000,
    };
    const workPastExpiry: Profile = {
      ...templateDefaults('work', NOW),
      id: 'work-1',
      name: 'Робота',
      expiresAt: null,
    };

    useProfiles.setState({
      activeId: expiredTemp.id,
      profiles: [expiredTemp, validTemp, workPastExpiry],
    });
    useProfiles.getState().update(workPastExpiry.id, { expiresAt: NOW - 5_000 });

    useProfiles.getState().sweepExpired(NOW);

    const state = useProfiles.getState();
    expect(state.profiles.map((p) => p.id)).toEqual([
      validTemp.id,
      workPastExpiry.id,
    ]);
    expect(state.activeId).toBe(validTemp.id);
  });

  it('persists changes and reloads the same profiles and activeId', () => {
    const study = useProfiles.getState().addFromTemplate('study');
    useProfiles.getState().setActive(study.id);

    const expected = useProfiles.getState();
    const reloaded = loadProfiles();

    expect(reloaded.activeId).toBe(expected.activeId);
    expect(reloaded.profiles).toEqual(expected.profiles);
    stop = startProfiles();
    expect(useProfiles.getState().activeId).toBe(study.id);
  });

  it('handles garbage storage, empty lists, unknown kinds, and stale activeId', () => {
    localStorage.setItem(PROFILES_KEY, '{nope');
    const fromBadJson = loadProfiles();
    expect(fromBadJson.profiles).toHaveLength(1);
    expect(fromBadJson.profiles[0].kind).toBe('personal');

    localStorage.setItem(PROFILES_KEY, '{"v":1,"profiles":[]}');
    const fromEmpty = loadProfiles();
    expect(fromEmpty.profiles).toHaveLength(1);
    expect(fromEmpty.profiles[0].kind).toBe('personal');

    localStorage.setItem(
      PROFILES_KEY,
      JSON.stringify({
        v: 1,
        activeId: 'stale-id',
        profiles: [
          { id: 'bad', name: 'Boss', kind: 'boss' },
          { id: 'ok-1', name: 'Робота', kind: 'work' },
          { id: 'ok-2', name: 'Хобі', kind: 'hobby' },
        ],
      }),
    );
    const filtered = loadProfiles();
    expect(filtered.profiles.map((p) => p.id)).toEqual(['ok-1', 'ok-2']);
    expect(filtered.activeId).toBe('ok-1');
    expect(filtered.profiles[0].quiet).toEqual({ from: '20:00', to: '08:00' });
  });

  it('names template profiles in the active app language, not the page lang', () => {
    document.documentElement.lang = 'uk';
    useSettingsStore.setState({
      values: { ...useSettingsStore.getState().values, [LANGUAGE_SETTING_KEY]: 'en' },
    });
    try {
      expect(useProfiles.getState().addFromTemplate('work').name).toBe('Work');
      localStorage.clear();
      expect(loadProfiles().profiles[0].name).toBe('Personal');
    } finally {
      useSettingsStore.setState({
        values: { ...useSettingsStore.getState().values, [LANGUAGE_SETTING_KEY]: 'uk' },
      });
    }
  });

  it('keeps one copy of a duplicated id and removes it without emptying the list', () => {
    localStorage.setItem(
      PROFILES_KEY,
      JSON.stringify({
        v: 1,
        activeId: 'a',
        profiles: [
          { id: 'a', name: 'Особисте', kind: 'personal' },
          { id: 'a', name: 'Копія', kind: 'personal' },
          { id: 'b', name: 'Робота', kind: 'work' },
        ],
      }),
    );
    useProfiles.setState(loadProfiles());
    expect(useProfiles.getState().profiles.map((p) => p.id)).toEqual(['a', 'b']);
    expect(useProfiles.getState().remove('a')).toBe(true);
    expect(useProfiles.getState().profiles.map((p) => p.id)).toEqual(['b']);
    expect(useProfiles.getState().activeId).toBe('b');
  });

  it('keeps an unreadable record aside and settles on one stable profile id', () => {
    const stored = JSON.stringify({ v: 1, activeId: 'x', profiles: [{ id: 'x', name: 'Майбутнє', kind: 'future-kind' }] });
    localStorage.setItem(PROFILES_KEY, stored);
    const first = loadProfiles();
    expect(first.profiles[0].kind).toBe('personal');
    expect(localStorage.getItem(UNREAD_KEY)).toBe(stored);
    useProfiles.setState(first);
    useProfiles.getState().addFromTemplate('work');
    expect(localStorage.getItem(UNREAD_KEY)).toBe(stored);
    expect(loadProfiles().profiles[0].id).toBe(first.profiles[0].id);
  });

  it('merges and removes keys via setProfileLook and normalizes old stored profiles', () => {
    const first = useProfiles.getState().profiles[0];
    expect(first.look).toBeNull();

    useProfiles.getState().setProfileLook(first.id, { accent: 'sea', wallpaper: 'sand', glyph: 'work' });
    expect(useProfiles.getState().profiles[0].look).toEqual({
      accent: 'sea',
      wallpaper: 'sand',
      glyph: 'work',
    });

    useProfiles.getState().setProfileLook(first.id, { wallpaper: undefined });
    const afterPatch = useProfiles.getState().profiles[0].look;
    expect(afterPatch).toEqual({
      accent: 'sea',
      glyph: 'work',
    });
    expect('wallpaper' in (afterPatch ?? {})).toBe(false);

    localStorage.setItem(
      PROFILES_KEY,
      JSON.stringify({
        v: 1,
        activeId: 'old-1',
        profiles: [
          { id: 'old-1', name: 'Старий', kind: 'work', look: { theme: 'night' } },
          {
            id: 'old-2',
            name: 'Частковий',
            kind: 'personal',
            look: { accent: 'iris', wallpaper: 'unknown-wp', theme: 'day', glyph: 'book', extra: 1 },
          },
        ],
      }),
    );
    const loaded = loadProfiles();
    expect(loaded.profiles[0].look).toBeNull();
    expect(loaded.profiles[1].look).toEqual({ accent: 'iris', glyph: 'book' });
    expect('theme' in (loaded.profiles[1].look ?? {})).toBe(false);
  });
});
